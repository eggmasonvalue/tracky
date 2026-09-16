# Current implementation status

## Linux acquisition

- Bleak discovery uses tested active scanning on BlueZ; passive startup is not used.
- Linux owns BLE transport, wire decoding, burst assembly, quality policy, and canonical SQLite evidence.
- Revision-3 and revision-4 SQLite databases migrate transactionally to revision 5 without dropping raw frames, decoded fields, operations, or opaque word evidence.
- Quality is versioned and independent of lifecycle: `accepted`, `contact_failed`, or `indeterminate`.
- Completion waits for configured idle grace, stream close, or explicit stop; the safety timeout remains a last-resort failure.
- Weight-selection policy version 1 persists a source frame only after three repeated, positive, wire-valid display-weight observations; Android never selects a frame itself.
- Canonical storage keeps the ten words opaque. The downstream derivation contract uses the QN SDK-derived five-segment 20/100 kHz ordering, `raw × 0.1 Ω` scaling, and conditional byte de-obfuscation recorded in `context/PROTOCOL.md`. ACK-dependent capture acceptance requires a persisted TX observation.

## MCP projection

- MCP is launched on demand over stdio and is a read-only projection over daemon-owned SQLite history.
- The deployed surface is bounded to `list_sessions`, `get_latest_session`, `get_session`, and `get_session_evidence`; it does not instantiate acquisition or perform global operation recovery.
- Canonical session and frame resources remain available at `tracky://sessions/{session_id}` and `tracky://sessions/{session_id}/frames`; the two session tools provide equivalents for clients with weak resource support.
- Session summaries use aggregate SQLite queries for counts and metadata and do not load complete frame streams; metadata exposes an O(1) frame-resource URI/count descriptor. Evidence is loaded only through the explicit evidence tool or frame resource.
- `list_sessions` uses bounded opaque keyset cursors with stable newest-first `(timestamp, session_id)` ordering; equal timestamps are not skipped.
- Aggregate opaque blocks are exposed only from `BurstAssembler` wire-valid ordered pairs. Migrated decoded JSON remains lossless in storage while MCP removes or normalizes forbidden legacy semantic keys.
- Mixed decoder versions are represented as a distinct version list rather than a misleading maximum. Empty/latest and missing-session reads return stable structured outcomes.
- MCP views, the v1 observation contract, and `skills/bia-analyst/SKILL.md` use quality/version fields, structured source weight evidence, and opaque block terminology.

## Android Health Connect projection

- Android is now a non-launcher integration surface reached through Health Connect's permission rationale/privacy entry points; it has no Bluetooth permission, BLE feature/transport, scale protocol, manual acquisition state, weigh-in UI, or canonical evidence database.
- The old evidence store is replaced by a Room import ledger containing source session identity, contract/derivation versions, `pending`/`imported`/`quarantined` disposition, retry state, and Health Connect record identity, plus one opaque cursor row. It stores no raw frames, profiles, or backup data.
- WorkManager uses connected-network constraints, exponential backoff, bounded HTTPS request/response timeouts, and one serialized sync path. The durable cursor advances only after each page item is imported or quarantined; retryable source state and failed Health Connect writes leave it unchanged.
- Endpoint validation requires HTTPS, rejects URL credentials/query/fragment/whitespace, ships no host or token default, and stores the bearer token with Android Keystore AES-GCM. Health Connect writes only Linux-selected, idempotent `WeightRecord` values with `clientRecordId`/`clientRecordVersion` and scale source metadata.
- Onboarding/rationale requests Height and historical-data read plus Weight write permissions. The bridge reads the latest applicable `HeightRecord` and never writes height. Derived-metric code and required profile inputs remain to be added after the exact QN scale-family formulas and applicability rules are recovered and validated.
- Google Sans Flex v4.007 was fetched from the official `googlefonts/googlesans-flex` release and bundled with its OFL-1.1 notice under `android/app/src/main/assets/licenses/`.

## Derivation

- QN SDK-derived prior art establishes the ten-channel left/right foot, left/right hand, and trunk ordering at 20/100 kHz, `raw × 0.1 Ω` scaling, and conditional byte de-obfuscation.
- A focused literature review found no transparent modern equation matching Tracky's five-segment 20/100 kHz scalar inputs. Matching commercial devices use undisclosed algorithms; published modern equations require incompatible frequencies, electrical quantities, electrode paths, or calibration populations.
- The QN SDK wrapper confirms that its native calculator consumes height, sex, age, weight, and all ten channels. Exact calculations remain in stripped `libyolanda_calc.so` binaries; relevant symbols survive, but no coefficient-to-operand mapping has yet been established.
- The clean-room `Modern-Apps` calculator is not vendor-equivalent: it ignores most segmental inputs, uses literature-derived and hand-tuned coefficients, and clamps outputs. Earlier conflicting Android/skill coefficients remain rejected.
- The active research direction is narrowly targeted recovery of the QN scale-family algorithm, followed by versioned vendor-output parity tests. Vendor parity must not be represented as independent clinical validation.
- Android and the analyst skill remain weight-only until exact formulas are recovered, provenance and applicability are documented, and the selected behavior is tested consistently.

## Sync API

- The Android observation endpoint requires bearer authentication for health and data routes, opens SQLite read-only, defaults to loopback, and runs only with either a TLS certificate/key pair or explicit loopback-only external TLS termination.
- The endpoint returns versioned pages with opaque keyset cursors and never advances past an open source session. It exposes only persisted Linux-selected weight evidence.
- Tokens are loaded from a protected file or environment, never from command-line arguments. Responses omit wildcard CORS and internal exception details.

## Verification

- Linux: 76 tests pass and Ruff is clean under UV-managed CPython 3.13.11.
- Android: unit tests, lint, and debug assembly pass. The debug build installs and launches on the connected Pixel 10; the Health Connect permission surface exposes only height/history read and weight write access, the latest-height read succeeds, WorkManager registers constrained jobs, and no launcher activity is present.
- Documentation lint and `git diff --check` pass.
- Real WorkManager retry timing, Debian service behavior, BLE acquisition under the service account, and an authenticated TLS end-to-end projection remain deployment gates.
