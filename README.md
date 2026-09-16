# Tracky

Private, local-first acquisition and Health Connect integration for the Tracky `TS-CS10B` / `TR-CS10B` eight-electrode scale.

## Architecture

```text
Scale → Linux acquisition appliance → SQLite system of record
                                  ├→ on-demand MCP → agent skill
                                  └→ Android bridge → Health Connect
```

Linux is the only BLE and scale-protocol owner. MCP reads captured history for agents. Android synchronizes canonical observations, performs only approved deterministic derivations, and writes supported records to Health Connect.

See:

- [`context/MAP.md`](context/MAP.md) — module boundaries and data flow
- [`context/DECISIONS.md`](context/DECISIONS.md) — durable tradeoffs
- [`context/CONVENTIONS.md`](context/CONVENTIONS.md) — coding and verification rules
- [`context/PROTOCOL.md`](context/PROTOCOL.md) — evidence-graded protocol knowledge
- [`contracts/README.md`](contracts/README.md) — target cross-component contract
- [`context/DEPLOYMENT.md`](context/DEPLOYMENT.md) — target deployment

## Repository

```text
android/    Android Health Connect bridge; legacy direct-BLE code awaits removal
linux/      Python BlueZ acquisition, SQLite, sync API, and MCP implementation
contracts/  Shared contract specification; implementation pending
skills/     Agent-facing analysis skill
context/    Canonical project map, decisions, conventions, and focused references
```

## Development

Linux:

```bash
cd linux
python -m pip install -e '.[dev,ble,mcp]'
pytest
ruff check src tests
```

Android:

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The current branch is an architectural migration, not a deployment-ready release. Run `todo list` for current implementation work.

## License

Tracky source code is licensed under the [MIT License](LICENSE). Bundled third-party assets retain their own compatible notices; Google Sans Flex, when added, must include its SIL Open Font License 1.1 notice.
