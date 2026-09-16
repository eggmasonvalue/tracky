# QN-Scale / CS10B protocol record

**Validation status:** live path validated 2026-09-16; this record intentionally
keeps no sample weights, personal raw values, capture timestamps, addresses,
credentials, or conversation details.

## Evidence vocabulary

- **E3 — live-correlated:** observed in live validation and independently
  confirmed by the device display or by a repeatable wire response.
- **E2 — repository-correlated:** reproduced by the imported Linux fixtures and
  protocol tests, with matching implementation in the Android client.
- **E1 — implementation hypothesis:** a deterministic code label or candidate
  interpretation not established by an independent physical validation.
- **U — unknown:** do not infer or expose as a product fact.

A higher grade applies only to the claim stated; it does not promote adjacent
fields or interpretations.

## Discovery and GATT

| Claim | Grade |
| --- | --- |
| The device advertises as `QN-Scale`. | E3/E2 |
| The advertisement exposes the `FFE0` service family. | E3/E2 |
| The connected GATT service is `FFF0`; `FFF1` is the notification path and `FFF2` is the write path. | E3/E2 |
| `AE02` is not required by the validated path. | E2; not a universal exclusion |

The scanner may match the name or either `FFE0`/`FFF0` service family. The
validated protocol should nevertheless use the discovered `FFF0` service and
its `FFF1`/`FFF2` characteristics rather than assuming an advertisement UUID is
the connected service.

## Handshake

After enabling notifications on `FFF1`, the observed sequence is:

1. RX `0x12` information frame.
2. TX `0x13` unit configuration and the short `0x02` time-push command on
   `FFF2`.
3. RX `0x14` status response.
4. TX `0x20` time-sync command.
5. RX `0x21` status response.
6. TX the two fixed `0xA0` setup writes.
7. RX live `0x10` measurement frames.

The state machine advances only on valid, ordered, checksum-bearing frames. The
short five-byte `0x02` time-push command is the documented checksum exception;
other known protocol frames use the additive trailer
`sum(frame_without_trailer) & 0xff`. The handshake succeeds in live validation
(E3) and is replayed by both imported clients (E2).

## Measurement frames

- A `0x10` frame is **18 bytes** long and carries the additive checksum (E3/E2).
- With zero-based indexing, bytes `3..4` are a big-endian unsigned weight
  field. `u16be(frame[3:5]) / 100` is kilograms (E3: independently display
  confirmed; E2: fixture and Android/Linux decoder agreement).
- `b5=0x01, b6=0x21` identifies the first observed five-word block. After it,
  the host writes `1f0620102176` to `FFF2`; the following block uses `b6=0x22`
  (E3/E2).
- Both block packets contain five consecutive big-endian unsigned 16-bit words
  at bytes `7..16` (E2, with live sequence confirmation at E3).
- Preserve every accepted frame and its checksum status. Do not average the
  stream or silently discard a repeated/near-repeated frame.

The word block is intentionally named `word[0..4]` here. It must not be
serialized or presented as resistance, impedance, ohms, segment data, or a
body-composition metric merely because the current decoder has those candidate
names.

## Word-block interpretation: hypotheses only

The imported `reassemble_burst` code proposes a two-packet, ten-word layout and
also exposes a `raw * 0.1 Ohm` conversion. Those are useful deterministic
reassembly experiments, not physical protocol evidence. The repository's own
protocol comments retain the block as opaque and explicitly leave units,
frequencies, and segment meaning unproven.

Pending explicit validation, treat all of the following as **E1/U**:

- anatomical or segment ordering;
- frequency assignment;
- `raw * 0.1 Ohm` scaling;
- resistance versus impedance terminology;
- any BIA, hydration, fat, muscle, fluid-ratio, or other physiological
  interpretation.

The Android/Linux implementations and their unit tests prove byte boundaries
and repeatable reassembly only. They do not prove those physical meanings.

## Contact, foot-only, and completion quality

Live condition observations are qualitative by design:

- A **completed handle-contact** acquisition produced stable nonzero words
  (E3). This is evidence that the handle path can produce a usable-looking
  word block, not proof of units or anatomy.
- A **completed foot-only Err2** acquisition produced an all-zero word block
  (E3). Keep the weight field separate from word-block quality; zero words are
  not a BIA result and must not be converted into physiological values.
- An **interrupted foot-only** acquisition could produce irregular nonzero words
  (E3). Nonzero data alone is therefore insufficient evidence of valid contact,
  completion, or BIA quality.

Completion and quality are separate dimensions:

1. **Wire-valid:** frame shape and checksum pass.
2. **Measurement-valid:** a wire-valid `0x10` frame supplies the independently
   validated weight field; a present block may still be zero.
3. **Burst-candidate:** the ordered `b6=0x21` / acknowledgment / `b6=0x22`
   sequence is present and both five-word blocks are retained.
4. **Quality-accepted:** requires an explicitly completed acquisition and a
   validated contact/quality rule. This rule is not yet established for the
   word block.

A terminal storage status (`complete`, `aborted`, or an operation error) must be
recorded independently of any selected sample. The repository deliberately
retains every frame and leaves `selected_frame_sequence` optional; completion
must not imply that one repeated frame is the product-level final result.
Interrupted or device-error observations remain diagnostic evidence and must
not be promoted to BIA output.

## Evidence sources

The durable repository basis is the sanitized material under:

- `linux\src\tracky_mcp\ble\client.py`
- `linux\src\tracky_mcp\protocol\checksum.py`
- `linux\src\tracky_mcp\protocol\decode.py`
- `linux\src\tracky_mcp\protocol\frames.py`
- `linux\src\tracky_mcp\protocol\session.py`
- `linux\fixtures\cs10b\README.md` and its sanitized `*\notes.md` records
- `android\app\src\main\java\dev\eggmasonvalue\tracky\core\ble\AndroidBleAcquisitionClient.kt`
- `android\app\src\main\java\dev\eggmasonvalue\tracky\core\protocol\ScaleProtocol.kt`
- `android\app\src\main\java\dev\eggmasonvalue\tracky\core\protocol\ScaleSession.kt`
- the corresponding Linux and Android protocol tests

Fixture and implementation agreement is evidence for decoding behavior, not a
substitute for independent validation of physical units or BIA semantics.
