"""Lossless SQLite storage tests using the sanitized CS10B fixtures."""

from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path

import pytest

from tracky_mcp.protocol import DECODER_ID, DECODER_VERSION
from tracky_mcp.storage import (
    SchemaRevisionError,
    SessionClosedError,
    SessionStatus,
    SQLiteMeasurementStore,
    default_database_path,
)

FIXTURE_ROOT = Path(__file__).parents[1] / "fixtures" / "cs10b"
CONDITION_FIXTURE = FIXTURE_ROOT / "20260809T194335Z" / "session.json"
STARTED_AT = datetime(2026, 8, 9, 19, 43, 35, tzinfo=timezone.utc)


def _condition_fixture() -> dict:
    return json.loads(CONDITION_FIXTURE.read_text(encoding="utf-8"))


def test_default_database_path_honors_xdg_and_home() -> None:
    assert default_database_path(environ={"XDG_DATA_HOME": "/data"}) == Path(
        "/data/tracky-mcp/tracky.sqlite3"
    )
    assert default_database_path(environ={"HOME": "/home/alice"}) == Path(
        "/home/alice/.local/share/tracky-mcp/tracky.sqlite3"
    )


def test_fixture_session_round_trips_losslessly_without_selecting_stable_frame(
    tmp_path: Path,
) -> None:
    fixture = _condition_fixture()
    path = tmp_path / "tracky.sqlite3"

    with SQLiteMeasurementStore(path) as store:
        session_id = store.create_session(
            session_id="session-1",
            started_at=STARTED_AT,
            device_alias=fixture["device"]["alias"],
            model_label=fixture["device"]["model_label"],
            address_hash=fixture["device"]["address_hash"],
            requested_unit=fixture["unit"]["requested"],
            condition=fixture["condition"],
            capture_id=fixture["capture_id"],
            metadata={"fixture_schema_version": fixture["schema_version"]},
        )
        sequences = store.append_frames(
            session_id,
            (notification["hex"] for notification in fixture["notifications"]),
        )
        store.complete_session(session_id, ended_at=STARTED_AT + timedelta(seconds=15))

        session = store.get_session(session_id)

    assert sequences == list(range(len(fixture["notifications"])))
    assert session.status is SessionStatus.COMPLETE
    assert session.selected_frame_sequence is None
    assert session.selected_frame is None
    assert session.display_timezone == "Asia/Kolkata"
    assert session.started_at_local.utcoffset() == timedelta(hours=5, minutes=30)
    assert session.metadata == {"fixture_schema_version": 1}
    assert [frame.raw_hex for frame in session.frames] == [
        notification["hex"] for notification in fixture["notifications"]
    ]
    assert len(session.measurements) == fixture["summary"]["measurement_count"]

    measurement = session.measurements[-1]
    assert measurement.decoder_id == DECODER_ID
    assert measurement.decoder_version == DECODER_VERSION
    assert measurement.measurement is not None
    assert measurement.measurement.weight_raw_u16_be == 7890
    assert measurement.measurement.weight_kg == Decimal("78.9")
    assert measurement.measurement.resistance_raw == (4549, 4215, 2190, 2039, 133)
    assert measurement.measurement.resistance_unit is None
    assert "unproven" in measurement.measurement.resistance_provenance

    # The file is a durable system of record rather than an in-memory projection.
    with SQLiteMeasurementStore(path) as reopened:
        persisted = reopened.get_session(session_id)
    assert persisted == session


def test_selected_frame_must_reference_a_measurement(tmp_path: Path) -> None:
    fixture = _condition_fixture()
    with SQLiteMeasurementStore(tmp_path / "tracky.sqlite3") as store:
        session_id = store.create_session(started_at=STARTED_AT)
        info_sequence = store.append_frame(session_id, fixture["notifications"][0]["hex"])
        measurement_sequence = store.append_frame(
            session_id, fixture["notifications"][3]["hex"]
        )

        with pytest.raises(ValueError, match="must be a measurement"):
            store.complete_session(
                session_id,
                ended_at=STARTED_AT + timedelta(seconds=1),
                selected_frame_sequence=info_sequence,
            )

        store.complete_session(
            session_id,
            ended_at=STARTED_AT + timedelta(seconds=1),
            selected_frame_sequence=measurement_sequence,
        )
        session = store.get_session(session_id)

    assert session.selected_frame == session.frames[measurement_sequence]
    assert session.selected_frame.measurement is not None


def test_malformed_and_empty_frames_are_retained(tmp_path: Path) -> None:
    with SQLiteMeasurementStore(tmp_path / "tracky.sqlite3") as store:
        session_id = store.create_session(started_at=STARTED_AT)
        short_sequence = store.append_frame(session_id, bytes.fromhex("1001"))
        empty_sequence = store.append_frame(session_id, b"")
        session = store.get_session(session_id)

    short = session.frames[short_sequence]
    assert short.raw == bytes.fromhex("1001")
    assert short.opcode == 0x10
    assert short.frame_kind == "decode_error"
    assert "must be 18 bytes" in (short.decode_error or "")

    empty = session.frames[empty_sequence]
    assert empty.raw == b""
    assert empty.opcode is None
    assert empty.frame_kind == "decode_error"
    assert "empty frame" in (empty.decode_error or "")


def test_closed_session_preserves_frames_and_rejects_append(tmp_path: Path) -> None:
    with SQLiteMeasurementStore(tmp_path / "tracky.sqlite3") as store:
        session_id = store.create_session(started_at=STARTED_AT)
        store.append_frame(session_id, bytes.fromhex("ff00"))
        store.abort_session(session_id, ended_at=STARTED_AT + timedelta(seconds=1))

        with pytest.raises(SessionClosedError):
            store.append_frame(session_id, bytes.fromhex("ff01"))

        session = store.get_session(session_id)

    assert session.status is SessionStatus.ABORTED
    assert [frame.raw_hex for frame in session.frames] == ["ff00"]


def test_timestamps_must_be_aware_and_session_end_cannot_precede_start(
    tmp_path: Path,
) -> None:
    with SQLiteMeasurementStore(tmp_path / "tracky.sqlite3") as store:
        with pytest.raises(ValueError, match="timezone"):
            store.create_session(started_at=datetime(2026, 8, 9))

        session_id = store.create_session(started_at=STARTED_AT)
        with pytest.raises(sqlite3.IntegrityError):
            store.complete_session(session_id, ended_at=STARTED_AT - timedelta(seconds=1))


def test_unknown_schema_revision_fails_loudly(tmp_path: Path) -> None:
    path = tmp_path / "future.sqlite3"
    connection = sqlite3.connect(path)
    connection.execute("PRAGMA user_version = 999")
    connection.close()

    with pytest.raises(SchemaRevisionError, match="999"):
        SQLiteMeasurementStore(path)


def test_list_sessions_is_newest_first(tmp_path: Path) -> None:
    with SQLiteMeasurementStore(tmp_path / "tracky.sqlite3") as store:
        older = store.create_session(session_id="older", started_at=STARTED_AT)
        newer = store.create_session(
            session_id="newer", started_at=STARTED_AT + timedelta(days=1)
        )

        sessions = store.list_sessions(limit=1)

    assert [session.id for session in sessions] == [newer]
    assert older != newer
