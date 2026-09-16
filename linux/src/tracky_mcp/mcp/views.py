"""Structured, agent-safe MCP views over Tracky's domain records."""

from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field

from tracky_mcp.storage import (
    AcquisitionOperation,
    KnownDevice,
    MeasurementSession,
    StoredFrame,
)


class DeviceView(BaseModel):
    id: str = Field(description="Opaque local device ID; never a BLE address")
    advertised_name: str | None
    alias: str | None
    model_label: str | None
    address_hash: str = Field(description="Non-connecting, truncated address hash")
    first_seen_at: datetime
    last_seen_at: datetime
    advertised_service_uuids: list[str]
    metadata: dict[str, Any]
    resource_uri: str

    @classmethod
    def from_record(cls, device: KnownDevice) -> DeviceView:
        return cls(
            id=device.id,
            advertised_name=device.advertised_name,
            alias=device.alias,
            model_label=device.model_label,
            address_hash=device.address_hash,
            first_seen_at=device.first_seen_at,
            last_seen_at=device.last_seen_at,
            advertised_service_uuids=list(device.advertised_service_uuids),
            metadata=device.metadata,
            resource_uri=f"tracky://devices/{device.id}",
        )


class DeviceList(BaseModel):
    devices: list[DeviceView]


class OperationView(BaseModel):
    id: str
    kind: str
    status: Literal["pending", "running", "complete", "failed", "cancelled"]
    created_at: datetime
    started_at: datetime | None
    finished_at: datetime | None
    session_id: str | None
    error_code: str | None
    error_message: str | None
    metadata: dict[str, Any]
    resource_uri: str
    session_resource_uri: str | None

    @classmethod
    def from_record(cls, operation: AcquisitionOperation) -> OperationView:
        session_uri = (
            None
            if operation.session_id is None
            else f"tracky://sessions/{operation.session_id}"
        )
        return cls(
            id=operation.id,
            kind=operation.kind,
            status=operation.status.value,
            created_at=operation.created_at,
            started_at=operation.started_at,
            finished_at=operation.finished_at,
            session_id=operation.session_id,
            error_code=operation.error_code,
            error_message=operation.error_message,
            metadata=operation.metadata,
            resource_uri=f"tracky://operations/{operation.id}",
            session_resource_uri=session_uri,
        )


class MeasurementView(BaseModel):
    protocol_type: int
    weight_raw_u16_be: int
    weight_scale_divisor: int
    weight_unit: str
    weight_kg: str = Field(description="Exact decimal derived from raw/divisor")
    weight_formula: str
    weight_provenance: str
    b5: int
    b6: int
    resistance_raw: list[int]
    resistance_layout: str | None
    resistance_unit: str | None
    resistance_provenance: str


class FrameView(BaseModel):
    sequence: int
    observed_at: datetime | None
    elapsed_ms: int | None
    direction: Literal["rx", "tx"]
    characteristic_uuid: str | None
    raw_hex: str
    opcode: int | None
    frame_kind: str
    checksum_valid: bool | None
    decoder_id: str
    decoder_version: int
    decoded_fields: dict[str, Any]
    decode_error: str | None
    measurement: MeasurementView | None

    @classmethod
    def from_record(cls, frame: StoredFrame) -> FrameView:
        measurement = frame.measurement
        measurement_view = None
        if measurement is not None:
            measurement_view = MeasurementView(
                protocol_type=measurement.protocol_type,
                weight_raw_u16_be=measurement.weight_raw_u16_be,
                weight_scale_divisor=measurement.weight_scale_divisor,
                weight_unit=measurement.weight_unit,
                weight_kg=format(measurement.weight_kg, "f"),
                weight_formula=measurement.weight_formula,
                weight_provenance=measurement.weight_provenance,
                b5=measurement.b5,
                b6=measurement.b6,
                resistance_raw=list(measurement.resistance_raw),
                resistance_layout=measurement.resistance_layout,
                resistance_unit=measurement.resistance_unit,
                resistance_provenance=measurement.resistance_provenance,
            )
        return cls(
            sequence=frame.sequence,
            observed_at=frame.observed_at,
            elapsed_ms=frame.elapsed_ms,
            direction=frame.direction,
            characteristic_uuid=frame.characteristic_uuid,
            raw_hex=frame.raw_hex,
            opcode=frame.opcode,
            frame_kind=frame.frame_kind,
            checksum_valid=frame.checksum_valid,
            decoder_id=frame.decoder_id,
            decoder_version=frame.decoder_version,
            decoded_fields=frame.decoded_fields,
            decode_error=frame.decode_error,
            measurement=measurement_view,
        )


class SessionSummary(BaseModel):
    id: str
    status: Literal["open", "complete", "aborted"]
    started_at: datetime
    ended_at: datetime | None
    display_timezone: str
    started_at_display: datetime
    ended_at_display: datetime | None
    device_alias: str | None
    model_label: str | None
    address_hash: str | None
    requested_unit: str | None
    condition: str | None
    capture_id: str | None
    selected_frame_sequence: int | None = Field(
        description="Null unless a separately justified final-sample policy selected a frame"
    )
    frame_count: int
    measurement_frame_count: int
    resource_uri: str
    frames_resource_uri: str

    @classmethod
    def from_record(cls, session: MeasurementSession) -> SessionSummary:
        return cls(
            id=session.id,
            status=session.status.value,
            started_at=session.started_at,
            ended_at=session.ended_at,
            display_timezone=session.display_timezone,
            started_at_display=session.started_at_local,
            ended_at_display=session.ended_at_local,
            device_alias=session.device_alias,
            model_label=session.model_label,
            address_hash=session.address_hash,
            requested_unit=session.requested_unit,
            condition=session.condition,
            capture_id=session.capture_id,
            selected_frame_sequence=session.selected_frame_sequence,
            frame_count=len(session.frames),
            measurement_frame_count=len(session.measurements),
            resource_uri=f"tracky://sessions/{session.id}",
            frames_resource_uri=f"tracky://sessions/{session.id}/frames",
        )


class SessionList(BaseModel):
    sessions: list[SessionSummary]


class SessionResource(BaseModel):
    summary: SessionSummary
    metadata: dict[str, Any]

    @classmethod
    def from_record(cls, session: MeasurementSession) -> SessionResource:
        return cls(summary=SessionSummary.from_record(session), metadata=session.metadata)


class FramesResource(BaseModel):
    session_id: str
    selected_frame_sequence: int | None
    interpretation_warning: str = (
        "Frames are an ordered evidence stream; list order and repetition do not establish "
        "a stable or final product measurement."
    )
    frames: list[FrameView]

    @classmethod
    def from_record(cls, session: MeasurementSession) -> FramesResource:
        return cls(
            session_id=session.id,
            selected_frame_sequence=session.selected_frame_sequence,
            frames=[FrameView.from_record(frame) for frame in session.frames],
        )


__all__ = [
    "DeviceList",
    "DeviceView",
    "FrameView",
    "FramesResource",
    "MeasurementView",
    "OperationView",
    "SessionList",
    "SessionResource",
    "SessionSummary",
]
