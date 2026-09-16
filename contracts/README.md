# Linux-to-consumer observation contract

**Status: target contract — not yet implemented.**

This is the versioned, read-only observation boundary for MCP and Android. Linux is
canonical. Android is a consumer and keeps only a minimal import ledger; it is not a
backup. No user profile or profile attributes cross from Android to Linux.

## Envelope

Every page and observation uses a declared `contract_version` and contains:

- `session_id`: canonical, immutable, Linux-issued opaque ID. It is stable across
  retries, pages, MCP reads, and Android imports.
- `raw_frames`: ordered references to immutable raw-frame evidence, in capture order.
  References identify evidence only; they do not expose transport payloads by default.
- `selected`: optional measurement/burst selection, present only when the explicit,
  versioned `selection_policy_version` permits selection from the referenced frames.
- `versions`: `transport_version`, `decoder_version`, `selection_policy_version`,
  and `quality_version`.
- `quality`: exactly one of `accepted`, `contact_failed`, or `indeterminate`, as
  defined by `quality_version`. It must not be inferred solely from nonzero values.
- `opaque_block_a` and `opaque_block_b`: two opaque blocks, each exactly five words.
  Consumers must preserve and round-trip them without parsing, matching, or assigning
  meaning.

A selected weight, when present, is structured as `raw`, `divisor`, `unit`, and
`provenance`. `raw` and `divisor` are source values; `unit` is a declared,
versioned unit identifier; `provenance` identifies the decoder/selection lineage.
No consumer may manufacture, relabel, or convert these fields. Unsupported
impedance labels or units are not part of this contract.

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

Android imports are idempotent using the Health Connect
`clientRecordId` + `clientRecordVersion` pair. The pair is the import key: repeating
an identical pair is a no-op; a newer version may replace the Android projection
according to the versioned import policy; an older version must not overwrite it.
The ledger stores only the minimum pair, session reference, import result, and
contract/version metadata required for deduplication and audit—not a backup of
Linux evidence or profiles.
