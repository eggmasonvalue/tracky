"""Derivation version 2 against native ``libyolanda_calc.so`` outputs.

Expected values were produced by running the pinned x86-64 vendor library
(qnscalesdk c9ce0ce, SHA-256 8164af7c...d7c0) through a glibc harness. The
synthetic vectors cover every BMI bank. Native water and protein are percentages of
body weight, so they are compared after the same conversion.
"""

from __future__ import annotations

import pytest

from tracky_mcp.derivation import (
    CHANNELS,
    DERIVATION_VERSION,
    Composition,
    Profile,
    Unavailable,
    _regressions,
    derive,
    impedances_from_blocks,
    load_profile,
)

# name: (height, weight, impedances in CHANNELS order, native raw regressions
#        (fat %, water kg, protein kg, skeletal muscle kg),
#        native outputs (fat %, lbm kg, water %, protein %, bone kg, bmr))
NATIVE = {
    "underweight": (
        165.0, 48.0, (520, 530, 40, 500, 510, 440, 450, 33, 420, 430),
        (19.3988939050716, 28.4148694127745, 8.01740931120436, 20.8761944852889),
        (19.4, 38.7, 59.2, 16.7, 2.26, 1205),
    ),
    "normal": (
        170.0, 70.0, (450, 460, 30, 420, 430, 380, 390, 25, 355, 365),
        (31.4548210580573, 37.9029668201336, 9.81586115971871, 26.4172003303308),
        (31.5, 48.0, 52.4, 12.3, 2.73, 1406),
    ),
    "overweight": (
        175.0, 90.0, (400, 410, 35, 380, 390, 340, 350, 28, 320, 330),
        (39.4541582175158, 40.9253542346714, 11.3241168481831, 30.9978803601697),
        (39.5, 54.5, 44.8, 11.9, 3.51, 1547),
    ),
    "obese": (
        160.0, 100.0, (350, 360, 25, 330, 340, 300, 310, 20, 280, 290),
        (44.6390170629928, 38.7350126068866, 10.2710345288698, 27.7900410083562),
        (44.6, 55.4, 39.4, 10.9, 5.1, 1565),
    ),
}


@pytest.mark.parametrize("name", NATIVE)
def test_raw_regressions_match_native(name: str) -> None:
    height, weight, z, raw, _ = NATIVE[name]
    ours = _regressions(height, weight, dict(zip(CHANNELS, map(float, z))))
    assert ours == pytest.approx(raw, rel=1e-12)


@pytest.mark.parametrize("name", NATIVE)
def test_outputs_match_native(name: str) -> None:
    height, weight, z, _, (fat, lbm, water_pct, protein_pct, bone, bmr) = NATIVE[name]
    result = derive(weight, z, Profile(height))
    assert isinstance(result, Composition)
    assert result.derivation_version == DERIVATION_VERSION
    assert result.trunk_order == "wire"
    assert result.body_fat_pct == fat
    assert result.lean_body_mass_kg == lbm
    assert round(100 * result.body_water_kg / weight, 1) == water_pct
    assert round(100 * result.protein_kg / weight, 1) == protein_pct
    assert result.bone_mass_kg == bone
    assert result.basal_metabolic_rate_kcal_per_day == bmr


def test_wire_blocks_map_to_channels_and_inverted_trunk_is_transposed() -> None:
    # The "normal" vector on the wire, with the trunk pair arriving inverted.
    z = impedances_from_blocks([4300, 3650, 4200, 3550, 4600], [3900, 4500, 3800, 250, 300])
    assert z == pytest.approx((450, 460, 25, 420, 430, 380, 390, 30, 355, 365))
    result = derive(70.0, z, Profile(170.0))
    assert isinstance(result, Composition)
    assert result.trunk_order == "transposed"
    assert result.body_fat_pct == 31.5
    assert result.basal_metabolic_rate_kcal_per_day == 1406


def test_inverted_limb_is_rejected_not_repaired() -> None:
    z = list(NATIVE["normal"][2])
    z[0], z[5] = z[5], z[0]  # RH20 < RH100
    assert derive(70.0, z, Profile(170.0)) is Unavailable.LIMB_FREQUENCY_INVERSION


@pytest.mark.parametrize(
    ("weight", "z", "profile", "reason"),
    [
        (None, NATIVE["normal"][2], Profile(170.0), Unavailable.NO_WEIGHT),
        (70.0, None, Profile(170.0), Unavailable.NO_IMPEDANCE),
        (70.0, (0,) * 10, Profile(170.0), Unavailable.NO_IMPEDANCE),
        (70.0, (450,) * 9 + (0,), Profile(170.0), Unavailable.IMPEDANCE_OUT_OF_RANGE),
        (70.0, (701,) * 10, Profile(170.0), Unavailable.IMPEDANCE_OUT_OF_RANGE),
        (70.0, NATIVE["normal"][2], None, Unavailable.NO_PROFILE),
        (250.0, NATIVE["normal"][2], Profile(170.0), Unavailable.OUTSIDE_MODEL_DOMAIN),
    ],
)
def test_unavailable_reasons(weight, z, profile, reason) -> None:
    assert derive(weight, z, profile) is reason


def test_profile_requires_plausible_height(tmp_path) -> None:
    path = tmp_path / "profile.toml"
    path.write_text("height_cm = 170.0\n", encoding="utf-8")
    assert load_profile(path) == Profile(170.0)
    with pytest.raises(ValueError):
        Profile(12.0)
