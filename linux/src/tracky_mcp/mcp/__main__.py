"""Local stdio entry point for the Tracky MCP server."""

from __future__ import annotations

from tracky_mcp.storage import SQLiteMeasurementStore

from . import create_server


def main() -> None:
    """Run one local stdio server using the XDG SQLite database."""

    with SQLiteMeasurementStore() as store:
        create_server(store).run(transport="stdio")


if __name__ == "__main__":
    main()
