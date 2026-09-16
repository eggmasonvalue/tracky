# Body-composition derivation

## Established device inputs

The QN/Yolanda SDK-derived protocol implementation establishes the CS10B
five-segment, dual-frequency input layout used by Tracky:

| Segment | 20 kHz | 100 kHz |
| --- | --- | --- |
| Left foot/leg | packet `0x21`, word 0 | packet `0x21`, word 1 |
| Right foot/leg | packet `0x21`, word 2 | packet `0x21`, word 3 |
| Left hand/arm | packet `0x21`, word 4 | packet `0x22`, word 0 |
| Right hand/arm | packet `0x22`, word 1 | packet `0x22`, word 2 |
| Trunk | packet `0x22`, word 3 | packet `0x22`, word 4 |

Each big-endian word uses the QN decoder's `kRatio=0.1` conversion to ohms.
QN-family devices may advertise byte-level impedance obfuscation; when enabled,
the decoder swaps bits 3/5 and 0/4 before assembling each word. The captured
Tracky path uses the plain representation.

Primary implementation references:

- <https://github.com/vayun-mathur/Modern-Apps/blob/c5d759c2ac086d8fe01e775db9496824561fb3a6/things/src/main/java/com/vayunmathur/things/platform/ScaleBleManager.kt>
- <https://github.com/vayun-mathur/Modern-Apps/blob/c5d759c2ac086d8fe01e775db9496824561fb3a6/things/src/main/java/com/vayunmathur/things/platform/ScaleBleProtocol.kt>
- <https://github.com/YolandaQingniu/qnscalesdk>
- <https://github.com/JackChan1999/boohee_v5.6>

Linux must retain the original words as canonical evidence. Conversion and
anatomical naming belong to a versioned derivation layer.

## Equation status

No complete modern coefficient set has yet been established for implementation.
"Segmental Multi-Frequency Sum-of-Segments" describes a model class, not a
single international-consensus equation with universal coefficients. The earlier
symbolic model used height-squared-over-impedance terms for arms, legs, and trunk,
then summed segment estimates, but it did not identify source-backed numerical
coefficients.

A review of published prediction equations found no transparent modern equation
whose declared inputs match Tracky's five-segment 20/100 kHz scalar impedance
values. Contemporary segmental equations generally require 50 or 250 kHz
resistance, often with reactance at 5 or 50 kHz. Those quantities cannot be
reconstructed from impedance magnitude alone, and equations are not transferable
across frequencies, electrode paths, instruments, or calibration populations.
The closest published alternatives remain unsuitable:

- Bracco et al. (1996) published 100 kHz segment equations for 51 normal and
  overweight women, but they require measured segment lengths, use a different
  measurement arrangement, and are not a dual-frequency model.
- Scalfi et al. (1997) published
  `TBW (L) = 0.563 × height² / Z100 + 2.695`, but it uses whole-body hand-to-foot
  impedance and was calibrated on 19 women with anorexia nervosa.
- Choi et al. (2021) published modern segment equations, but they require separate
  50 kHz resistance and reactance and were calibrated on male amputees.
- Validation studies using InBody 230/270 instruments share Tracky's five-segment
  20/100 kHz shape but evaluate undisclosed manufacturer outputs rather than
  publishing reusable coefficients.

Supporting reviews and primary evidence:

- <https://pmc.ncbi.nlm.nih.gov/articles/PMC11137940/>
- <https://journals.physiology.org/doi/full/10.1152/jappl.1996.81.6.2580>
- <https://pmc.ncbi.nlm.nih.gov/articles/PMC7177846/>
- <https://pmc.ncbi.nlm.nih.gov/articles/PMC9002650/>
- <https://pubmed.ncbi.nlm.nih.gov/39047869/>

The following prior coefficient implementations are rejected:

- Android used mutually unsupported arm/leg/trunk coefficients.
- The analyst skill used a different unsupported arm/leg/trunk set.
- The `Modern-Apps` `BodyComposition.kt` implementation combines native Yolanda
  control-flow observations with literature-derived and hand-tuned coefficients;
  its comments explicitly state that the proprietary native coefficient banks were
  not copied verbatim. It uses only a combined low-frequency hand path, ignores
  the other segmental channels, and clamps calculated values.

A published Japanese ALM equation found during prior research uses standing
8-electrode measurements at 5, 50, and 250 kHz. Its inputs do not match Tracky's
20/100 kHz segmental inputs, so its coefficients must not be transplanted.

## QN algorithm recovery

The public `YolandaQingniu/qnscalesdk` repository at commit
`c9ce0cecf80b1d48270ca40823f3dc8f4e8c07cc` confirms that
`BleScaleData.calcSpecialtyBodyData` receives height, sex, age, weight, and all ten
20/100 kHz segment values. The Java archive contains only JNI wrappers; the
calculation and its coefficient banks reside in stripped `libyolanda_calc.so`
binaries and are not published as source.

The arm64 binary retains the relevant dynamic symbols. The JNI entry point calls
native `calcSpecialtyBodyData`, which directly calls
`calcSpecialtyBodyFat` and `calcSpecialtyBodyWaterWeight` along with segmental
muscle/fat helpers. The binary also exposes named single- and double-frequency
algorithm families. Its arithmetic uses anonymous binary128 constants, however,
and the inspected wrappers and direct call graph do not associate those constants
with inputs strongly enough to state an exact formula. Seven ABI builds are
available, including x86 and x86-64 builds suitable for more focused analysis.

This establishes a viable reverse-engineering route to scale-family compatibility,
but not yet coefficients, vendor-output parity, or physiological validity. Any
recovered algorithm must be implemented from documented behavior rather than
shipping the vendor binary, assigned an immutable derivation version, tested
against vendor-output golden vectors, and clearly distinguished from independent
clinical validation.

## Required next result

Before derived Health Connect records are enabled, establish an explicit algorithm
specification containing:

1. the exact equations and every numerical coefficient;
2. the primary source for each equation;
3. required input units and segment/frequency definitions;
4. calibration population, applicability limits, exclusions, and reported error;
5. an immutable algorithm/coefficient version; and
6. source-backed golden vectors.

The active target is an inspectable reimplementation of the exact QN scale-family
algorithm because no compatible modern published equation has been found. Recovery
must preserve coefficient-to-operand evidence and demonstrate parity against vendor
outputs; unlabeled constants and approximate clean-room formulas are insufficient.
Vendor parity is not independent clinical validation and must be identified as such
where results are presented. Outputs must fail explicitly outside their recovered
applicability rules and must never be clamped into plausible ranges.

## Health Connect target

The bridge must write every scientifically supported result for which Health Connect
has an appropriate record type. The expected set includes weight, body-fat
percentage, lean body mass, body-water mass, and basal metabolic rate when the
selected equations support them. Do not map vendor-only scores, body age, visceral
levels, or segmental estimates into unrelated Health Connect fields.
