"""Persisted, explicit-handle acquisition orchestration.

The service bridges an injected BLE event source to storage without deciding
which repeated measurement is "stable". MCP can project these operations later;
this module has no MCP dependency and never treats a connection as state.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Callable, Mapping
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Protocol

from tracky_mcp.ble import ScaleClient, WireEvent, WireObserver
from tracky_mcp.protocol import DecodedFrame
from tracky_mcp.storage import (
    AcquisitionOperation,
    MeasurementSession,
    OperationStatus,
    SessionStatus,
    SQLiteMeasurementStore,
)

Clock = Callable[[], datetime]


class AcquisitionClient(Protocol):
    """Transport seam required by :class:`AcquisitionService`."""

    def events(self) -> AsyncIterator[DecodedFrame]:
        """Yield decoded RX events while the transport is active."""

    async def disconnect(self) -> None:
        """Release the transport, tolerating repeated calls."""


@dataclass(frozen=True, slots=True)
class WeighInRequest:
    """Inputs known before starting a local weigh-in acquisition."""

    device: Any = field(repr=False, compare=False)
    unit: str = "kg"
    display_timezone: str = "Asia/Kolkata"
    device_alias: str | None = None
    model_label: str | None = None
    address_hash: str | None = None
    condition: str | None = None
    timeout_seconds: float = 60.0
    metadata: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if self.timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be greater than zero")


ClientFactory = Callable[[WeighInRequest, WireObserver], AcquisitionClient]


class OperationNotActiveError(RuntimeError):
    """Raised when this process cannot control a nonterminal operation handle."""


class AcquisitionService:
    """Run and persist weigh-ins behind opaque, pollable operation handles.

    ``start`` returns immediately. The caller may poll ``get``, explicitly
    ``stop`` a successful observation window, or ``cancel`` it. Peripheral
    disconnect also completes a session. A safety timeout fails and aborts the
    session while preserving every frame received so far.
    """

    def __init__(
        self,
        store: SQLiteMeasurementStore,
        *,
        client_factory: ClientFactory | None = None,
        clock: Clock | None = None,
        recover_on_start: bool = True,
    ) -> None:
        self.store = store
        self._client_factory = client_factory or self._default_client_factory
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._tasks: dict[str, asyncio.Task[None]] = {}
        self._stop_events: dict[str, asyncio.Event] = {}
        if recover_on_start:
            self.store.recover_interrupted_operations(recovered_at=self._now())

    @staticmethod
    def _default_client_factory(
        request: WeighInRequest,
        observer: WireObserver,
    ) -> AcquisitionClient:
        return ScaleClient(
            request.device,
            unit=request.unit,
            wire_observer=observer,
        )

    def _now(self) -> datetime:
        value = self._clock()
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("acquisition clock must return a timezone-aware datetime")
        return value

    def start(self, request: WeighInRequest) -> str:
        """Create a persisted operation and schedule its acquisition task."""

        try:
            loop = asyncio.get_running_loop()
        except RuntimeError as exc:
            raise RuntimeError("start() requires a running asyncio event loop") from exc

        operation_id = self.store.create_operation(
            created_at=self._now(),
            metadata=request.metadata,
        )
        stop_event = asyncio.Event()
        task = loop.create_task(self._run(operation_id, request, stop_event))
        self._stop_events[operation_id] = stop_event
        self._tasks[operation_id] = task
        task.add_done_callback(
            lambda completed, handle=operation_id: self._task_done(handle, completed)
        )
        return operation_id

    def get(self, operation_id: str) -> AcquisitionOperation:
        """Read status solely from durable storage."""

        return self.store.get_operation(operation_id)

    async def wait(self, operation_id: str) -> AcquisitionOperation:
        """Wait when locally active, otherwise return the persisted status."""

        task = self._tasks.get(operation_id)
        if task is not None:
            await asyncio.shield(task)
        return self.get(operation_id)

    async def stop(self, operation_id: str) -> AcquisitionOperation:
        """End the observation window successfully without selecting a frame."""

        operation = self.get(operation_id)
        if operation.terminal:
            return operation
        stop_event = self._stop_events.get(operation_id)
        task = self._tasks.get(operation_id)
        if stop_event is None or task is None:
            raise OperationNotActiveError(
                f"operation {operation_id} is not controlled by this process"
            )
        stop_event.set()
        await asyncio.shield(task)
        return self.get(operation_id)

    async def cancel(self, operation_id: str) -> AcquisitionOperation:
        """Cancel local work, retaining an aborted session and all evidence."""

        operation = self.get(operation_id)
        if operation.terminal:
            return operation
        task = self._tasks.get(operation_id)
        if task is None:
            raise OperationNotActiveError(
                f"operation {operation_id} is not controlled by this process"
            )
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
        return self.get(operation_id)

    def _task_done(self, operation_id: str, task: asyncio.Task[None]) -> None:
        self._tasks.pop(operation_id, None)
        self._stop_events.pop(operation_id, None)
        # _run translates every outcome into persisted state. Retrieve the
        # exception to prevent an event-loop warning if an invariant bug escapes.
        if not task.cancelled():
            task.exception()

    async def _run(
        self,
        operation_id: str,
        request: WeighInRequest,
        stop_event: asyncio.Event,
    ) -> None:
        session_id: str | None = None
        client: AcquisitionClient | None = None
        try:
            started_at = self._now()
            session_id = self.store.create_session(
                started_at=started_at,
                display_timezone=request.display_timezone,
                device_alias=request.device_alias,
                model_label=request.model_label,
                address_hash=request.address_hash,
                requested_unit=request.unit,
                condition=request.condition,
                metadata=request.metadata,
            )
            self.store.start_operation(
                operation_id,
                session_id=session_id,
                started_at=started_at,
            )

            def record_wire(event: WireEvent) -> None:
                elapsed = max(
                    0,
                    int((event.observed_at - started_at).total_seconds() * 1000),
                )
                self.store.append_frame(
                    session_id,
                    event.data,
                    observed_at=event.observed_at,
                    elapsed_ms=elapsed,
                    direction=event.direction,
                    characteristic_uuid=event.characteristic_uuid,
                )

            client = self._client_factory(request, record_wire)
            async with asyncio.timeout(request.timeout_seconds):
                await self._consume_until_stopped(client, stop_event)

            finished_at = self._now()
            self.store.finish_acquisition(
                operation_id,
                session_id=session_id,
                status=OperationStatus.COMPLETE,
                finished_at=finished_at,
            )
        except asyncio.CancelledError:
            await self._close_failed_session(session_id, operation_id, cancelled=True)
        except TimeoutError:
            await self._close_failed_session(
                session_id,
                operation_id,
                error_code="timeout",
                error_message=(
                    f"Acquisition exceeded the {request.timeout_seconds:g} second safety timeout"
                ),
            )
        except Exception as exc:
            await self._close_failed_session(
                session_id,
                operation_id,
                error_code="acquisition_failed",
                error_message=f"{type(exc).__name__}: {exc}",
            )
        finally:
            if client is not None:
                try:
                    await client.disconnect()
                except Exception:
                    # The primary persisted outcome wins over a teardown race.
                    pass

    async def _consume_until_stopped(
        self,
        client: AcquisitionClient,
        stop_event: asyncio.Event,
    ) -> None:
        iterator = client.events().__aiter__()
        next_event: asyncio.Task[DecodedFrame] | None = None
        stopped: asyncio.Task[bool] | None = None
        try:
            while True:
                next_event = asyncio.create_task(anext(iterator))
                stopped = asyncio.create_task(stop_event.wait())
                done, pending = await asyncio.wait(
                    {next_event, stopped},
                    return_when=asyncio.FIRST_COMPLETED,
                )
                for task in pending:
                    task.cancel()
                await asyncio.gather(*pending, return_exceptions=True)
                if stopped in done and stopped.result():
                    if next_event in done:
                        # Consume a simultaneously delivered event; its wire
                        # callback has already persisted the bytes.
                        try:
                            next_event.result()
                        except StopAsyncIteration:
                            pass
                    return
                try:
                    next_event.result()
                except StopAsyncIteration:
                    return
                next_event = None
                stopped = None
        finally:
            active = [task for task in (next_event, stopped) if task is not None]
            for task in active:
                if not task.done():
                    task.cancel()
            if active:
                await asyncio.gather(*active, return_exceptions=True)
            close = getattr(iterator, "aclose", None)
            if close is not None:
                await close()

    async def _close_failed_session(
        self,
        session_id: str | None,
        operation_id: str,
        *,
        cancelled: bool = False,
        error_code: str = "cancelled",
        error_message: str = "Operation cancelled",
    ) -> None:
        finished_at = self._now()
        operation = self.store.get_operation(operation_id)
        if operation.status not in {OperationStatus.PENDING, OperationStatus.RUNNING}:
            return
        status = OperationStatus.CANCELLED if cancelled else OperationStatus.FAILED
        if session_id is not None and operation.status is OperationStatus.RUNNING:
            self.store.finish_acquisition(
                operation_id,
                session_id=session_id,
                status=status,
                finished_at=finished_at,
                error_code="cancelled" if cancelled else error_code,
                error_message=error_message,
            )
            return
        if session_id is not None:
            session = self.store.get_session(session_id)
            if session.status is SessionStatus.OPEN:
                self.store.abort_session(session_id, ended_at=finished_at)
        if cancelled:
            self.store.cancel_operation(
                operation_id,
                finished_at=finished_at,
                error_message=error_message,
            )
        else:
            self.store.fail_operation(
                operation_id,
                finished_at=finished_at,
                error_code=error_code,
                error_message=error_message,
            )

    def get_session(self, operation_id: str) -> MeasurementSession | None:
        """Resolve an operation's durable session without connection state."""

        operation = self.get(operation_id)
        if operation.session_id is None:
            return None
        return self.store.get_session(operation.session_id)


__all__ = [
    "AcquisitionClient",
    "AcquisitionService",
    "ClientFactory",
    "Clock",
    "OperationNotActiveError",
    "WeighInRequest",
]
