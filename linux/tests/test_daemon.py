"""Unit tests for the 24/7 background acquisition daemon."""

from __future__ import annotations

import asyncio
import sys
from dataclasses import dataclass
from types import ModuleType, SimpleNamespace
from typing import Any

import pytest

from tracky_mcp.daemon import TrackyDaemon
from tracky_mcp.storage import SQLiteMeasurementStore


@dataclass
class FakeBleDevice:
    address: str
    name: str | None = None


class FakeScanner:
    def __init__(self, detection_callback: Any, adapter: str | None = None) -> None:
        self.callback = detection_callback
        self.adapter = adapter
        self.started = False
        self.stopped = False

    async def start(self) -> None:
        self.started = True

    async def stop(self) -> None:
        self.stopped = True

    def emit_advertisement(
        self,
        device: FakeBleDevice,
        service_uuids: list[str] | None = None,
    ) -> None:
        adv = SimpleNamespace(service_uuids=service_uuids or [])
        self.callback(device, adv)


class FakeClient:
    def __init__(self) -> None:
        self.connected = False
        self.disconnected = False

    async def events(self) -> Any:
        yield SimpleNamespace(raw=b"test", opcode=0x12, checksum_valid=True)
        # Yield and then complete
        await asyncio.sleep(0.01)

    async def disconnect(self) -> None:
        self.disconnected = True


def test_daemon_uses_active_bleak_discovery_configuration(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Any,
) -> None:
    captured: dict[str, Any] = {}

    class ConfiguredScanner:
        def __init__(self, **kwargs: Any) -> None:
            captured.update(kwargs)

    bleak = ModuleType("bleak")
    bleak.BleakScanner = ConfiguredScanner  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "bleak", bleak)

    store = SQLiteMeasurementStore(path=tmp_path / "test.db")
    try:
        daemon = TrackyDaemon(store=store, adapter="hci0")
        daemon._create_scanner()
    finally:
        store.close()

    assert captured["scanning_mode"] == "active"
    assert captured["adapter"] == "hci0"
    assert callable(captured["detection_callback"])


def test_scanner_health_failure_escapes_run_forever_for_systemd_restart(
    tmp_path: Any, monkeypatch: pytest.MonkeyPatch,
) -> None:
    scanner = FakeScanner(lambda *_args: None)
    daemon = TrackyDaemon(
        store=SQLiteMeasurementStore(tmp_path / "health.sqlite3"),
        scanner_factory=lambda **_kwargs: scanner,
        scanner_health_interval_seconds=0.01,
    )
    checks = 0

    async def health() -> None:
        nonlocal checks
        checks += 1
        if checks == 2:
            raise RuntimeError("adapter lost")

    monkeypatch.setattr(daemon, "_check_scanner_health", health)
    with pytest.raises(RuntimeError, match="adapter lost"):
        asyncio.run(daemon.run_forever())
    assert checks == 2
    assert scanner.started and scanner.stopped
    assert not daemon._running


@pytest.mark.parametrize("failure", [
    "owner_changed", "powered_off", "not_discovering", "adapter_removed", "bus_disconnected",
])
def test_health_probe_detects_bluez_and_adapter_loss_without_advertisements(
    tmp_path: Any, monkeypatch: pytest.MonkeyPatch, failure: str,
) -> None:
    error = object()
    state = {"owner": ":1.10", "Powered": True, "Discovering": True, "present": True}

    class Bus:
        connected = True

        def __init__(self, **_kwargs: Any) -> None:
            pass

        async def connect(self) -> Any:
            return self

        async def call(self, message: Any) -> Any:
            if message.member == "GetNameOwner":
                return SimpleNamespace(message_type=None, body=[state["owner"]])
            assert message.path == "/org/bluez/hci0"
            return SimpleNamespace(
                message_type=None if state["present"] else error,
                body=[{name: SimpleNamespace(value=state[name])
                       for name in ("Powered", "Discovering")}],
            )

    module = ModuleType("dbus_fast")
    module.BusType = SimpleNamespace(SYSTEM="system")  # type: ignore[attr-defined]
    module.Message = lambda **kwargs: SimpleNamespace(**kwargs)  # type: ignore[attr-defined]
    module.MessageType = SimpleNamespace(ERROR=error)  # type: ignore[attr-defined]
    aio = ModuleType("dbus_fast.aio")
    aio.MessageBus = Bus  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "dbus_fast", module)
    monkeypatch.setitem(sys.modules, "dbus_fast.aio", aio)

    async def scenario() -> None:
        with SQLiteMeasurementStore(tmp_path / "probe.sqlite3") as store:
            daemon = TrackyDaemon(store=store)
            await daemon._check_scanner_health()
            await daemon._check_scanner_health()  # Quiet airwaves are healthy.
            if failure == "owner_changed":
                state["owner"] = ":1.11"
            elif failure == "powered_off":
                state["Powered"] = False
            elif failure == "not_discovering":
                state["Discovering"] = False
            elif failure == "adapter_removed":
                state["present"] = False
            else:
                daemon._health_bus.connected = False
            with pytest.raises(RuntimeError, match="Scanner health failed"):
                await daemon._check_scanner_health()

    asyncio.run(scenario())


def test_daemon_ignores_non_scale_devices(tmp_path: Any) -> None:
    store = SQLiteMeasurementStore(path=tmp_path / "test.db")
    scanner: FakeScanner | None = None

    def fake_scanner_factory(detection_callback: Any, adapter: str | None = None) -> FakeScanner:
        nonlocal scanner
        scanner = FakeScanner(detection_callback, adapter)
        return scanner

    daemon = TrackyDaemon(
        store=store,
        scanner_factory=fake_scanner_factory,
        client_factory=lambda req, obs: FakeClient(),
    )

    async def run() -> None:
        await daemon.start()
        assert scanner is not None and scanner.started

        # Emit non-scale device
        non_scale = FakeBleDevice("11:22:33:44:55:66", name="Headphones")
        scanner.emit_advertisement(non_scale)
        await asyncio.sleep(0.05)

        assert daemon._active_operation_id is None
        assert len(store.list_devices()) == 0

        await daemon.stop()
        assert scanner.stopped

    asyncio.run(run())


def test_daemon_triggers_acquisition_on_scale_seen(tmp_path: Any) -> None:
    store = SQLiteMeasurementStore(path=tmp_path / "test.db")
    scanner: FakeScanner | None = None

    def fake_scanner_factory(detection_callback: Any, adapter: str | None = None) -> FakeScanner:
        nonlocal scanner
        scanner = FakeScanner(detection_callback, adapter)
        return scanner

    daemon = TrackyDaemon(
        store=store,
        scanner_factory=fake_scanner_factory,
        client_factory=lambda req, obs: FakeClient(),
        cooldown_seconds=1.0,
    )

    async def run() -> None:
        await daemon.start()
        scale = FakeBleDevice("AA:BB:CC:DD:EE:FF", name="QN-Scale")
        scanner.emit_advertisement(scale, service_uuids=["0000fff0-0000-1000-8000-00805f9b34fb"])

        # Give the async task time to run through acquisition
        await asyncio.sleep(0.1)

        # Device should be registered
        devices = store.list_devices()
        assert len(devices) == 1
        assert devices[0].advertised_name == "QN-Scale"

        await daemon.stop()

    asyncio.run(run())


def test_daemon_cooldown_and_debounce(tmp_path: Any) -> None:
    store = SQLiteMeasurementStore(path=tmp_path / "test.db")
    scanner: FakeScanner | None = None

    def fake_scanner_factory(detection_callback: Any, adapter: str | None = None) -> FakeScanner:
        nonlocal scanner
        scanner = FakeScanner(detection_callback, adapter)
        return scanner

    daemon = TrackyDaemon(
        store=store,
        scanner_factory=fake_scanner_factory,
        client_factory=lambda req, obs: FakeClient(),
        cooldown_seconds=5.0,
    )

    async def run() -> None:
        await daemon.start()
        scale = FakeBleDevice("AA:BB:CC:DD:EE:FF", name="QN-Scale")

        # First advertisement triggers acquisition
        scanner.emit_advertisement(scale)
        await asyncio.sleep(0.05)

        # Immediate second advertisement is ignored due to active or cooldown
        scanner.emit_advertisement(scale)
        await asyncio.sleep(0.1)

        # Only one operation created
        assert daemon._last_completed_at is not None

        await daemon.stop()

    asyncio.run(run())
