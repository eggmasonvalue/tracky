"""Body-composition derivation (derivation version 2).

Reimplements the composition path of ``calcSpecialtyBodyData`` from the x86-64
``libyolanda_calc.so`` in the public ``YolandaQingniu/qnscalesdk`` repository
(commit c9ce0ce). Every coefficient is the exact float64 value of a binary128
``.rodata`` constant. ``context/DERIVATION.md`` records provenance, the native
pipeline, the input gates, and the trunk-order rule.

This is a vendor-compatible reimplementation, not independent clinical
validation. The function is pure: raw evidence goes in, a versioned result
comes out, so stored sessions can be re-derived whenever this module changes.
"""

from __future__ import annotations

import math
import tomllib
from dataclasses import dataclass
from enum import Enum
from os import PathLike
from typing import Final, Sequence

DERIVATION_VERSION: Final[int] = 2

_Bank = tuple[float, dict[str, float]]

# Canonical channel order, matching the native calculator's arguments.
CHANNELS: Final[tuple[str, ...]] = (
    "RH20", "LH20", "T20", "RF20", "LF20",
    "RH100", "LH100", "T100", "RF100", "LF100",
)
_LIMBS: Final[tuple[str, ...]] = ("RH", "LH", "RF", "LF")

# Wire layout: packet 0x21 = block A, packet 0x22 = block B, five words each.
# A = [LF20, LF100, RF20, RF100, LH20]; B = [LH100, RH20, RH100, T20, T100].
_BLOCK_WORD_FOR_CHANNEL: Final[tuple[tuple[str, int], ...]] = (
    ("B", 1), ("A", 4), ("B", 3), ("A", 2), ("A", 0),
    ("B", 2), ("B", 0), ("B", 4), ("A", 3), ("A", 1),
)
_OHMS_PER_WORD: Final[float] = 0.1
_MAX_OHMS: Final[float] = 700.0


_FAT_UNDERWEIGHT: Final[_Bank] = (
    -62.0719292073132, {
        "Height": 0.036839834257782, "Weight": 0.117346516934577,
        "BMI": 3.45595189922899,
        "Z_RH20": 0.0597823384076915, "Z_LH20": -0.0616480615352019,
        "Z_T20": -0.463379762695084, "Z_RF20": -0.186575010739835,
        "Z_LF20": 0.154847544230755,
        "Z_RH100": -0.0170962517342159, "Z_LH100": 0.0722113346676602,
        "Z_T100": 0.814030967044537, "Z_RF100": 0.206149677821911,
        "Z_LF100": -0.178536259008776,
        "LL_RH_20K": 0.791405620837228, "LL_T_20K": -0.0121626216579239,
        "LL_RF_20K": -0.0626974574944824, "LL_LF_20K": 0.865430740005401,
        "LL_RH_100K": -0.559457501452672, "LL_LH_100K": -0.135380460991728,
        "LL_T_100K": 0.00153436018138699, "LL_LF_100K": -0.833669634955733,
    },
)

_FAT_NORMAL: Final[_Bank] = (
    -84.332816512093, {
        "Height": 0.138551403224977, "Weight": -0.229638126411852,
        "BMI": 3.5380079792298,
        "Z_RH20": 0.0316047765299857, "Z_LH20": -0.0456856094717298,
        "Z_T20": -0.381011675527324, "Z_RF20": -0.16735090326332,
        "Z_LF20": -0.0877589672167205,
        "Z_RH100": 0.00260679672345121, "Z_LH100": 0.0730061552968283,
        "Z_T100": 0.876003772432261, "Z_RF100": 0.224774420521255,
        "Z_LF100": 0.0735371840926183,
        "LL_RH_20K": 0.541779102847987, "LL_T_20K": -0.0047991564060679,
        "LL_LF_20K": 0.162537864764221,
        "LL_RH_100K": -0.443024531997328, "LL_LH_100K": -0.0260945733658815,
        "LL_T_100K": -0.00163585952146194, "LL_RF_100K": 0.0389918890376868,
        "LL_LF_100K": -0.23721366452991,
    },
)

_FAT_OVERWEIGHT: Final[_Bank] = (
    -51.793305197286, {
        "Height": -0.0169194883077701, "Weight": -0.101817514694878,
        "BMI": 2.51905588455144,
        "Z_RH20": 0.140290549506976, "Z_LH20": -0.0435357324556074,
        "Z_T20": -0.104885740189388, "Z_RF20": 0.125732198644235,
        "Z_LF20": -0.348363448973619,
        "Z_RH100": -0.0900681924878712, "Z_LH100": 0.052816858496536,
        "Z_T100": 0.558860341050186, "Z_RF100": -0.111714031322363,
        "Z_LF100": 0.381046130361682,
        "LL_RH_20K": 0.736380305349612, "LL_T_20K": 0.00256324936451522,
        "LL_RF_20K": 0.512554241117436, "LL_LF_20K": -0.25456178296025,
        "LL_RH_100K": -0.544909559181151, "LL_LH_100K": -0.0892105113773864,
        "LL_T_100K": -0.00762367319464728, "LL_RF_100K": -0.441481841333243,
        "LL_LF_100K": 0.181677570558573,
    },
)

_FAT_OBESE: Final[_Bank] = (
    -46.7525373477897, {
        "Height": 0.445770045773053, "Weight": -0.216923234016041,
        "BMI": 1.87284418558527,
        "Z_RH20": -0.464444098327764, "Z_LH20": 0.298646058032808,
        "Z_T20": 0.000795515611502758, "Z_RF20": 0.338137304663717,
        "Z_LF20": -0.532486268981483,
        "Z_RH100": 0.345097223009627, "Z_LH100": -0.055754925061094,
        "Z_T100": -0.794653212666266, "Z_RF100": -0.328523953616789,
        "Z_LF100": 0.483203986537538,
        "LL_RH_20K": -0.482099548984409, "LL_T_20K": 0.0105183084635134,
        "LL_RF_20K": 0.0967161464479232, "LL_LF_20K": 0.0963700965982488,
        "LL_LH_100K": 0.496298279624006, "LL_T_100K": -0.0262851904508251,
        "LL_LF_100K": -0.260727801265288,
    },
)


_WATER_UNDERWEIGHT: Final[_Bank] = (
    -17.2019751630188, {
        "Height": 0.19986095594563,
        "Z_RH20": 0.0528147447835995,
        "Z_LH20": 0.0298304727535372,
        "Z_T20": 0.102553081115273,
        "Z_RF20": 0.0139541953439101,
        "Z_LF20": -0.00665806388633959,
        "Z_RH100": -0.0382726442489652,
        "Z_LH100": -0.046820442061218,
        "Z_T100": -0.270190722267035,
        "Z_RF100": -0.0234303740794645,
        "Z_LF100": 0.0130747121319431,
        "LL_RH_20K": 0.277056544339893,
        "LL_T_20K": 0.00279954217622145,
        "LL_RF_20K": -0.284063105337713,
        "LL_RH_100K": -0.114465506185748,
        "LL_LH_100K": 0.00677043481216278,
        "LL_T_100K": -0.000369852584584975,
        "LL_RF_100K": 0.251013060498391,
        "LL_LF_100K": 0.039324381864712,
    },
)

_WATER_NORMAL: Final[_Bank] = (
    -24.4714664096621, {
        "Height": 0.252358087364141,
        "Z_RH20": -0.0270261400299406,
        "Z_LH20": 0.0333052578687033,
        "Z_T20": 0.339914149592619,
        "Z_RF20": 0.0755396334541697,
        "Z_LF20": -0.136126321235517,
        "Z_RH100": 0.000637057939651814,
        "Z_LH100": -0.0171634200692027,
        "Z_T100": -0.35939883718008,
        "Z_RF100": -0.0937636072780273,
        "Z_LF100": 0.163184865566457,
        "LL_RH_20K": -0.270273795720051,
        "LL_T_20K": 0.00621330530081532,
        "LL_LF_20K": -0.49972144051281,
        "LL_RH_100K": 0.160391284350859,
        "LL_LH_100K": 0.15705337068528,
        "LL_T_100K": 0.000534159644291057,
        "LL_RF_100K": -0.0163514983774457,
        "LL_LF_100K": 0.506226268865202,
    },
)

_WATER_OVERWEIGHT: Final[_Bank] = (
    -32.6700536350634, {
        "Height": 0.260736216377311,
        "Z_RH20": 0.0184242378546057,
        "Z_LH20": 0.0502161465590932,
        "Z_T20": 0.470033483611567,
        "Z_RF20": 0.0464433221641726,
        "Z_LF20": -0.0226024154899807,
        "Z_RH100": -0.0508850128202693,
        "Z_LH100": -0.0425886097574286,
        "Z_T100": -0.376396293313435,
        "Z_RF100": -0.0881274821191157,
        "Z_LF100": 0.0741058661962872,
        "LL_RH_20K": -0.136136068057864,
        "LL_T_20K": 0.0125172115025401,
        "LL_RF_20K": 0.182714498870045,
        "LL_LF_20K": -0.421681951868532,
        "LL_RH_100K": 0.0835445466547522,
        "LL_LH_100K": 0.0930190112105635,
        "LL_T_100K": -0.00113083309876114,
        "LL_RF_100K": -0.196775924870053,
        "LL_LF_100K": 0.468496511566397,
    },
)

_WATER_OBESE: Final[_Bank] = (
    -37.2688396773075, {
        "Height": 0.185405565137595,
        "Z_RH20": 0.22008578603782,
        "Z_LH20": -0.10366239299542,
        "Z_T20": -1.29814127844573,
        "Z_RF20": 0.236083111820356,
        "Z_LF20": -0.0818387055221357,
        "Z_RH100": -0.264244070587425,
        "Z_LH100": 0.188531342874094,
        "Z_T100": 0.108677082054683,
        "Z_RF100": -0.261097795910022,
        "Z_LF100": 0.183055799765455,
        "LL_RH_20K": -0.00824240946343724,
        "LL_T_20K": -0.0188847166107097,
        "LL_RF_20K": 0.0591357651290047,
        "LL_LF_20K": 0.00927406568232807,
        "LL_LH_100K": 0.257189684176895,
        "LL_T_100K": 0.00630353270829029,
        "LL_LF_100K": 0.147519989352237,
    },
)

_PROTEIN: Final[_Bank] = (
    1.07165063294088, {
        "Height": 0.0159702367562896, "Weight": 0.0773157613652759,
        "BMI": -0.151451960940454,
        "Z_RH20": 0.0180429035273242, "Z_LH20": -0.0220426266612121,
        "Z_T20": 0.109061937383163, "Z_RF20": -0.000999172121795203,
        "Z_LF20": 0.00870113037731865,
        "Z_RH100": -0.0179828526309123, "Z_LH100": 0.0211076710629566,
        "Z_T100": -0.124370930418956, "Z_RF100": -0.00532923513490021,
        "Z_LF100": -0.00393843655735424,
        "LL_RH_20K": 0.0225315689041326, "LL_LH_20K": -0.143851226632338,
        "LL_T_20K": 0.00131882338128034, "LL_RF_20K": -0.0116648941094905,
        "LL_LF_20K": -0.0817753760274064,
        "LL_LH_100K": 0.133848531835613, "LL_T_100K": 0.000252540999405805,
        "LL_LF_100K": 0.090438126630511,
    },
)

_SKELETAL_MUSCLE: Final[_Bank] = (
    0.63206395940795, {
        "Height": 0.051036467315893, "Weight": 0.236860359732721,
        "BMI": -0.470722275662929,
        "Z_RH20": 0.0411350401147831, "Z_LH20": -0.0503462809653475,
        "Z_T20": 0.310599852501293, "Z_RF20": -0.0017898609678593,
        "Z_LF20": 0.0181092601577931,
        "Z_RH100": -0.0433891795451644, "Z_LH100": 0.049510057273899,
        "Z_T100": -0.356764255601546, "Z_RF100": -0.0134391496272278,
        "Z_LF100": -0.00820980883043372,
        "LL_RH_20K": 0.0559418855214566, "LL_LH_20K": -0.410390268396024,
        "LL_T_20K": 0.00355213309084269, "LL_RF_20K": -0.0246853665587738,
        "LL_LF_20K": -0.274331162001714,
        "LL_LH_100K": 0.391830240595914, "LL_T_100K": 0.00101901758486269,
        "LL_LF_100K": 0.287735234938504,
    },
)

_BMI_UNDERWEIGHT: Final[float] = 19.0
_BMI_NORMAL_UPPER: Final[float] = 24.0
_BMI_OVERWEIGHT_UPPER: Final[float] = 31.0
_BONE_LOWER_FRACTION: Final[float] = 0.039
_BONE_UPPER_FRACTION: Final[float] = 0.051


class Unavailable(str, Enum):
    """Why a session has no composition. Values are contract codes."""

    NO_WEIGHT = "no_weight"
    NO_IMPEDANCE = "no_impedance"
    IMPEDANCE_OUT_OF_RANGE = "impedance_out_of_range"
    LIMB_FREQUENCY_INVERSION = "limb_frequency_inversion"
    NO_PROFILE = "no_profile"
    OUTSIDE_MODEL_DOMAIN = "outside_model_domain"


@dataclass(frozen=True, slots=True)
class Profile:
    """The single user's profile. Only height enters the composition path."""

    height_cm: float

    def __post_init__(self) -> None:
        if not (math.isfinite(self.height_cm) and 40.0 <= self.height_cm <= 240.0):
            raise ValueError("height_cm must be within [40, 240]")


def load_profile(path: str | PathLike[str]) -> Profile:
    with open(path, "rb") as handle:
        data = tomllib.load(handle)
    return Profile(height_cm=float(data["height_cm"]))


@dataclass(frozen=True, slots=True)
class Composition:
    derivation_version: int
    trunk_order: str  # "wire" or "transposed"
    height_cm: float
    body_fat_pct: float
    lean_body_mass_kg: float
    body_water_kg: float
    bone_mass_kg: float
    protein_kg: float
    skeletal_muscle_kg: float
    basal_metabolic_rate_kcal_per_day: int


def impedances_from_blocks(block_a: Sequence[int], block_b: Sequence[int]) -> tuple[float, ...]:
    """Map the two opaque wire blocks to ohms in canonical channel order."""

    if len(block_a) != 5 or len(block_b) != 5:
        raise ValueError("each block must contain exactly five words")
    blocks = {"A": block_a, "B": block_b}
    return tuple(blocks[b][i] * _OHMS_PER_WORD for b, i in _BLOCK_WORD_FOR_CHANNEL)


def derive(
    weight_kg: float | None,
    impedances: Sequence[float] | None,
    profile: Profile | None,
) -> Composition | Unavailable:
    """Derive composition, or explain why none exists. Never clamps outputs."""

    if weight_kg is None or not (math.isfinite(weight_kg) and weight_kg > 0.0):
        return Unavailable.NO_WEIGHT
    if impedances is None or len(impedances) != len(CHANNELS) or not any(impedances):
        return Unavailable.NO_IMPEDANCE
    z = dict(zip(CHANNELS, impedances, strict=True))
    if not all(math.isfinite(v) and 0.0 < v <= _MAX_OHMS for v in z.values()):
        return Unavailable.IMPEDANCE_OUT_OF_RANGE
    # Tissue impedance magnitude cannot rise with frequency.
    if any(z[f"{limb}20"] < z[f"{limb}100"] for limb in _LIMBS):
        return Unavailable.LIMB_FREQUENCY_INVERSION
    trunk_order = "wire"
    if z["T20"] < z["T100"]:
        # With every limb physically ordered, an inverted trunk is a transposed pair.
        z["T20"], z["T100"] = z["T100"], z["T20"]
        trunk_order = "transposed"
    if profile is None:
        return Unavailable.NO_PROFILE

    raw = _regressions(profile.height_cm, weight_kg, z)
    if raw is None:
        return Unavailable.OUTSIDE_MODEL_DOMAIN
    fat_pct, water, protein, skeletal_muscle = raw

    fat_mass = weight_kg * fat_pct / 100.0
    lean = weight_kg - fat_mass
    bone_residual = lean - water - protein
    bone = min(
        max(bone_residual, _BONE_LOWER_FRACTION * weight_kg),
        _BONE_UPPER_FRACTION * weight_kg,
    )
    correction = (bone_residual - bone) / 2.0
    water += correction
    protein += correction

    # The native calculator rejects F outside (0, 75) and clamps displayed values;
    # this reimplementation rejects every value the native code would clamp.
    if not (
        2.1 <= fat_pct <= 70.0
        and 10.0 <= 100.0 * water / weight_kg <= 80.0
        and 100.0 * protein / weight_kg >= 5.0
        and 0.0 < skeletal_muscle < lean
    ):
        return Unavailable.OUTSIDE_MODEL_DOMAIN

    return Composition(
        derivation_version=DERIVATION_VERSION,
        trunk_order=trunk_order,
        height_cm=profile.height_cm,
        body_fat_pct=_vendor_round(fat_pct, 1),
        lean_body_mass_kg=_vendor_round(lean, 1),
        body_water_kg=_vendor_round(water, 2),
        bone_mass_kg=_vendor_round(bone, 2),
        protein_kg=_vendor_round(protein, 2),
        skeletal_muscle_kg=_vendor_round(skeletal_muscle, 2),
        basal_metabolic_rate_kcal_per_day=math.trunc(370.0 + 21.6 * lean),
    )


def _regressions(
    height: float, weight: float, z: dict[str, float]
) -> tuple[float, float, float, float] | None:
    """Raw native regressions: fat %, water kg, protein kg, skeletal muscle kg."""

    bmi = weight / (height / 100.0) ** 2
    features = {"Height": height, "Weight": weight, "BMI": bmi}
    for channel, ohms in z.items():
        features[f"Z_{channel}"] = ohms
        segment = channel.rstrip("0123456789")
        features[f"LL_{segment}_{channel[len(segment):]}K"] = height * height / ohms

    if bmi < _BMI_UNDERWEIGHT:
        fat_bank, water_bank = _FAT_UNDERWEIGHT, _WATER_UNDERWEIGHT
    elif bmi < _BMI_NORMAL_UPPER:
        fat_bank, water_bank = _FAT_NORMAL, _WATER_NORMAL
    elif bmi < _BMI_OVERWEIGHT_UPPER:
        fat_bank, water_bank = _FAT_OVERWEIGHT, _WATER_OVERWEIGHT
    else:
        fat_bank, water_bank = _FAT_OBESE, _WATER_OBESE

    values = tuple(
        _evaluate(bank, features)
        for bank in (fat_bank, water_bank, _PROTEIN, _SKELETAL_MUSCLE)
    )
    if not all(math.isfinite(v) for v in values) or not 0.0 < values[0] < 75.0:
        return None
    return values  # type: ignore[return-value]


def _evaluate(bank: _Bank, features: dict[str, float]) -> float:
    intercept, coefficients = bank
    return intercept + sum(c * features[term] for term, c in coefficients.items())


def _vendor_round(value: float, places: int) -> float:
    """Native rounding: add a half step (with a small epsilon), then truncate."""

    scale = 10**places
    return math.trunc(value * scale + 0.5 + 1e-7) / scale
