"""MCP 2026-07-28 adapter over Tracky's domain services."""

from __future__ import annotations

from typing import Annotated, Literal

from mcp.server import MCPServer
from mcp.types import ToolAnnotations
from pydantic import Field

from tracky_mcp import __version__
from tracky_mcp.acquisition import AcquisitionService, DeviceService
from tracky_mcp.storage import SQLiteMeasurementStore

from .views import (
    DeviceList,
    DeviceView,
    FramesResource,
    OperationView,
    SessionList,
    SessionResource,
    SessionSummary,
)

_READ_ONLY = ToolAnnotations(
    readOnlyHint=True,
    destructiveHint=False,
    idempotentHint=True,
    openWorldHint=False,
)
_LOCAL_MUTATION = ToolAnnotations(
    readOnlyHint=False,
    destructiveHint=False,
    idempotentHint=True,
    openWorldHint=False,
)
_BLE_ACTION = ToolAnnotations(
    readOnlyHint=False,
    destructiveHint=False,
    idempotentHint=False,
    openWorldHint=True,
)
_CANCEL_ACTION = ToolAnnotations(
    readOnlyHint=False,
    destructiveHint=True,
    idempotentHint=True,
    openWorldHint=False,
)

Limit = Annotated[int, Field(ge=1, le=100)]
ScanTimeout = Annotated[float, Field(gt=0, le=30)]
AcquisitionTimeout = Annotated[float, Field(gt=0, le=300)]


def create_server(
    store: SQLiteMeasurementStore,
    *,
    devices: DeviceService | None = None,
    acquisitions: AcquisitionService | None = None,
) -> MCPServer:
    """Build an injectable modern MCP server without owning storage lifetime."""

    device_service = devices or DeviceService(store)
    acquisition_service = acquisitions or AcquisitionService(store)
    server = MCPServer(
        "tracky-mcp",
        title="Tracky local measurement server",
        description="Local CS10B BLE acquisition, raw evidence, and measurement history.",
        instructions=(
            "Treat raw frames as evidence and decoded values as interpretations. "
            "A completed acquisition only means recording stopped; selected_frame_sequence "
            "remains null until a scientifically justified final-sample policy exists. "
            "Never assign units or segment/frequency labels to resistance_raw without provenance."
        ),
        version=__version__,
    )

    @server.tool(annotations=ToolAnnotations(**_BLE_ACTION.model_dump(by_alias=True)))
    async def discover_devices(timeout_seconds: ScanTimeout = 5.0) -> DeviceList:
        """Scan locally for supported scales and return opaque device IDs."""

        discovered = await device_service.discover(timeout=timeout_seconds)
        return DeviceList(devices=[DeviceView.from_record(device) for device in discovered])

    @server.tool(annotations=ToolAnnotations(**_READ_ONLY.model_dump(by_alias=True)))
    async def list_devices(limit: Limit = 100) -> DeviceList:
        """List previously discovered scales without exposing BLE addresses."""

        return DeviceList(
            devices=[
                DeviceView.from_record(device)
                for device in device_service.list_known(limit=limit)
            ]
        )

    @server.tool(annotations=ToolAnnotations(**_LOCAL_MUTATION.model_dump(by_alias=True)))
    async def configure_device(
        device_id: str,
        alias: str | None = None,
        model_label: str | None = None,
    ) -> DeviceView:
        """Set local labels for an opaque device ID; this does not contact the scale."""

        return DeviceView.from_record(
            device_service.configure(
                device_id,
                alias=alias,
                model_label=model_label,
            )
        )

    @server.tool(annotations=ToolAnnotations(**_BLE_ACTION.model_dump(by_alias=True)))
    async def start_weigh_in(
        device_id: str,
        unit: Literal["kg", "lb"] = "kg",
        condition: str | None = None,
        timeout_seconds: AcquisitionTimeout = 60.0,
    ) -> OperationView:
        """Start recording a weigh-in and return a durable, pollable operation handle."""

        request = device_service.weigh_in_request(
            device_id,
            unit=unit,
            condition=condition,
            timeout_seconds=timeout_seconds,
            metadata={"source": "mcp"},
        )
        operation_id = acquisition_service.start(request)
        return OperationView.from_record(acquisition_service.get(operation_id))

    @server.tool(annotations=ToolAnnotations(**_READ_ONLY.model_dump(by_alias=True)))
    async def get_operation(operation_id: str) -> OperationView:
        """Poll a persisted acquisition operation without relying on connection state."""

        return OperationView.from_record(acquisition_service.get(operation_id))

    @server.tool(annotations=ToolAnnotations(**_LOCAL_MUTATION.model_dump(by_alias=True)))
    async def stop_weigh_in(operation_id: str) -> OperationView:
        """Stop recording successfully; this does not select or assert a stable frame."""

        return OperationView.from_record(await acquisition_service.stop(operation_id))

    @server.tool(annotations=ToolAnnotations(**_CANCEL_ACTION.model_dump(by_alias=True)))
    async def cancel_weigh_in(operation_id: str) -> OperationView:
        """Abort active acquisition while retaining all recorded evidence."""

        return OperationView.from_record(await acquisition_service.cancel(operation_id))

    @server.tool(annotations=ToolAnnotations(**_READ_ONLY.model_dump(by_alias=True)))
    async def list_sessions(limit: Limit = 20) -> SessionList:
        """List recent session summaries, newest first, without loading raw frames."""

        sessions = store.list_sessions(limit=limit)
        return SessionList(
            sessions=[SessionSummary.from_record(session) for session in sessions]
        )

    @server.resource(
        "tracky://devices/{device_id}",
        mime_type="application/json",
        description="Agent-safe local scale identity; excludes the BLE connection target.",
    )
    async def device_resource(device_id: str) -> str:
        return DeviceView.from_record(store.get_device(device_id)).model_dump_json()

    @server.resource(
        "tracky://operations/{operation_id}",
        mime_type="application/json",
        description="Persisted acquisition-operation state.",
    )
    async def operation_resource(operation_id: str) -> str:
        return OperationView.from_record(store.get_operation(operation_id)).model_dump_json()

    @server.resource(
        "tracky://sessions/{session_id}",
        mime_type="application/json",
        description="Measurement-session metadata and evidence counts, excluding frame bodies.",
    )
    async def session_resource(session_id: str) -> str:
        return SessionResource.from_record(store.get_session(session_id)).model_dump_json()

    @server.resource(
        "tracky://sessions/{session_id}/frames",
        mime_type="application/json",
        description="Complete ordered RX/TX evidence stream for a measurement session.",
    )
    async def frames_resource(session_id: str) -> str:
        return FramesResource.from_record(store.get_session(session_id)).model_dump_json()

    return server


__all__ = ["create_server"]
