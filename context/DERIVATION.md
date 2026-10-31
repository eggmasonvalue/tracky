# Body-composition derivation

Linux derives composition once, in `linux/src/tracky_mcp/derivation.py`, from a
session's wire-valid opaque blocks, its selected weight, and the Linux profile
(`/etc/tracky/profile.toml`, `height_cm`). The sync API and MCP emit the result;
Android writes it to Health Connect and derives nothing.

## Inputs

The CS10B sends five segments at 20 and 100 kHz across packets `0x21` (block A)
and `0x22` (block B). Each big-endian word × 0.1 is ohms.

| Segment | 20 kHz | 100 kHz |
| --- | --- | --- |
| Left foot/leg | A0 | A1 |
| Right foot/leg | A2 | A3 |
| Left hand/arm | A4 | B0 |
| Right hand/arm | B1 | B2 |
| Trunk | B3 | B4 |

The derivation consumes them in the native argument order
`RH20, LH20, T20, RF20, LF20, RH100, LH100, T100, RF100, LF100`.
Canonical storage keeps the words opaque.

## Derivation version 2

Version 2 reimplements the composition path of `calcSpecialtyBodyData` in the
x86-64 `libyolanda_calc.so` from `YolandaQingniu/qnscalesdk` at commit
`c9ce0cecf80b1d48270ca40823f3dc8f4e8c07cc` (SHA-256
`8164af7cacfda745dd3a7d4bd2baa9cbd438de534aa7c8e5428078afe362d7c0`). Every
coefficient is the exact float64 value of a binary128 `.rodata` constant. It is
vendor-compatible output, not independent clinical validation.

### Gates

Composition is absent, with a contract reason code, when:

| Code | Condition |
| --- | --- |
| `no_weight` | no Linux-selected kg weight |
| `no_impedance` | no assembled burst, or every word is zero |
| `impedance_out_of_range` | any channel is not in `(0, 700]` Ω (native gate) |
| `limb_frequency_inversion` | any limb has `Z20 < Z100` |
| `no_profile` | no Linux profile is configured |
| `outside_model_domain` | any value the native code would reject or clamp |

Tissue impedance magnitude cannot rise with frequency. When all four limbs are
ordered and only the trunk has `T20 < T100`, the pair is treated as transposed,
swapped, and reported as `trunk_order: "transposed"`; otherwise `"wire"`. See
`DECISIONS.md`.

### Regressions

Features: height (cm), weight (kg), BMI, the ten impedances, and
`height² / Z` for each channel. Four regressions are evaluated:

- fat % `F`: one of four banks selected by BMI `< 19`, `< 24`, `< 31`, else;
- water kg `A`: four banks with the same BMI brackets;
- protein kg `P` and skeletal muscle kg `S`: one bank each.

Sex and age enter no output emitted here; the native calculator uses them only
for visceral and subcutaneous fat, score, body type, and body age.

### Composition

```text
T  = w·F/100            L  = w − T
B0 = L − A − P          B  = clamp(B0, 0.039·w, 0.051·w)
δ  = (B0 − B)/2         A′ = A + δ        P′ = P + δ
BMR = trunc(370 + 21.6·L)
```

`outside_model_domain` applies unless `0 < F < 75`, `2.1 ≤ F ≤ 70`,
`10 ≤ 100·A′/w ≤ 80`, `100·P′/w ≥ 5`, and `0 < S < L`. Outputs are never
clamped.

| Output | Value | Rounding |
| --- | --- | --- |
| `body_fat_pct` | `F` | 1 dp |
| `lean_body_mass_kg` | `L` | 1 dp |
| `body_water_kg` | `A′` | 2 dp |
| `bone_mass_kg` | `B` | 2 dp |
| `protein_kg` | `P′` | 2 dp |
| `skeletal_muscle_kg` | `S` | 2 dp |
| `basal_metabolic_rate_kcal_per_day` | BMR | truncated |

Rounding adds a half step plus `1e-7` and truncates, as the native code does.

### Verification

`linux/tests/test_derivation.py` compares the four raw regressions and every
rounded output with native-library results for one vector per BMI bank.
Agreement is to about `1e-13` and exact after rounding. Captured sessions also
matched the native library; they stay out of Git as personal measurements.

### Limitations

- The calibration cohort, reference method, and error are unpublished.
- BMI bank boundaries introduce step discontinuities.
- The scale reports impedance magnitude only; no phase angle or Cole modelling.
- Vendor parity is not physiological validation.

## Health Connect projection

| Output | Record |
| --- | --- |
| weight | `WeightRecord` |
| `body_fat_pct` | `BodyFatRecord` |
| `lean_body_mass_kg` | `LeanBodyMassRecord` |
| `body_water_kg` | `BodyWaterMassRecord` |
| `bone_mass_kg` | `BoneMassRecord` |
| `basal_metabolic_rate_kcal_per_day` | `BasalMetabolicRateRecord` |

Protein and skeletal muscle have no Health Connect type and stay with MCP. Do
not map vendor scores, body age, visceral levels, or segmental estimates into
unrelated record types.

## Changing the derivation

Any change to coefficients, gates, rounding, or input interpretation bumps
`DERIVATION_VERSION`. Android then replays history and overwrites its records
through `clientRecordVersion`; stored raw evidence never changes.
