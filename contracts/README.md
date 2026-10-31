# Linux-to-consumer observation contract

**Status: version 2 is implemented by the Linux canonical store, on-demand MCP projection, authenticated sync API, and Android Health Connect bridge.**

This is the versioned, read-only observation boundary for MCP and Android. Linux is
canonical and derives body composition; Android is a consumer that keeps only a
minimal import ledger and is not a backup.

## Envelope

Every page and observation uses a declared `contract_version` and contains:

- `session_id`: canonical, immutable, Linux-issued opaque ID. It is stable across
  retries, pages, MCP reads, and Android imports.
- `raw_frames`: an immutable evidence reference. Metadata responses use an O(1)
  `{resource_uri, count}` descriptor; explicit evidence responses retain ordered
  frame references and full frame bodies.
- `selected`: optional measurement/burst selection, present only when the explicit,
  versioned `selection_policy_version` permits selection from the referenced frames.
- `versions`: `transport_version`, optional singular `decoder_version`,
  `decoder_versions`, `selection_policy_version`, and `quality_version`. The
  singular field is null when one session contains mixed decoder versions; the
  distinct-version list remains authoritative.
- `quality`: exactly one of `accepted`, `contact_failed`, or `indeterminate`, as
  defined by `quality_version`. It must not be inferred solely from nonzero values.
- `opaque_block_a` and `opaque_block_b`: two opaque blocks, each exactly five words,
  exposed together only when `BurstAssembler` can assemble a wire-valid ordered pair.
  Consumers must preserve and round-trip them without parsing, matching, or assigning
  meaning.
- `composition` and `composition_unavailable`: Linux-derived body composition, or
  the reason it is absent. See below.

A selected weight, when present, is structured as `raw`, `divisor`, `unit`, and
`provenance`. Weight-selection policy version 1 selects the latest frame in a run
of three repeated, positive, wire-valid display-weight observations and persists
that frame reference before projection. `raw` and `divisor` are source values;
`unit` is a declared, versioned unit identifier; `provenance` identifies the
decoder/selection lineage. No consumer may manufacture, relabel, or convert these
fields. Unsupported impedance labels or units are not part of this contract.

## Linux MCP projection

The deployed MCP process is launched on demand over stdio and opens the canonical
SQLite store read-only by behavior. It does not construct an acquisition service,
run operation recovery, scan, connect, acquire, or mutate records. Its bounded tool
surface is exactly:

- `list_sessions(limit, cursor)`: bounded newest-first session summaries and
  aggregate frame counts, returning an opaque `next_cursor` when more rows exist;
- `get_latest_session()`: metadata for the newest session;
- `get_session(session_id)`: metadata for one session;
- `get_session_evidence(session_id)`: the complete ordered wire-evidence stream.

The canonical resources are `tracky://sessions/{session_id}` for metadata and
`tracky://sessions/{session_id}/frames` for evidence. The two session tools are
tool equivalents for clients with weak resource support. Summary queries use SQL
counts and metadata and do not materialize raw frame streams; metadata returns an
O(1) evidence resource URI/count descriptor. The evidence tool and frame resource
are the explicit full-stream reads.

`list_sessions` orders by `(started_at_utc DESC, session_id DESC)` and uses a
keyset cursor, so equal timestamps are neither skipped nor repeated. Clients pass
`next_cursor` back unchanged and never decode or construct it. Successful tool
responses carry `ok: true`; read failures carry `ok: false` and an `error` object
with stable `code`, `message`, and optional `session_id`. Invalid cursors return
`invalid_cursor`. Empty history returns `no_sessions` from `get_latest_session`;
missing IDs return `session_not_found` from the session tools and matching
resources.

Session metadata and evidence success responses declare `contract_version: 1`,
expose the canonical `session_id`, `raw_frames`, optional `selected` data,
`versions`, exact `quality`/`quality_version`, and `opaque_block_a`/
`opaque_block_b` when a wire-valid ordered burst pair was assembled. A session
`status` is lifecycle state and is independent of `quality`. The summary also
retains `id` as a compatibility alias for `session_id`.

## Read and pagination

MCP and Android reads are read-only observations of the Linux canonical store.
Pages carry an opaque `next_cursor`. A cursor is monotonic for its stream, must be
returned unchanged, and must not be decoded or constructed by consumers. Empty
pages still advance only according to the server's cursor rules.

## Acknowledgement and Android import

Acknowledgement means only that the consumer durably received and validated the
specified immutable `session_id` and page/item identity. It does not mutate Linux,
confirm measurement validity, or authorize deletion. Acknowledgements are safe to
retry and are separate from pagination.

The Android HTTP adapter consumes the authenticated `GET
/api/v1/sessions/unsynced` projection. Its response declares `contract_version`,
`versions` (including `derivation_version`, present even on empty pages),
`sessions`, and the opaque `next_cursor`. A retryable `source_state` returns no
sessions or cursor advancement, including while an acquisition session remains
open. Each session may contain a Linux-selected source weight:

```json
{
  "selected": {
    "frame_sequence": 12,
    "weight": {"raw": 7890, "divisor": 100, "unit": "kg", "provenance": "..."}
  }
}
```

Android imports only a completed session with a validated selected weight whose
unit is the declared `kg`. It never infers values from raw frames or opaque words.
The endpoint requires HTTPS and bearer authentication; the app ships neither a
host-specific endpoint nor a token.

## Composition

Linux derives composition per `context/DERIVATION.md` from the session's opaque
blocks, its selected weight, and the Linux profile. It is present only alongside a
valid selected weight:

```json
{
  "composition": {
    "derivation_version": 2,
    "trunk_order": "wire",
    "height_cm": 170.0,
    "body_fat_pct": 31.5,
    "lean_body_mass_kg": 48.0,
    "body_water_kg": 36.67,
    "bone_mass_kg": 2.73,
    "protein_kg": 8.58,
    "skeletal_muscle_kg": 26.42,
    "basal_metabolic_rate_kcal_per_day": 1406
  },
  "composition_unavailable": null
}
```

When `composition` is null, `composition_unavailable` is one of `no_weight`,
`no_impedance`, `impedance_out_of_range`, `limb_frequency_inversion`,
`no_profile`, or `outside_model_domain`. `trunk_order` is `wire` or `transposed`.

## Health Connect projection

- Record identity per session: `base = "tracky-" + first 32 hex of sha256(session_id)`,
  with `clientRecordId`s `$base-weight`, `$base-body-fat`, `$base-lean-body-mass`,
  `$base-body-water-mass`, `$base-bone-mass`, and `$base-bmr`.
- Every record's `clientRecordVersion` is the page's `derivation_version`.
  Health Connect replaces a record only when the version is higher.
- `WeightRecord` is always written. The five derived records are written when
  composition is present and their write permission is granted; otherwise they are
  deleted by `clientRecordId`. Only the weight write permission is required.
- When a page reports a higher `derivation_version` than the ledger has seen,
  Android resets its cursor and replays the full history.
- Record time is `started_at_utc` with the device zone's offset at that instant.
- The ledger stores the record identity and version, source session reference,
  disposition (`pending`, `imported`, or `quarantined`), retry/error state, all
  returned Health Connect record IDs, and one cursor row. Android commits the cursor
  only after every item in the page is imported or durably quarantined.
