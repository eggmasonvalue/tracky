"""Optional stateless MCP 2026-07-28 adapter.

The base package remains importable without the ``mcp`` extra. Call
:func:`create_server` only in an MCP-enabled installation.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from mcp.server import MCPServer

    from tracky_mcp.acquisition import AcquisitionService, DeviceService
    from tracky_mcp.storage import SQLiteMeasurementStore


def create_server(
    store: SQLiteMeasurementStore,
    *,
    devices: DeviceService | None = None,
    acquisitions: AcquisitionService | None = None,
) -> MCPServer:
    """Lazily build the server so protocol/BLE/storage installs need no MCP SDK."""

    try:
        from .server import create_server as build_server
    except ImportError as exc:
        missing = exc.name or ""
        if missing in {"mcp", "pydantic"} or missing.startswith(("mcp.", "pydantic.")):
            raise ImportError(
                "MCP server requires the optional 'mcp' dependency; install "
                "with `python -m pip install -e '.[mcp]'`."
            ) from exc
        raise
    return build_server(store, devices=devices, acquisitions=acquisitions)


__all__ = ["create_server"]
