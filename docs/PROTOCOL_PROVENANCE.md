# CS10B protocol provenance

The pure Kotlin kernel in `app/src/main/java/dev/eggmasonvalue/tracky/core/protocol/` is a behavior port of the CS10B protocol implementation from:

- Source: <https://github.com/eggmasonvalue/tracky-mcp>
- Exact revision: `473b39bc63179fc626406ee4e2448403d157002e`
- Source implementation paths:
  - `src/tracky_mcp/protocol/checksum.py`
  - `src/tracky_mcp/protocol/frames.py`
  - `src/tracky_mcp/protocol/decode.py`
  - `src/tracky_mcp/protocol/session.py`
- Source test paths:
  - `tests/test_protocol.py`
  - `tests/test_condition_fixtures.py`
- Supporting research note: `context/research/cs10b-ble-protocol.md`

That source repository is licensed under the **MIT License** (copyright 2026 tracky-mcp contributors). The Kotlin port retains the source behavior while making malformed packets a raw-preserving `DecodeResult.Malformed` instead of an exception.

## Sanitized fixtures

Only the four small `expected_decode.json` files were copied. Full session captures, operator notes, GATT logs, and device identifiers were not copied.

| Kotlin test resource | Source path at the revision above |
| --- | --- |
| `fixtures/cs10b_20260809T182636Z_expected_decode.json` | `fixtures/cs10b/20260809T182636Z/expected_decode.json` |
| `fixtures/cs10b_20260809T191051Z_expected_decode.json` | `fixtures/cs10b/20260809T191051Z/expected_decode.json` |
| `fixtures/cs10b_20260809T194335Z_expected_decode.json` | `fixtures/cs10b/20260809T194335Z/expected_decode.json` |
| `fixtures/cs10b_20260809T194641Z_expected_decode.json` | `fixtures/cs10b/20260809T194641Z/expected_decode.json` |

## Scientific boundaries

- The 18-byte `0x10` layout and weight interpretation are capture-backed: bytes 3–4 are a big-endian unsigned 16-bit value divided by 100 to obtain kilograms. Confidence is high for the captured CS10B sessions, including an in-session comparison, but this is not proof for every QingNiu/Yolanda model or firmware.
- `b5=0x01` and `b6=0x21` identify the captured five-word layout. The names or meanings of those flag bytes are not established.
- The five big-endian words at bytes 7–16 are intentionally unitless raw values. Their electrical unit, calibration, frequencies, segment order, and physiological meaning are unproven. They must not be labeled as ohms or mapped to body segments.
- An all-zero five-word block is still a present block. In the sanitized condition fixture it occurred with the handle unplugged; zero must not be interpreted as a valid zero-resistance measurement.
- The kernel does not select, average, or infer stability from repeated measurements. It does not derive body fat, water, muscle, or other composition metrics.
- Unknown, malformed, and checksum-failing packets remain available as raw bytes so future interpretations can be audited against the original evidence.
