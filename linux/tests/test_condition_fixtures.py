"""Condition fixtures: handle path vs USB-C unplugged (not full handshake write logs)."""

from __future__ import annotations

import json
from pathlib import Path

from tracky_mcp.protocol import MeasurementFrame, decode_frame

ROOT = Path(__file__).parents[1] / "fixtures" / "cs10b"
GROUNDED = ROOT / "20260809T194335Z"
UNPLUGGED = ROOT / "20260809T194641Z"


def _load(dir_path: Path) -> tuple[dict, dict]:
    session = json.loads((dir_path / "session.json").read_text(encoding="utf-8"))
    expected = json.loads((dir_path / "expected_decode.json").read_text(encoding="utf-8"))
    return session, expected


def test_handle_grounded_has_nonzero_resistance_block() -> None:
    session, expected = _load(GROUNDED)
    assert session["condition"] == "feet_handle_usbc_connected_on_ground"
    decoded = decode_frame(expected["frame_hex"])
    assert isinstance(decoded, MeasurementFrame)
    assert decoded.weight_kg == 78.9
    assert decoded.b5 == 0x01 and decoded.b6 == 0x21
    assert decoded.resistance_raw == [4549, 4215, 2190, 2039, 133]
    assert all(value != 0 for value in decoded.resistance_raw)
    assert session["decode_hypotheses"]["resistance_nonzero_count"] > 0
    assert session["decode_hypotheses"]["resistance_all_zero_count"] == 0


def test_handle_unplugged_zero_resistance_block_still_decodes_weight() -> None:
    session, expected = _load(UNPLUGGED)
    assert session["condition"] == "feet_handle_usbc_unplugged"
    decoded = decode_frame(expected["frame_hex"])
    assert isinstance(decoded, MeasurementFrame)
    assert decoded.weight_kg == 78.9
    # Layout bits still claim a resistance block, but values are all zero.
    assert decoded.b5 == 0x01 and decoded.b6 == 0x21
    assert decoded.resistance_raw == [0, 0, 0, 0, 0]
    assert session["decode_hypotheses"]["resistance_all_zero_count"] > 0
    assert session["decode_hypotheses"]["resistance_nonzero_count"] == 0


def test_condition_notifications_all_checksum_ok() -> None:
    for path in (GROUNDED, UNPLUGGED):
        session, _expected = _load(path)
        for record in session["notifications"]:
            frame = decode_frame(record["hex"])
            assert frame.checksum_valid is True
