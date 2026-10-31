"""24/7 background acquisition daemon for Linux BlueZ.

Listens for QN-Scale advertisements, triggers the handshake on detection,
records all raw frames into SQLite, and resumes listening for the next weigh-in.
Zero profile knowledge: pure raw telemetry acquisition.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import signal
from collections.abc import Callable
from datetime import datetime, timezone
from typing import Any

from tracky_mcp.acquisition.service import AcquisitionService, WeighInRequest
from tracky_mcp.ble import ScaleClient, is_scale_device
from tracky_mcp.ble.cleanup import CLEANUP_TIMEOUT_SECONDS, bounded_await
from tracky_mcp.storage import SQLiteMeasurementStore

logger = logging.getLogger("tracky.daemon")


def _field(source: Any, name: str) -> Any:
    if isinstance(source, dict):
        return source.get(name)
    return getattr(source, name, None)


class TrackyDaemon:
    """Unattended BLE listener for zero-touch weigh-in capture."""

    def __init__(
        self,
        store: SQLiteMeasurementStore | None = None,
        *,
        scanner_factory: Callable[..., Any] | None = None,
        client_factory: Callable[..., Any] | None = None,
        cooldown_seconds: float = 10.0,
        acquisition_timeout_seconds: float = 45.0,
        adapter: str | None = None,
        scanner_health_interval_seconds: float = 30.0,
    ) -> None:
        self.store = store or SQLiteMeasurementStore()
        self._scanner_factory = scanner_factory
        self._client_factory = client_factory
        self.cooldown_seconds = cooldown_seconds
        self.acquisition_timeout_seconds = acquisition_timeout_seconds
        # Select explicitly so discovery and the health probe use the same adapter.
        self.adapter = adapter or "hci0"
        if scanner_health_interval_seconds <= 0:
            raise ValueError("scanner_health_interval_seconds must be greater than zero")
        self._scanner_health_interval_seconds = scanner_health_interval_seconds
        self._health_bus: Any = None
        self._bluez_owner: str | None = None

        self.service = AcquisitionService(
            store=self.store,
            client_factory=client_factory or self._default_client_factory,
        )
        self._scanner: Any = None
        self._running = False
        self._active_operation_id: str | None = None
        self._active_task: asyncio.Task[Any] | None = None
        self._last_completed_at: datetime | None = None
        self._lock = asyncio.Lock()

    @staticmethod
    def _default_client_factory(request: WeighInRequest, observer: Any) -> ScaleClient:
        return ScaleClient(
            request.device,
            wire_observer=observer,
            unit=request.unit,
        )

    def _create_scanner(self) -> Any:
        if self._scanner_factory is not None:
            return self._scanner_factory(
                detection_callback=self._on_detection,
                adapter=self.adapter,
            )
        from bleak import BleakScanner

        # BlueZ passive discovery requires kernel ``or_patterns`` support that
        # is absent on common adapters.  Active discovery is the tested
        # configuration and still lets the callback filter by name/service.
        kwargs: dict[str, Any] = {
            "detection_callback": self._on_detection,
            "scanning_mode": "active",
            "adapter": self.adapter,
        }
        return BleakScanner(**kwargs)

    def _on_detection(self, device: Any, advertisement_data: Any) -> None:
        """Invoked synchronously by bleak when an advertisement is received."""
        if not self._running:
            return
        if not is_scale_device(device, advertisement_data):
            return

        address = _field(device, "address") or "unknown"
        name = _field(device, "name") or "QN-Scale"

        loop = asyncio.get_running_loop()
        loop.create_task(self._handle_scale_seen(device, advertisement_data, address, name))

    async def _handle_scale_seen(
        self,
        device: Any,
        advertisement_data: Any,
        address: str,
        name: str,
    ) -> None:
        async with self._lock:
            if not self._running:
                return
            if self._active_operation_id is not None:
                logger.debug("Scale seen but acquisition already active; ignoring")
                return

            now = datetime.now(timezone.utc)
            if self._last_completed_at is not None:
                elapsed = (now - self._last_completed_at).total_seconds()
                if elapsed < self.cooldown_seconds:
                    logger.debug(
                        "Scale seen during cooldown (%0.1fs); ignoring",
                        elapsed,
                    )
                    return

            logger.info("Scale detected: %s. Starting acquisition.", name)

            # Register/update device in store
            service_uuids = _field(advertisement_data, "service_uuids") or []
            known = self.store.register_device(
                connection_target=str(address),
                seen_at=now,
                advertised_name=name,
                advertised_service_uuids=service_uuids,
            )

            request = WeighInRequest(
                device=device,
                unit="kg",
                device_alias=known.alias,
                model_label=known.model_label,
                address_hash=known.address_hash,
                timeout_seconds=self.acquisition_timeout_seconds,
            )

            try:
                operation_id = self.service.start(request)
                self._active_operation_id = operation_id
                self._active_task = asyncio.current_task()
            except Exception as exc:
                logger.exception("Failed to start scale acquisition: %s", exc)
                return

        # Await completion outside the lock so other detection callbacks can check active state
        try:
            operation = await self.service.wait(operation_id)
            logger.info(
                "Acquisition %s finished with status: %s",
                operation_id,
                operation.status.value,
            )
        except Exception as exc:
            logger.exception("Error during acquisition %s: %s", operation_id, exc)
        finally:
            async with self._lock:
                self._active_operation_id = None
                self._active_task = None
                self._last_completed_at = datetime.now(timezone.utc)

    async def start(self) -> None:
        """Start listening for scale advertisements."""
        if self._running:
            return
        logger.info("Starting Tracky 24/7 background acquisition daemon...")
        self._running = True
        self._scanner = self._create_scanner()
        await bounded_await(self._scanner.start())
        logger.info("Scanner active. Waiting for QN-Scale weigh-ins.")

    async def _check_scanner_health(self) -> None:
        """Probe live BlueZ state, not advertisement silence or cached properties.

        The dedicated D-Bus connection also detects a system-bus disconnect.
        A changed BlueZ owner means the scanner's original discovery session
        was lost, even if another client has since enabled discovery again.
        dbus-fast is already a dependency of Bleak's Linux backend.
        """

        from dbus_fast import BusType, Message, MessageType
        from dbus_fast.aio import MessageBus

        if self._health_bus is None:
            self._health_bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
        bus = self._health_bus
        if not bus.connected:
            raise RuntimeError("Scanner health failed: system D-Bus disconnected")
        owner_reply = await bus.call(Message(
            destination="org.freedesktop.DBus",
            path="/org/freedesktop/DBus",
            interface="org.freedesktop.DBus",
            member="GetNameOwner",
            signature="s",
            body=["org.bluez"],
        ))
        if owner_reply is None or owner_reply.message_type is MessageType.ERROR:
            raise RuntimeError("Scanner health failed: BlueZ unavailable")
        owner = owner_reply.body[0]
        if self._bluez_owner is not None and owner != self._bluez_owner:
            raise RuntimeError("Scanner health failed: BlueZ restarted")
        self._bluez_owner = owner
        reply = await bus.call(Message(
            destination=owner,
            path=f"/org/bluez/{self.adapter}",
            interface="org.freedesktop.DBus.Properties",
            member="GetAll",
            signature="s",
            body=["org.bluez.Adapter1"],
        ))
        if reply is None or reply.message_type is MessageType.ERROR:
            raise RuntimeError("Scanner health failed: adapter unavailable")
        properties = reply.body[0]
        if not all(properties.get(name) and properties[name].value
                   for name in ("Powered", "Discovering")):
            raise RuntimeError("Scanner health failed: adapter not powered/discovering")

    async def stop(self) -> None:
        """Stop listening and cleanly cancel any active acquisition."""
        if not self._running:
            return
        logger.info("Stopping Tracky daemon...")
        self._running = False
        if self._scanner is not None:
            try:
                await bounded_await(self._scanner.stop())
            except Exception:
                pass
            self._scanner = None

        if self._health_bus is not None:
            self._health_bus.disconnect()
            self._health_bus = None

        async with self._lock:
            operation_id = self._active_operation_id
            active_task = self._active_task
        if operation_id is not None:
            logger.info("Cancelling in-flight acquisition %s", operation_id)
            try:
                # Iterator cleanup and disconnect each have a five-second bound.
                await bounded_await(
                    self.service.cancel(operation_id), 3 * CLEANUP_TIMEOUT_SECONDS
                )
            except Exception:
                pass
        if active_task is not None and active_task is not asyncio.current_task():
            try:
                await bounded_await(asyncio.shield(active_task))
            except Exception:
                pass
        self._active_operation_id = None
        self.store.close()
        logger.info("Tracky daemon stopped cleanly.")

    async def run_forever(self) -> None:
        """Run until cancelled via asyncio cancellation or signal."""
        stop_event = asyncio.Event()

        def _signal_handler() -> None:
            logger.info("Shutdown signal received")
            stop_event.set()

        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(sig, _signal_handler)
            except NotImplementedError:
                # Windows event loop doesn't support add_signal_handler
                pass

        try:
            await self.start()
            await bounded_await(self._check_scanner_health())
            while not stop_event.is_set():
                try:
                    await asyncio.wait_for(
                        stop_event.wait(), self._scanner_health_interval_seconds
                    )
                except TimeoutError:
                    # Propagate failure: the CLI exits non-zero and systemd
                    # restarts discovery. No ads is normal when nobody weighs in.
                    await bounded_await(self._check_scanner_health())
        finally:
            await self.stop()


def main() -> None:
    """CLI entrypoint for tracky-daemon."""
    parser = argparse.ArgumentParser(
        description="Tracky 24/7 BlueZ background acquisition daemon for CS10B scales"
    )
    parser.add_argument(
        "--db-path",
        help="Path to SQLite database file (default: XDG standard data dir)",
        default=None,
    )
    parser.add_argument(
        "--adapter",
        help="Bluetooth adapter name (default: hci0)",
        default=None,
    )
    parser.add_argument(
        "--timeout",
        help="Max weigh-in acquisition duration in seconds (default: 45)",
        type=float,
        default=45.0,
    )
    parser.add_argument(
        "-v", "--verbose",
        action="store_true",
        help="Enable debug logging",
    )
    args = parser.parse_args()

    log_level = logging.DEBUG if args.verbose else logging.INFO
    logging.basicConfig(
        level=log_level,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    store = SQLiteMeasurementStore(path=args.db_path)
    daemon = TrackyDaemon(
        store=store,
        acquisition_timeout_seconds=args.timeout,
        adapter=args.adapter,
    )

    try:
        asyncio.run(daemon.run_forever())
    except (KeyboardInterrupt, SystemExit):
        pass


if __name__ == "__main__":
    main()
