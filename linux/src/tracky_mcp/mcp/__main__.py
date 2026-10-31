"""On-demand stdio entry point for the read-only Tracky MCP server."""

from __future__ import annotations

import argparse

from tracky_mcp.derivation import load_profile
from tracky_mcp.storage import SQLiteMeasurementStore

from . import create_server


def main() -> None:
    """Run one on-demand, read-only history server over stdio."""

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db-path", help="SQLite database path (default: XDG data directory)")
    parser.add_argument(
        "--profile", help="TOML profile with height_cm; without it no composition is derived"
    )
    args = parser.parse_args()
    profile = load_profile(args.profile) if args.profile else None
    with SQLiteMeasurementStore(path=args.db_path, read_only=True) as store:
        create_server(store, profile=profile).run(transport="stdio")


if __name__ == "__main__":
    main()
