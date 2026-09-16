"""Minimal local HTTP sync API for tracky-android on the Pixel 10.

Allows the Android WorkManager sync client to fetch raw weigh-in sessions
over local Wi-Fi / Tailscale. Completely stateless regarding user profiles.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlparse

from tracky_mcp.storage import MeasurementSession, SQLiteMeasurementStore

logger = logging.getLogger("tracky.sync_server")

CHANNEL_NAMES = [
    "lf20k",
    "lf100k",
    "rf20k",
    "rf100k",
    "lh20k",
    "lh100k",
    "rh20k",
    "rh100k",
    "t20k",
    "t100k",
]


def extract_session_telemetry(session: MeasurementSession) -> dict[str, Any]:
    """Extract raw weight and 10-channel impedance from stored session frames."""
    weight_kg = 0.0
    p1_values: list[int] | None = None
    p2_values: list[int] | None = None

    for frame in session.frames:
        m = frame.measurement
        if m is not None:
            if m.weight_raw_u16_be > 0 and weight_kg == 0.0:
                weight_kg = round(m.weight_raw_u16_be / float(m.weight_scale_divisor), 2)
            if m.b5 == 0x01 and m.b6 == 0x21 and len(m.resistance_raw) == 5:
                p1_values = list(m.resistance_raw)
            elif m.b5 == 0x01 and m.b6 == 0x22 and len(m.resistance_raw) == 5:
                p2_values = list(m.resistance_raw)

    raw_channels: list[int] | None = None
    ohms: dict[str, float] | None = None
    if p1_values is not None and p2_values is not None:
        raw_channels = p1_values + p2_values
        ohms = {
            name: round(val * 0.1, 1)
            for name, val in zip(CHANNEL_NAMES, raw_channels)
        }

    return {
        "session_id": session.id,
        "started_at_utc": session.started_at.isoformat(),
        "status": session.status.value,
        "weight_kg": weight_kg,
        "device_alias": session.device_alias,
        "address_hash": session.address_hash,
        "raw_impedance_channels": raw_channels,
        "ohms": ohms,
    }


def create_sync_handler(
    db_path: str | os.PathLike[str] | None = None,
    auth_token: str | None = None,
) -> type[BaseHTTPRequestHandler]:
    """Create a request handler bound to the database path and optional token."""

    class SyncRequestHandler(BaseHTTPRequestHandler):
        def _send_json(self, status: HTTPStatus, body: Any) -> None:
            data = json.dumps(body, indent=2).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(data)

        def _check_auth(self) -> bool:
            if not auth_token:
                return True
            auth_header = self.headers.get("Authorization", "")
            if not auth_header.startswith("Bearer "):
                return False
            token = auth_header[7:].strip()
            return token == auth_token

        def do_GET(self) -> None:
            parsed = urlparse(self.path)
            path = parsed.path.rstrip("/")

            if path == "/api/v1/health":
                self._send_json(
                    HTTPStatus.OK,
                    {"status": "ok", "service": "tracky-sync", "version": "0.1.0"},
                )
                return

            if not self._check_auth():
                self._send_json(
                    HTTPStatus.UNAUTHORIZED,
                    {"error": "unauthorized", "detail": "Valid Bearer token required"},
                )
                return

            if path == "/api/v1/sessions/unsynced":
                params = parse_qs(parsed.query)
                since_str = params.get("since", [None])[0]
                limit_str = params.get("limit", ["100"])[0]

                since: datetime | None = None
                if since_str:
                    try:
                        # Handle space decoded from unencoded '+' in timezone offsets
                        clean_since = since_str.replace(" ", "+")
                        since = datetime.fromisoformat(clean_since)
                        if since.tzinfo is None:
                            since = since.replace(tzinfo=timezone.utc)
                    except ValueError:
                        self._send_json(
                            HTTPStatus.BAD_REQUEST,
                            {
                                "error": "invalid_parameter",
                                "detail": "Invalid ISO 8601 timestamp for 'since'",
                            },
                        )
                        return

                try:
                    limit = int(limit_str)
                    limit = max(1, min(limit, 1000))
                except ValueError:
                    limit = 100

                try:
                    with SQLiteMeasurementStore(path=db_path) as store:
                        sessions = store.list_sessions_since(since=since, limit=limit)
                        items = [extract_session_telemetry(s) for s in sessions]
                    cursor = items[-1]["started_at_utc"] if items else since_str
                    self._send_json(
                        HTTPStatus.OK,
                        {
                            "count": len(items),
                            "cursor": cursor,
                            "sessions": items,
                        },
                    )
                except Exception as exc:
                    logger.exception("Failed to query sessions: %s", exc)
                    self._send_json(
                        HTTPStatus.INTERNAL_SERVER_ERROR,
                        {"error": "internal_error", "detail": str(exc)},
                    )
                return

            self._send_json(
                HTTPStatus.NOT_FOUND,
                {"error": "not_found", "detail": f"Path '{path}' not recognized"},
            )

        def log_message(self, format: str, *args: Any) -> None:
            logger.debug(
                "%s - - [%s] %s",
                self.address_string(),
                self.log_date_time_string(),
                format % args,
            )

    return SyncRequestHandler


def run_sync_server(
    host: str = "0.0.0.0",
    port: int = 8765,
    db_path: str | None = None,
    auth_token: str | None = None,
) -> None:
    """Run the blocking HTTP sync server."""
    token = auth_token or os.environ.get("TRACKY_SYNC_TOKEN")
    handler = create_sync_handler(db_path=db_path, auth_token=token)

    server = ThreadingHTTPServer((host, port), handler)
    logger.info("Tracky Sync API listening on http://%s:%d (auth=%s)", host, port, bool(token))

    try:
        server.serve_forever()
    except (KeyboardInterrupt, SystemExit):
        pass
    finally:
        server.server_close()
        logger.info("Tracky Sync API server stopped.")


def main() -> None:
    parser = argparse.ArgumentParser(description="Tracky local HTTP raw session sync API")
    parser.add_argument("--host", default="0.0.0.0", help="Host address to bind (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8765, help="Port to listen on (default: 8765)")
    parser.add_argument("--db-path", default=None, help="Path to SQLite database")
    parser.add_argument("--token", default=None, help="Pre-shared Bearer token for authentication")
    parser.add_argument("-v", "--verbose", action="store_true", help="Enable verbose debug logging")

    args = parser.parse_args()
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )
    run_sync_server(host=args.host, port=args.port, db_path=args.db_path, auth_token=args.token)


if __name__ == "__main__":
    main()
