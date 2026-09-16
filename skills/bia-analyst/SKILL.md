---
name: bia-analyst
description: Retrieve and conservatively summarize read-only Tracky MCP history for latest, session-list, or specific-session requests, including capture-backed weight and contract-defined contact quality; keep electrical fields opaque and do not derive body composition or diagnose.
---

# Tracky History Analyst (`bia-analyst`)

Use this skill for consumer-side review of evidence already captured in Tracky history. It is a read-only analysis skill, not a device-acquisition or BLE skill. Report what the current Tracky MCP contract provides, with provenance and explicit limits.

## Operating rules

- Use only read-only Tracky MCP history operations exposed by the current runtime.
- Treat the MCP schema and returned contract fields as the source of truth. Do not invent tool names, arguments, resource paths, fields, units, or quality meanings.
- Keep capture facts separate from interpretation. A missing field is unavailable evidence, not permission to estimate it.
- Preserve returned labels, values, units, timestamps, session identifiers, and quality labels. Normalize formatting only when the original value remains unambiguous.
- Report observations; do not diagnose, assign causes, or give clinical conclusions.

## Evidence workflow

### 1. Select the evidence operation

Match the request to the operation the current MCP interface actually exposes:

- **Latest:** retrieve the contract's latest-session evidence.
- **List:** retrieve the contract's session-list evidence, honoring the caller's requested bound when supported.
- **Session:** retrieve evidence for the requested session identifier.

Use only the returned session identifier when moving from a list result to a session-detail request. If the requested operation or identifier is unavailable, state that the request is blocked and identify the missing evidence. Do not substitute an invented endpoint or silently choose another session.

**Completion criterion:** the requested latest, list, or session evidence has been returned by a read-only MCP operation, or the response clearly states why it could not be retrieved.

### 2. Establish provenance

For every reported measurement, include the provenance that the contract supplies, such as:

- session identifier;
- capture timestamp;
- device or source label;
- returned field name and unit;
- contract quality state.

Do not add provenance that was not returned. If multiple records or measurements are present, keep them associated with their own session and timestamp rather than merging them.

**Completion criterion:** each reported fact can be traced to a returned session or list record without guessing.

### 3. Report capture-backed weight

Report weight only when a weight measurement is present in the retrieved evidence. Preserve its returned numeric value and unit, and identify its session and capture time when available. This is a capture-backed observation, not an estimate or a body-composition result.

If weight is absent, malformed, or has no usable unit, say so plainly. Do not infer weight from electrical fields, prior sessions, display text, or derived quantities. A change or baseline may be reported only when the relevant captured measurements are both present and the comparison is explicitly requested; otherwise report the individual observations.

**Completion criterion:** the response either reports the returned weight with its available provenance or explicitly marks weight as unavailable.

### 4. Apply contract-defined observation quality

Use a contact or observation classification only when the current contract supplies a quality state with that meaning. Preserve the raw quality label alongside any short explanation.

| Contract support | Permitted description |
| --- | --- |
| Explicit state meaning complete handle contact | **Complete handle-contact** |
| Explicit state meaning all-zero contact failure | **All-zero contact failure** |
| Explicit state meaning irregular or indeterminate observation | **Irregular/indeterminate**; retain the contract's exact label |
| No applicable state, an unknown state, or values without documented quality semantics | **Quality not classified by the contract** |

Zero-valued electrical fields alone do not establish all-zero contact failure. Nonzero fields alone do not establish complete handle-contact. Unexpected values, missing values, or apparent inconsistencies remain descriptive observations unless the contract explicitly classifies them. If a quality state and values appear inconsistent, report both and do not resolve the conflict.

**Completion criterion:** the response uses one of the classifications above only when its contract meaning is explicit; otherwise it reports the quality state as unavailable or unknown.

### 5. Keep electrical observations opaque

Electrical field names and values are evidence, not a validated physiological model. Repeat them using the exact contract labels and units when available. Pending validated semantics, do not reinterpret words such as resistance, impedance, frequency, channel, segment, or raw count; do not map fields to anatomy; and do not convert, combine, rank, or ratio them.

In particular, do not infer hydration, fluid shifts, tissue type, muscle or fat change, symmetry, contact quality, or health status from electrical values. If the contract provides electrical data without validated semantics, say that the fields are present but their consumer-side meaning is unresolved.

**Completion criterion:** every electrical value in the response is either quoted as a returned field or omitted with a clear reason; no physical meaning has been added.

## Body-composition boundary

This skill is currently blocked from deriving body composition. Do not calculate or report body fat, fat mass, fat-free or lean mass, total or segmental water, visceral fat, metabolic age, segmental composition, or similar estimates.

Do not introduce equations, coefficients, threshold ranges, normal ranges, proxy ratios, demographic corrections, or diagnostic claims. The presence of multiple electrical fields, height, age, sex, historical measurements, or a device label does not authorize a calculation.

A future implementation may cross this boundary only after a versioned, validated algorithm is explicitly available with all of the following:

1. a version identifier and authoritative source;
2. a documented input schema and field semantics;
3. defined quality and applicability rules;
4. validation evidence for the intended consumer use; and
5. documented output semantics and limits.

Until that gate is satisfied and this skill is updated to name the approved algorithm, remain descriptive and state that body-composition derivation is unavailable.

## Response shape

Choose the smallest response that answers the request while retaining provenance and limits.

### Latest or specific-session response

```markdown
### Tracky history observation
- Session: <returned identifier, or unavailable>
- Captured at: <returned timestamp, or unavailable>
- Weight: <returned value and unit, or unavailable>
- Contract quality: <exact state and permitted classification, or not classified>
- Electrical observations: <returned fields verbatim or "not returned">
- Scope: Capture-backed observations only; electrical semantics and body-composition derivation are unavailable.
```

### Session-list response

```markdown
### Tracky sessions
| Session | Captured at | Weight | Contract quality |
| --- | --- | --- | --- |
| <returned values> | <returned values> | <returned value/unit or unavailable> | <exact state or not classified> |

Only records and fields returned by Tracky MCP are included.
```

If retrieval fails or evidence is incomplete, lead with the limitation, identify the missing operation or field, and report the evidence that is still available. Never fill gaps with BLE acquisition, remembered values, model assumptions, or medical interpretation.

## Safety boundary

The output is an evidence summary for consumer history. It is not a diagnosis, a clinical assessment, or a substitute for professional care. Keep conclusions at the level of what was captured, what the contract explicitly classifies, and what remains unknown.
