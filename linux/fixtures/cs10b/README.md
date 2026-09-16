# CS10B fixtures

Sanitized Linux captures for Tracky `TR-CS10B` / `TS-CS10B` (aliases).
Address hash `000000000000` only — no raw BD_ADDR.

| Dir | What |
| --- | --- |
| `20260809T182636Z` | Handshake, unit **lb** (`0x02`); weight golden 79.10 kg |
| `20260809T191051Z` | Handshake, unit **kg** (`0x01`); weight golden 79.00 kg |
| `20260809T194335Z` | ScaleClient; handle **plugged on ground** → non-zero `resistance_raw` |
| `20260809T194641Z` | ScaleClient; handle **USB-C unplugged** → zero `resistance_raw`, weight OK |

Each dir: `session.json`, `expected_decode.json`, `notes.md`.

Weight: BE u16 @ bytes 3–4 / 100 → kg (high confidence).  
`resistance_raw[5]`: structured, unitless. Streams keep every 0x10 (no average).
