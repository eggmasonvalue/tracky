# Decisions

Durable architecture only. Amend before competing. Not a changelog or worklog.

```text
## YYYY-MM-DD — title
Context: what forced the choice
Decision: what we chose
Tradeoff: what we gave up or rejected and why
Status: active | superseded by <date/title>
```

## 2026-09-16 — Linux exclusively owns acquisition and canonical evidence

Context: Reliable zero-touch capture requires an always-on host in the scale's room. Multiple BLE and protocol implementations had diverged, and Android background scanning cannot guarantee capture of the scale's short advertising window.

Decision: The Linux appliance exclusively owns BLE discovery, GATT, wire-protocol decoding, measurement quality, session lifecycle, stable source identity, and the canonical SQLite evidence store. Android, MCP, and skills consume Linux-owned interfaces and never acquire directly.

Tradeoff: Direct phone-only weighing is removed. Operation depends on the local Linux appliance, but protocol behavior and evidence have one authority.

Status: active

## 2026-09-16 — One acquisition path has two consumer projections

Context: AI agents need inspectable history while Android applications need Health Connect records. Those consumers require different presentation and interpretation behavior but the same observations.

Decision: An on-demand read-only MCP server plus skill serves agents. A non-launcher Android bridge synchronizes observations and writes supported, deterministic projections to Health Connect. Neither projection is a system of record.

Tradeoff: Each consumer owns a separate adapter and may support different outputs. Cross-component compatibility requires a versioned observation contract.

Status: active

## 2026-09-16 — One monorepo owns the Tracky product

Context: Separate repositories allowed architecture, fixtures, and contracts to diverge even though Linux, Android, and the skill form one release unit.

Decision: Keep Linux, Android, shared contracts, fixtures, skills, and canonical documentation in one repository while retaining independent language-specific builds.

Tradeoff: Repository migration and mixed-toolchain CI are more complex, but boundary changes and compatibility tests can be reviewed atomically.

Status: active

## 2026-09-16 — Preserve evidence separately from interpretation

Context: The wire format contains a display-confirmed weight and two physically observed five-word blocks, while electrical units, channel meanings, and body-composition formulas require additional validation.

Decision: Persist lossless ordered wire evidence and grade every interpretation. Keep unresolved electrical fields opaque. Enable a derived health metric only through a versioned algorithm with traceable coefficients, defined inputs, applicability, uncertainty, and explicit failure behavior. Never clamp invalid output into a plausible range.

Tradeoff: Health Connect and agent output may initially be weight-only, but future evidence can be reprocessed without another weigh-in and unsupported claims do not become health data.

Status: active

## 2026-09-17 — Recover scale-family derivation behavior

Context: No transparent modern prediction equation was found whose declared inputs
match Tracky's five-segment 20/100 kHz scalar impedance values. Published equations
use incompatible frequencies, electrical quantities, electrode paths, or calibration
populations, while matching commercial devices keep their algorithms undisclosed.
The QN SDK confirms that its native calculator consumes Tracky's exact profile and
ten-channel inputs, but its coefficient banks exist only in stripped native binaries.

Decision: Recover and independently reimplement the exact QN scale-family derivation
behavior from inspectable public artifacts. Do not ship vendor binaries. Require an
immutable algorithm version, coefficient-to-operand provenance, applicability rules,
and vendor-output golden vectors before enabling derived records. Describe successful
parity as vendor compatibility, not independent clinical validation.

Tradeoff: This can restore the intended smart-scale outputs without transplanting
incompatible academic equations, but reverse engineering is more costly and vendor
parity alone does not establish physiological accuracy. Derived records are
enabled only for a derivation version whose outputs match the native library.

Status: active

## 2026-10-31 — Linux derives composition from a Linux-side profile

Context: Deriving on Android duplicated the algorithm in Kotlin, required background Health Connect height reads that silently degraded imports, and hid composition from MCP. Tracky has one user.

Decision: Linux runs the single derivation implementation when serving observations, using a root-managed profile file that holds only height. The sync contract carries the versioned composition; Android writes it and reads nothing from Health Connect.

Tradeoff: Profile data now lives on the appliance and a height change requires editing a file there, but there is one implementation, one test suite, and every consumer sees the same numbers.

Status: active

## 2026-10-31 — Correct a physically inverted trunk pair

Context: Every capture from the current unit reports trunk impedance higher at 100 kHz than at 20 kHz while all four limbs are physically ordered. Tissue impedance cannot rise with frequency. Fed as-is, the vendor regression extrapolates to a markedly higher fat percentage that the vendor app itself rejects in favour of a feet-only estimate; with the pair swapped, the 8-electrode result agrees with that estimate.

Decision: Derivation treats a trunk-only inversion as a transposed pair, swaps it, and records `trunk_order: "transposed"`. Any limb inversion rejects composition instead. Canonical evidence is never rewritten.

Tradeoff: The correction could mask a genuine hardware fault on this unit, but it is gated by physics, visible in every result, and reversible by a derivation-version bump. A unit with an ordered trunk never triggers it.

Status: active

## 2026-10-31 — Derivation version drives Health Connect record versions

Context: Derived values must be correctable after import without duplicating records or wiping Health Connect.

Decision: Each session maps to fixed per-type `clientRecordId`s, and every record's `clientRecordVersion` is the derivation version. A higher version makes Android replay history from an empty cursor; sessions without composition have their derived records deleted.

Tradeoff: A version bump rewrites the whole history on the phone, which is cheap for one user and keeps Health Connect an exact projection.

Status: active

## 2026-09-16 — Android uses Health Connect profile data without owning it

Context: Derivation may require profile inputs. Health Connect provides height as a time-series record but has no equivalent stable fitness records for every potentially required input.

Decision: Android reads the latest applicable height from Health Connect and never writes height. Initial onboarding collects only required inputs unavailable from Health Connect, with no fabricated defaults and a later correction path. No profile data crosses to Linux.

Tradeoff: Some local user input remains necessary before profile-dependent derivation can run.

Status: superseded by 2026-10-31 — Linux derives composition from a Linux-side profile

## 2026-09-16 — Android keeps an import ledger, not a backup

Context: Reliable synchronization requires retries and deduplication, but duplicating the canonical evidence database would create ownership and reconciliation problems.

Decision: Android stores only source identity, contract and derivation versions, disposition, retry state, and Health Connect identity required for idempotent imports. Linux backup and restore remain deployment responsibilities.

Tradeoff: Android cannot restore the Linux evidence database and offline inspection remains limited.

Status: active

## 2026-09-16 — Deploy acquisition as a system service and MCP on demand

Context: Acquisition must survive logout and reboot, while MCP is needed only when an agent queries history. Independent acquisition owners can corrupt operation state.

Decision: Run one Linux daemon as a system-level `systemd` service under a dedicated account and state directory. Launch MCP on demand over stdio with read-only access; MCP does not instantiate acquisition or recovery services. User lingering is not part of the deployment.

Tradeoff: Administrative setup is required and MCP cannot initiate weigh-ins, but acquisition remains reliable and single-owner.

Status: active

## 2026-09-16 — Android has no ordinary launcher entry

Context: Android is background integration infrastructure rather than a daily-use scale application, but Health Connect still requires onboarding and permission-rationale surfaces.

Decision: Expose Health Connect onboarding, permission rationale, and actionable diagnostics without an ordinary launcher entry. Use Google Sans Flex under SIL Open Font License 1.1 and ship its license notice with the asset.

Tradeoff: Diagnostics are less discoverable than a normal application and must be reachable from Health Connect or actionable notifications.

Status: active
