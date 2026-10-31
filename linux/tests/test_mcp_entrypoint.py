"""The stdio MCP CLI must read only the explicitly selected database."""

import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest

import tracky_mcp.mcp.__main__ as entrypoint
from tracky_mcp.storage import SessionStatus, SQLiteMeasurementStore


def test_mcp_db_path_option_uses_read_only_store_and_preserves_stdio(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    path = tmp_path / "explicit database.sqlite3"
    with SQLiteMeasurementStore(path) as writer:
        session_id = writer.create_session(started_at=datetime(2026, 1, 1, tzinfo=timezone.utc))
        transports: list[str] = []

        def server(store: SQLiteMeasurementStore) -> SimpleNamespace:
            assert store.path == path
            assert store.read_only
            assert store.get_session(session_id).status is SessionStatus.OPEN  # No recovery.
            with pytest.raises(sqlite3.OperationalError, match="readonly"):
                store._connection.execute("DELETE FROM measurement_sessions")
            return SimpleNamespace(run=lambda *, transport: transports.append(transport))

        monkeypatch.setattr(entrypoint, "create_server", server)
        monkeypatch.setattr(sys, "argv", ["tracky-mcp", "--db-path", str(path)])
        monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path / "unused-default"))
        entrypoint.main()
        assert transports == ["stdio"]
        assert writer.get_session(session_id).status is SessionStatus.OPEN
        assert not (tmp_path / "unused-default").exists()
