"""The stdio MCP CLI must read only the explicitly selected database."""

import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest

import tracky_mcp.mcp.__main__ as entrypoint
from tracky_mcp.derivation import Profile
from tracky_mcp.storage import SessionStatus, SQLiteMeasurementStore


@pytest.mark.parametrize("with_profile", [False, True])
def test_mcp_db_path_option_uses_read_only_store_and_preserves_stdio(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str],
    with_profile: bool,
) -> None:
    path = tmp_path / "explicit database.sqlite3"
    profile_path = tmp_path / "synthetic profile.toml"
    arguments = ["tracky-mcp", "--db-path", str(path)]
    if with_profile:
        profile_path.write_text("height_cm = 170\n", encoding="utf-8")
        arguments.extend(["--profile", str(profile_path)])
    with SQLiteMeasurementStore(path) as writer:
        session_id = writer.create_session(started_at=datetime(2026, 1, 1, tzinfo=timezone.utc))
        transports: list[str] = []

        def server(
            store: SQLiteMeasurementStore, *, profile: Profile | None = None
        ) -> SimpleNamespace:
            assert profile == (Profile(170.0) if with_profile else None)
            assert store.path == path
            assert store.read_only
            assert store.get_session(session_id).status is SessionStatus.OPEN  # No recovery.
            with pytest.raises(sqlite3.OperationalError, match="readonly"):
                store._connection.execute("DELETE FROM measurement_sessions")
            return SimpleNamespace(run=lambda *, transport: transports.append(transport))

        monkeypatch.setattr(entrypoint, "create_server", server)
        monkeypatch.setattr(sys, "argv", arguments)
        monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path / "unused-default"))
        entrypoint.main()
        assert transports == ["stdio"]
        assert writer.get_session(session_id).status is SessionStatus.OPEN
        assert not (tmp_path / "unused-default").exists()
        assert capsys.readouterr().out == ""


@pytest.mark.parametrize("contents", [None, "not valid TOML", "height_cm = 0\n"])
def test_mcp_invalid_profile_fails_before_opening_store(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str],
    contents: str | None,
) -> None:
    profile_path = tmp_path / "invalid profile.toml"
    if contents is not None:
        profile_path.write_text(contents, encoding="utf-8")

    def unexpected_store(**_kwargs: object) -> None:
        raise AssertionError("invalid profile opened the store")

    monkeypatch.setattr(entrypoint, "SQLiteMeasurementStore", unexpected_store)
    monkeypatch.setattr(sys, "argv", ["tracky-mcp", "--profile", str(profile_path)])
    with pytest.raises((OSError, ValueError)):
        entrypoint.main()
    assert capsys.readouterr().out == ""
