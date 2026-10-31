# Tracky

Private, local-first acquisition and Health Connect integration for the Tracky `TS-CS10B` / `TR-CS10B` eight-electrode scale.

## Architecture

```text
Scale → Linux acquisition appliance → SQLite system of record
                                  ├→ on-demand MCP → agent skill
                                  └→ Android bridge → Health Connect
```

Linux is the only BLE and scale-protocol owner, and it derives body composition from a Linux-side profile. MCP reads captured history and composition for agents. Android is a non-launcher background writer: it synchronizes Linux observations over authenticated HTTPS and writes weight, body fat, lean body mass, body water mass, bone mass, and basal metabolic rate to Health Connect. Derived values are a vendor-compatible reimplementation of the QN scale-family algorithm, parity-tested against the native library; see [`context/DERIVATION.md`](context/DERIVATION.md).

See:

- [`context/MAP.md`](context/MAP.md) — module boundaries and data flow
- [`context/DECISIONS.md`](context/DECISIONS.md) — durable tradeoffs
- [`context/CONVENTIONS.md`](context/CONVENTIONS.md) — coding and verification rules
- [`context/PROTOCOL.md`](context/PROTOCOL.md) — evidence-graded protocol knowledge
- [`context/DERIVATION.md`](context/DERIVATION.md) — body-composition derivation
- [`contracts/README.md`](contracts/README.md) — cross-component contract
- [`context/DEPLOYMENT.md`](context/DEPLOYMENT.md) — installation and operations

## Repository

```text
android/    Non-launcher Android Health Connect writer and import ledger
linux/      Python BlueZ acquisition, SQLite, derivation, sync API, MCP, units, installer
contracts/  Shared observation contract
skills/     Agent-facing analysis skill
context/    Canonical project map, decisions, conventions, and focused references
```

## Development

Linux:

```bash
cd linux
uv sync --python 3.13 --extra dev --extra ble --extra mcp
uv run pytest
uv run ruff check src tests
```

Android:

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Deployment: see [`context/DEPLOYMENT.md`](context/DEPLOYMENT.md). Android has no launcher entry; open onboarding with `adb shell am start -n dev.eggmasonvalue.tracky/.MainActivity` or from Health Connect's app permissions.

## License

Tracky source code is licensed under the [MIT License](LICENSE). Bundled third-party assets retain their own compatible notices. The bundled Google Sans Flex Android asset is the official v4.007 release and includes its SIL Open Font License 1.1 notice under `android/app/src/main/assets/licenses/`.
