"""Decode the fixture-backed CS10B protocol frames.

Only fields supported by the sanitized capture are turned into named values.
Unknown bytes remain available through ``raw``/``raw_hex`` and opaque payload
properties; in particular, the five values in a resistance-bearing frame are
not assigned an ohm unit or a body-composition meaning here.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TypeAlias

from .checksum import has_valid_checksum
from .frames import CHECKSUM_BEARING_OPCODES, Opcode

# Captured interpretations are tagged with these values when persisted. A
# version change means the same immutable bytes may receive a new decoding;
# it is scientific provenance, not a package compatibility promise.
DECODER_ID = "tracky-cs10b-capture-layout"
DECODER_VERSION = 1


class FrameDecodeError(ValueError):
    """Raised when a known opcode is too short to decode safely."""


FrameInput: TypeAlias = bytes | bytearray | memoryview | str

_OPCODE_NAMES = {
    int(Opcode.MEASUREMENT): "measurement_0x10",
    int(Opcode.INFO): "info_0x12",
    int(Opcode.UNIT_CONFIG): "unit_config_0x13",
    int(Opcode.STATUS_14): "status_0x14",
    int(Opcode.TIME_PUSH): "time_push",
    int(Opcode.TIME_SYNC): "time_sync_0x20",
    int(Opcode.STATUS_21): "status_0x21",
    int(Opcode.OVER): "over_0x1f",
    int(Opcode.A0): "a0",
}


@dataclass(slots=True)
class DecodedFrame:
    """Common representation shared by known and unknown frames."""

    raw: bytes
    opcode: int
    checksum_valid: bool | None

    @property
    def raw_bytes(self) -> bytes:
        """Alias that makes the byte-preserving boundary explicit."""

        return self.raw

    @property
    def raw_hex(self) -> str:
        return self.raw.hex()

    @property
    def frame_hex(self) -> str:
        """Fixture-friendly alias for :attr:`raw_hex`."""

        return self.raw_hex

    @property
    def checksum_byte(self) -> int | None:
        return self.raw[-1] if self.raw else None

    @property
    def checksum(self) -> int | None:
        return self.checksum_byte

    @property
    def is_checksum_bearing(self) -> bool:
        return self.opcode in {int(value) for value in CHECKSUM_BEARING_OPCODES}

    @property
    def is_checksum_valid(self) -> bool | None:
        return self.checksum_valid

    @property
    def payload(self) -> bytes:
        """Bytes after the opcode, excluding a known checksum trailer."""

        if self.checksum_valid is None:
            return self.raw[1:]
        return self.raw[1:-1]

    @property
    def opcode_name(self) -> str:
        return _OPCODE_NAMES.get(self.opcode, f"unknown_0x{self.opcode:02x}")


@dataclass(slots=True)
class InfoFrame(DecodedFrame):
    """Observed ``0x12`` scale-info notification."""

    protocol_type: int
    info_payload: bytes
    length_field: int

    @property
    def data(self) -> bytes:
        return self.info_payload


@dataclass(slots=True)
class StatusFrame(DecodedFrame):
    """Observed ``0x14`` or ``0x21`` response with opaque status bytes."""

    protocol_type: int
    status_byte: int
    status_payload: bytes
    length_field: int

    @property
    def status(self) -> int:
        """Compatibility alias; the status byte's exact meaning is unproven."""

        return self.status_byte

    @property
    def data(self) -> bytes:
        return self.status_payload


@dataclass(slots=True)
class MeasurementFrame(DecodedFrame):
    """Observed 18-byte ``0x10`` live measurement notification."""

    protocol_type: int
    weight_raw_u16_be: int
    weight_kg: float
    weight_formula: str
    weight_provenance: str
    b5: int
    b6: int
    resistance_raw: list[int]
    resistance_layout: str | None
    weight_unit: str = "kg"
    weight_scale_divisor: int = 100
    resistance_unit: str | None = None
    resistance_provenance: str = (
        "Opaque five-value block; field boundaries are observed, but units, "
        "frequencies, and segment meaning are unproven."
    )

    @property
    def weight_raw(self) -> int:
        return self.weight_raw_u16_be

    @property
    def has_resistance(self) -> bool:
        return bool(self.resistance_raw)

    @property
    def resistance_values(self) -> list[int]:
        """Alias preserving the intentionally unitless raw values."""

        return self.resistance_raw


@dataclass(slots=True, frozen=True)
class SegmentalBioimpedance:
    """Reassembled 10-channel dual-frequency bioimpedance burst.

    Channels represent raw uint16 values across 5 body segments at 20 kHz and
    100 kHz. Electrical scaling is 0.1 Ohms per raw count.
    """

    lf20k: int
    lf100k: int
    rf20k: int
    rf100k: int
    lh20k: int
    lh100k: int
    rh20k: int
    rh100k: int
    t20k: int
    t100k: int
    weight_kg: float = 0.0

    @property
    def raw_channels(self) -> list[int]:
        return [
            self.lf20k,
            self.lf100k,
            self.rf20k,
            self.rf100k,
            self.lh20k,
            self.lh100k,
            self.rh20k,
            self.rh100k,
            self.t20k,
            self.t100k,
        ]

    @property
    def ohms(self) -> dict[str, float]:
        """Convert raw counts to Ohms (0.1 Ohm per count)."""
        return {
            "lf20k": round(self.lf20k * 0.1, 1),
            "lf100k": round(self.lf100k * 0.1, 1),
            "rf20k": round(self.rf20k * 0.1, 1),
            "rf100k": round(self.rf100k * 0.1, 1),
            "lh20k": round(self.lh20k * 0.1, 1),
            "lh100k": round(self.lh100k * 0.1, 1),
            "rh20k": round(self.rh20k * 0.1, 1),
            "rh100k": round(self.rh100k * 0.1, 1),
            "t20k": round(self.t20k * 0.1, 1),
            "t100k": round(self.t100k * 0.1, 1),
        }

    @property
    def fluid_ratio(self) -> float | None:
        """Dual-frequency fluid ratio: sum(Z_100k) / sum(Z_20k)."""
        sum_20k = self.lf20k + self.rf20k + self.lh20k + self.rh20k + self.t20k
        sum_100k = self.lf100k + self.rf100k + self.lh100k + self.rh100k + self.t100k
        if sum_20k == 0:
            return None
        return round(sum_100k / sum_20k, 4)


def reassemble_burst(
    packet1: MeasurementFrame,
    packet2: MeasurementFrame,
) -> SegmentalBioimpedance:
    """Combine Packet 1 (b6=0x21) and Packet 2 (b6=0x22) into a 10-channel burst."""
    if packet1.b5 != 0x01 or packet1.b6 != 0x21:
        raise ValueError(
            f"packet1 must have b5=0x01 and b6=0x21, got "
            f"b5=0x{packet1.b5:02x}, b6=0x{packet1.b6:02x}"
        )
    if packet2.b5 != 0x01 or packet2.b6 != 0x22:
        raise ValueError(
            f"packet2 must have b5=0x01 and b6=0x22, got "
            f"b5=0x{packet2.b5:02x}, b6=0x{packet2.b6:02x}"
        )
    if len(packet1.resistance_raw) != 5 or len(packet2.resistance_raw) != 5:
        raise ValueError("both packet1 and packet2 must contain 5 raw resistance values")

    p1 = packet1.resistance_raw
    p2 = packet2.resistance_raw

    return SegmentalBioimpedance(
        lf20k=p1[0],
        lf100k=p1[1],
        rf20k=p1[2],
        rf100k=p1[3],
        lh20k=p1[4],
        lh100k=p2[0],
        rh20k=p2[1],
        rh100k=p2[2],
        t20k=p2[3],
        t100k=p2[4],
        weight_kg=packet2.weight_kg if packet2.weight_kg > 0 else packet1.weight_kg,
    )


@dataclass(slots=True)
class CommandFrame(DecodedFrame):
    """Typed view of one of the known host-write command forms."""

    command_name: str
    command_payload: bytes
    protocol_type: int | None
    unit_byte: int | None
    qn_epoch_seconds: int | None

    @property
    def name(self) -> str:
        return self.command_name

    @property
    def data(self) -> bytes:
        return self.command_payload


@dataclass(slots=True)
class UnknownFrame(DecodedFrame):
    """A raw frame whose opcode is not yet described by the protocol note."""


def _coerce_frame(frame: FrameInput) -> bytes:
    if isinstance(frame, str):
        try:
            value = bytes.fromhex(frame)
        except ValueError as exc:
            raise FrameDecodeError("frame hex is not valid hexadecimal") from exc
    elif isinstance(frame, (bytes, bytearray, memoryview)):
        value = bytes(frame)
    else:
        raise TypeError("frame must be bytes-like or a hexadecimal string")
    if not value:
        raise FrameDecodeError("cannot decode an empty frame")
    return value


def _require_length(raw: bytes, minimum: int, name: str) -> None:
    if len(raw) < minimum:
        raise FrameDecodeError(f"{name} frame is too short: expected at least {minimum} bytes")


def _checksum_status(raw: bytes, opcode: int) -> bool | None:
    if opcode in {int(value) for value in CHECKSUM_BEARING_OPCODES}:
        return has_valid_checksum(raw)
    # The five-byte 0x02 time_push command is explicitly not checksum-bearing.
    return None


def decode_frame(frame: FrameInput) -> DecodedFrame:
    """Decode one raw notification or known host-write frame.

    Checksum mismatches are retained as ``checksum_valid=False`` rather than
    discarded, allowing callers to log the raw packet.  The session state
    machine declines to advance on such a known checksum-bearing frame.
    """

    raw = _coerce_frame(frame)
    opcode = raw[0]
    checksum_valid = _checksum_status(raw, opcode)

    if opcode == Opcode.MEASUREMENT:
        if len(raw) != 18:
            raise FrameDecodeError(
                f"0x10 measurement frame must be 18 bytes, got {len(raw)}"
            )
        b5 = raw[5]
        b6 = raw[6]
        has_block = b5 == 0x01 and b6 in (0x21, 0x22)
        resistance_raw = (
            [int.from_bytes(raw[index : index + 2], "big") for index in range(7, 17, 2)]
            if has_block
            else []
        )
        if has_block:
            resistance_layout = (
                "packet 1/2: lf20k, lf100k, rf20k, rf100k, lh20k"
                if b6 == 0x21
                else "packet 2/2: lh100k, rh20k, rh100k, t20k, t100k"
            )
        else:
            resistance_layout = None
        return MeasurementFrame(
            raw=raw,
            opcode=opcode,
            checksum_valid=checksum_valid,
            protocol_type=raw[2],
            weight_raw_u16_be=int.from_bytes(raw[3:5], "big"),
            weight_kg=int.from_bytes(raw[3:5], "big") / 100,
            weight_formula="int.from_bytes(frame[3:5], 'big') / 100",
            weight_provenance=(
                "High-confidence CS10B capture layout: big-endian uint16 at "
                "bytes 3..4 divided by 100 gives kilograms."
            ),
            b5=b5,
            b6=b6,
            resistance_raw=resistance_raw,
            resistance_layout=resistance_layout,
        )

    if opcode == Opcode.INFO:
        _require_length(raw, 4, "0x12 info")
        return InfoFrame(
            raw=raw,
            opcode=opcode,
            checksum_valid=checksum_valid,
            protocol_type=raw[2],
            info_payload=raw[3:-1],
            length_field=raw[1],
        )

    if opcode in (Opcode.STATUS_14, Opcode.STATUS_21):
        _require_length(raw, 4, f"0x{opcode:02x} status")
        return StatusFrame(
            raw=raw,
            opcode=opcode,
            checksum_valid=checksum_valid,
            protocol_type=raw[2],
            status_byte=raw[3],
            status_payload=raw[3:-1],
            length_field=raw[1],
        )

    if opcode == Opcode.UNIT_CONFIG:
        _require_length(raw, 4, "0x13 unit config")
        return CommandFrame(
            raw=raw,
            opcode=opcode,
            checksum_valid=checksum_valid,
            command_name="unit_config_0x13",
            command_payload=raw[1:-1],
            protocol_type=raw[2],
            unit_byte=raw[3],
            qn_epoch_seconds=None,
        )

    if opcode == Opcode.TIME_PUSH:
        if len(raw) != 5:
            raise FrameDecodeError(f"0x02 time_push command must be 5 bytes, got {len(raw)}")
        return CommandFrame(
            raw=raw,
            opcode=opcode,
            checksum_valid=None,
            command_name="time_push",
            command_payload=raw[1:],
            protocol_type=None,
            unit_byte=None,
            qn_epoch_seconds=int.from_bytes(raw[1:5], "little"),
        )

    if opcode == Opcode.TIME_SYNC:
        _require_length(raw, 8, "0x20 time sync")
        return CommandFrame(
            raw=raw,
            opcode=opcode,
            checksum_valid=checksum_valid,
            command_name="time_sync_0x20",
            command_payload=raw[1:-1],
            protocol_type=raw[2],
            unit_byte=None,
            qn_epoch_seconds=int.from_bytes(raw[3:7], "little"),
        )

    if opcode == Opcode.OVER:
        _require_length(raw, 5, "0x1F over")
        return CommandFrame(
            raw=raw,
            opcode=opcode,
            checksum_valid=checksum_valid,
            command_name="over_0x1f",
            command_payload=raw[1:-1],
            protocol_type=raw[2] if len(raw) > 2 else None,
            unit_byte=None,
            qn_epoch_seconds=None,
        )

    if opcode == Opcode.A0:
        _require_length(raw, 2, "0xA0 command")
        return CommandFrame(
            raw=raw,
            opcode=opcode,
            checksum_valid=checksum_valid,
            command_name="a0",
            command_payload=raw[1:-1],
            protocol_type=None,
            unit_byte=None,
            qn_epoch_seconds=None,
        )

    return UnknownFrame(raw=raw, opcode=opcode, checksum_valid=None)


__all__ = [
    "CommandFrame",
    "DECODER_ID",
    "DECODER_VERSION",
    "DecodedFrame",
    "FrameDecodeError",
    "FrameInput",
    "InfoFrame",
    "MeasurementFrame",
    "SegmentalBioimpedance",
    "StatusFrame",
    "UnknownFrame",
    "decode_frame",
    "reassemble_burst",
]
