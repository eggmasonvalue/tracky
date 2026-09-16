"""Unit tests for the minimal raw session sync HTTP endpoint."""

from __future__ import annotations

import json
import threading
import urllib.request
from datetime import datetime, timezone
from http.server import ThreadingHTTPServer
from typing import Any

from tracky_mcp.protocol import append_checksum
from tracky_mcp.storage import SQLiteMeasurementStore
from tracky_mcp.sync_server import create_sync_handler


def _make_server(db_path: Any, auth_token: str | None = None) -> tuple[ThreadingHTTPServer, str]:
    handler = create_sync_handler(db_path=db_path, auth_token=auth_token)
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    port = server.server_port
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, f"http://127.0.0.1:{port}"


def _create_sample_session(store: SQLiteMeasurementStore, session_time: datetime) -> str:
    known = store.register_device(
        connection_target="AA:BB:CC:DD:EE:FF",
        seen_at=session_time,
        advertised_name="QN-Scale",
    )
    p1 = append_checksum(
        bytes(
            (
                0x10, 0x12, 0x20, 0x1E, 0xD2, 0x01, 0x21,
                0x11, 0xC5, 0x10, 0x77, 0x08, 0x8E, 0x07, 0xF7, 0x00, 0x85,
            )
        )
    )
    p2 = append_checksum(
        bytes(
            (
                0x10, 0x12, 0x20, 0x1E, 0xD2, 0x01, 0x22,
                0x00, 0x74, 0x00, 0x8C, 0x00, 0x7A, 0x00, 0xE6, 0x00, 0xC8,
            )
        )
    )
    session_id = store.create_session(started_at=session_time, device_alias=known.alias)
    store.append_frames(session_id, [p1.hex(), p2.hex()])
    store.complete_session(session_id, ended_at=session_time)
    return session_id


def test_health_endpoint(tmp_path: Any) -> None:
    db_path = tmp_path / "test.db"
    server, base_url = _make_server(db_path)
    try:
        req = urllib.request.Request(f"{base_url}/api/v1/health")
        with urllib.request.urlopen(req) as resp:
            assert resp.status == 200
            data = json.loads(resp.read().decode("utf-8"))
            assert data["status"] == "ok"
    finally:
        server.shutdown()


def test_auth_protection(tmp_path: Any) -> None:
    db_path = tmp_path / "test.db"
    server, base_url = _make_server(db_path, auth_token="secret-token-123")
    try:
        # Request without token -> 401
        req_unauth = urllib.request.Request(f"{base_url}/api/v1/sessions/unsynced")
        try:
            urllib.request.urlopen(req_unauth)
            assert False, "Expected 401 Unauthorized"
        except urllib.error.HTTPError as err:
            assert err.code == 401

        # Request with correct token -> 200
        req_auth = urllib.request.Request(
            f"{base_url}/api/v1/sessions/unsynced",
            headers={"Authorization": "Bearer secret-token-123"},
        )
        with urllib.request.urlopen(req_auth) as resp:
            assert resp.status == 200
            data = json.loads(resp.read().decode("utf-8"))
            assert data["count"] == 0
    finally:
        server.shutdown()


def test_fetch_unsynced_sessions_with_10_channel_telemetry(tmp_path: Any) -> None:
    db_path = tmp_path / "test.db"
    t1 = datetime(2026, 9, 13, 8, 0, 0, tzinfo=timezone.utc)
    t2 = datetime(2026, 9, 13, 9, 0, 0, tzinfo=timezone.utc)

    with SQLiteMeasurementStore(path=db_path) as store:
        s1_id = _create_sample_session(store, t1)
        s2_id = _create_sample_session(store, t2)

    server, base_url = _make_server(db_path)
    try:
        # Fetch all
        req = urllib.request.Request(f"{base_url}/api/v1/sessions/unsynced")
        with urllib.request.urlopen(req) as resp:
            assert resp.status == 200
            data = json.loads(resp.read().decode("utf-8"))
            assert data["count"] == 2
            first = data["sessions"][0]
            assert first["session_id"] == s1_id
            assert first["weight_kg"] == 78.90
            assert first["raw_impedance_channels"] == [
                4549, 4215, 2190, 2039, 133,
                116, 140, 122, 230, 200,
            ]
            assert first["ohms"]["lf20k"] == 454.9
            assert first["ohms"]["t100k"] == 20.0

        # Fetch with cursor (since t1)
        req_since = urllib.request.Request(
            f"{base_url}/api/v1/sessions/unsynced?since={t1.isoformat()}"
        )
        with urllib.request.urlopen(req_since) as resp:
            assert resp.status == 200
            data_since = json.loads(resp.read().decode("utf-8"))
            assert data_since["count"] == 1
            assert data_since["sessions"][0]["session_id"] == s2_id
    finally:
        server.shutdown()
