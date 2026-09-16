"""In-memory MCP 2026-07-28 contract tests."""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from mcp import Client

from tracky_mcp.acquisition import AcquisitionService, DeviceService, WeighInRequest
from tracky_mcp.ble import FFF1_NOTIFY_UUID, WireEvent, WireObserver
from tracky_mcp.mcp import create_server
from tracky_mcp.protocol import DecodedFrame, decode_frame
from tracky_mcp.storage import SQLiteMeasurementStore

NOW = datetime(2026, 8, 11, 18, 0, tzinfo=timezone.utc)
ADDRESS = "AA:BB:CC:DD:EE:FF"
MEASUREMENT = bytes.fromhex("1012201ed2012111c51077088e07f70085ca")


@dataclass
class FakeDevice:
    address: str
    name: str
    metadata: dict[str, Any]


async def fake_scanner(_timeout: float) -> list[FakeDevice]:
    return [
        FakeDevice(
            address=ADDRESS,
            name="QN-Scale",
            metadata={"service_uuids": ["0000ffe0-0000-1000-8000-00805f9b34fb"]},
        )
    ]


class FiniteClient:
    def __init__(self, observer: WireObserver) -> None:
        self.observer = observer

    async def events(self) -> AsyncIterator[DecodedFrame]:
        self.observer(WireEvent(NOW, "rx", FFF1_NOTIFY_UUID, MEASUREMENT))
        yield decode_frame(MEASUREMENT)

    async def disconnect(self) -> None:
        return None


def finite_client_factory(
    _request: WeighInRequest,
    observer: WireObserver,
) -> FiniteClient:
    return FiniteClient(observer)


def test_modern_mcp_surface_drives_discovery_acquisition_and_resources(
    tmp_path: Path,
) -> None:
    async def scenario() -> None:
        with SQLiteMeasurementStore(tmp_path / "tracky.sqlite3") as store:
            devices = DeviceService(store, scanner=fake_scanner, clock=lambda: NOW)
            acquisitions = AcquisitionService(
                store,
                client_factory=finite_client_factory,
                clock=lambda: NOW,
            )
            server = create_server(store, devices=devices, acquisitions=acquisitions)

            async with Client(server, mode="2026-07-28") as client:
                assert client.protocol_version == "2026-07-28"
                listed_tools = await client.list_tools()
                tool_names = {tool.name for tool in listed_tools.tools}
                assert tool_names == {
                    "discover_devices",
                    "list_devices",
                    "configure_device",
                    "start_weigh_in",
                    "get_operation",
                    "stop_weigh_in",
                    "cancel_weigh_in",
                    "list_sessions",
                }
                assert not any("delete" in name or "purge" in name for name in tool_names)

                discover = await client.call_tool(
                    "discover_devices", {"timeout_seconds": 1.0}
                )
                assert discover.is_error is False
                assert discover.structured_content is not None
                [device] = discover.structured_content["devices"]
                assert device["advertised_name"] == "QN-Scale"
                assert device["id"]
                assert device["address_hash"]
                assert ADDRESS not in json.dumps(discover.structured_content)

                start = await client.call_tool(
                    "start_weigh_in",
                    {
                        "device_id": device["id"],
                        "condition": "fixture-replay",
                    },
                )
                assert start.is_error is False
                assert start.structured_content is not None
                operation_id = start.structured_content["id"]
                await acquisitions.wait(operation_id)

                status = await client.call_tool(
                    "get_operation", {"operation_id": operation_id}
                )
                assert status.structured_content is not None
                assert status.structured_content["status"] == "complete"
                session_id = status.structured_content["session_id"]
                assert session_id is not None

                sessions = await client.call_tool("list_sessions", {"limit": 10})
                assert sessions.structured_content is not None
                [summary] = sessions.structured_content["sessions"]
                assert summary["id"] == session_id
                assert summary["selected_frame_sequence"] is None
                assert summary["started_at_display"].endswith("+05:30")
                assert summary["frame_count"] == 1
                assert summary["measurement_frame_count"] == 1

                frames = await client.read_resource(
                    f"tracky://sessions/{session_id}/frames"
                )
                payload = json.loads(frames.contents[0].text)
                assert "do not establish a stable" in payload["interpretation_warning"]
                assert payload["selected_frame_sequence"] is None
                assert payload["frames"][0]["raw_hex"] == MEASUREMENT.hex()
                measurement = payload["frames"][0]["measurement"]
                assert measurement["weight_kg"] == "78.9"
                assert measurement["weight_unit"] == "kg"
                assert measurement["resistance_raw"] == [4549, 4215, 2190, 2039, 133]
                assert measurement["resistance_unit"] is None

                device_resource = await client.read_resource(
                    f"tracky://devices/{device['id']}"
                )
                assert ADDRESS not in device_resource.contents[0].text

    asyncio.run(scenario())


def test_tool_schemas_are_bounded_structured_and_safety_annotated(tmp_path: Path) -> None:
    async def scenario() -> None:
        with SQLiteMeasurementStore(tmp_path / "tracky.sqlite3") as store:
            server = create_server(
                store,
                devices=DeviceService(store, scanner=fake_scanner, clock=lambda: NOW),
                acquisitions=AcquisitionService(
                    store,
                    client_factory=finite_client_factory,
                    clock=lambda: NOW,
                ),
            )
            async with Client(server, mode="2026-07-28") as client:
                tools = {tool.name: tool for tool in (await client.list_tools()).tools}

                assert tools["discover_devices"].output_schema is not None
                assert tools["start_weigh_in"].output_schema is not None
                assert tools["list_sessions"].output_schema is not None
                assert tools["list_sessions"].input_schema["properties"]["limit"][
                    "maximum"
                ] == 100
                assert tools["cancel_weigh_in"].annotations is not None
                assert tools["cancel_weigh_in"].annotations.destructive_hint is True
                assert tools["get_operation"].annotations is not None
                assert tools["get_operation"].annotations.read_only_hint is True

    asyncio.run(scenario())
