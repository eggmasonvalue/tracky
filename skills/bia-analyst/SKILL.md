---
name: bia-analyst
description: Retrieve and conservatively summarize read-only Tracky MCP history for latest, session-list, or specific-session requests, including capture-backed weight, Linux-derived composition, and contract-defined quality; keep opaque word blocks unassigned and do not calculate composition or diagnose.
---

# Tracky History Analyst (`bia-analyst`)

Use this skill for consumer-side review of evidence already captured in Tracky history. It is a read-only history skill, not a device-acquisition or BLE skill. Report what the current Tracky MCP contract provides, with provenance and explicit limits.

## Operating rules

- Use only the four read-only Tracky MCP history tools: `list_sessions`, `get_latest_session`, `get_session`, and `get_session_evidence`.
- Treat the MCP schema and returned contract fields as the source of truth. Do not invent tool names, arguments, resource paths, fields, units, or quality meanings.
- Keep capture facts separate from interpretation. A missing field is unavailable evidence, not permission to estimate it.
- Preserve returned identifiers, values, units, timestamps, quality labels, opaque blocks, and version fields. Normalize formatting only when the original value remains unambiguous.
- Report observations; do not diagnose, assign causes, or give clinical conclusions.

## Evidence workflow

### 1. Select the history operation

Match the request to the operation the deployed MCP interface exposes:

- **Latest:** call `get_latest_session`.
- **List:** call `list_sessions`, honoring the requested `limit` when supplied and staying within the server's bound. If `next_cursor` is returned, pass that exact opaque string unchanged for the next page; never decode or construct it.
- **Session metadata:** call `get_session` with the requested `session_id`.
- **Evidence:** call `get_session_evidence` with the returned or requested `session_id` when frame-backed weight, raw frames, or opaque blocks are needed.

The session metadata tool and `tracky://sessions/{session_id}` resource are equivalent views. The evidence tool and `tracky://sessions/{session_id}/frames` resource are equivalent views; use the tool when the client has weak resource support. Metadata `raw_frames` is an O(1) resource URI/count descriptor; request evidence for frame bodies. Use only the returned session identifier when moving from a list or latest result to an evidence request. If an operation or identifier is unavailable, state that the request is blocked and name the missing evidence. Keep acquisition, recovery, discovery, and configuration out of the workflow.

**Completion criterion:** the requested latest, list, session-metadata, or session-evidence result has been returned by a read-only MCP operation, or the response clearly states why it could not be retrieved.

### 2. Establish provenance

For every reported observation, include the provenance that the contract supplies, such as:

- `session_id` (also exposed as the compatibility `id` in summaries);
- `started_at` / `started_at_display` and `ended_at` when present;
- returned device alias or model label;
- returned field name, unit, and weight provenance;
- exact `quality` and `quality_version`;
- `contract_version` and relevant `versions` when they qualify an interpretation. Treat `versions.decoder_version` as one version only when non-null; use `versions.decoder_versions` for mixed captures.

Do not add provenance that was not returned. If multiple records or measurements are present, keep them associated with their own session and timestamp rather than merging them.

**Completion criterion:** each reported fact can be traced to a returned session, frame, or contract version without guessing.

### 3. Report capture-backed weight

Report weight only when a returned frame or explicitly selected observation supplies it. Prefer the structured `weight` fields: `raw`, `divisor`, `unit`, and `provenance`. Preserve those source values exactly. A frame may also expose the decoder's exact `weight_kg` rendering; do not relabel it or manufacture a different unit.

The session-list summary is metadata-only and does not load or promise a weight value. If weight is absent, malformed, or has no usable unit, say so plainly. Do not infer weight from opaque fields, prior sessions, display text, or derived quantities. A change or baseline may be reported only when the relevant captured measurements are both present and the comparison is explicitly requested; otherwise report the individual observations.

**Completion criterion:** the response either reports the returned weight with its available provenance or explicitly marks weight as unavailable.

### 4. Apply contract-defined quality

Use only the exact returned `quality` label and its `quality_version`. For the current version, permitted descriptions are:

| Quality | Permitted description |
| --- | --- |
| `accepted` | Linux evidence-backed accepted capture quality; do not turn it into a clinical conclusion. |
| `contact_failed` | Linux evidence-backed contact-failure quality; retain the exact label. |
| `indeterminate` | Quality remains indeterminate; retain the exact label. |

`status` is the session lifecycle (`open`, `complete`, or `aborted`) and is separate from `quality`. Do not infer quality from lifecycle, zero values, nonzero values, frame repetition, or a device label. If a quality state and values appear inconsistent, report both and do not resolve the conflict.

**Completion criterion:** the response preserves the exact contract quality and does not add a stronger contact or health claim.

### 5. Keep opaque evidence opaque

Use the contract names `opaque_block_a` and `opaque_block_b` for session/evidence-level blocks. The aggregate pair is present only when Linux's `BurstAssembler` observed a wire-valid ordered packet-1/packet-2 pair; corrupt, interrupted, or incomplete streams have no aggregate pair. Individual frame evidence uses `opaque_words`; it does not promote an unpaired block to an aggregate field. Repeat returned values and provenance when useful; do not rename them to electrical fields.

The MCP evidence surface deliberately keeps these values opaque even though the separate protocol record documents the QN SDK-derived channel mapping and scale. Do not reinterpret, convert, combine, rank, average, or ratio them inside this skill unless a versioned derivation implementation explicitly exposes an approved result. If blocks are absent or incomplete, say that the evidence is unavailable or incomplete.

**Completion criterion:** every opaque value in the response is quoted as returned or omitted with a clear reason; no physical meaning has been added.

## Body-composition boundary

Report returned `composition` fields with their units and `derivation_version` (currently 2). This is Linux-derived vendor-compatible output, not independent clinical validation. `trunk_order: transposed` means the scale's trunk pair was physically inverted and corrected by Linux; preserve that provenance.

If `composition` is null, report the exact `composition_unavailable` reason (`no_profile` when no Linux profile is configured). Request single-session metadata for composition; list summaries remain metadata-only. Do not calculate missing metrics, reinterpret opaque blocks, add normal ranges, or make diagnostic claims.

**Completion criterion:** composition is quoted as returned with its derivation version and limits, or marked unavailable with the returned reason.

## Response shape

Choose the smallest response that answers the request while retaining provenance and limits.

### Latest or specific-session response

```markdown
### Tracky history observation
- Session: <returned session_id, or unavailable>
- Captured at: <returned started_at_display/timestamp, or unavailable>
- Weight: <returned weight.raw/divisor/unit and provenance, or unavailable>
- Contract quality: <exact returned quality and quality_version, or unavailable>
- Opaque blocks: <returned opaque_block_a/b, or not returned>
- Composition: <returned fields and derivation_version, or composition_unavailable reason>
- Scope: Capture-backed observations and Linux-derived vendor-compatible output; not clinical validation.
```

Use `get_session_evidence` before filling weight or opaque-block lines when the session metadata response does not return them.

### Session-list response

```markdown
### Tracky sessions
| Session | Captured at | Weight | Contract quality |
| --- | --- | --- | --- |
| <returned session_id> | <returned timestamp> | unavailable until evidence is requested | <exact quality and version> |

Next cursor: <returned opaque next_cursor, or none>

Only records and fields returned by Tracky MCP are included. Pass any returned cursor unchanged; do not decode it.
```

Successful read results carry `ok: true`. Empty latest history has `ok: false` and structured error code `no_sessions`; an unknown session has `session_not_found`; a malformed cursor has `invalid_cursor`. Report the exact code and message as the limitation rather than treating it as a captured observation. If evidence is incomplete, report the evidence that is still available. Never fill gaps with BLE acquisition, remembered values, model assumptions, or medical interpretation.

## Safety boundary

The output is an evidence summary for consumer history. It is not a diagnosis, a clinical assessment, or a substitute for professional care. Keep conclusions at the level of what was captured, what the contract explicitly classifies, and what remains unknown.
