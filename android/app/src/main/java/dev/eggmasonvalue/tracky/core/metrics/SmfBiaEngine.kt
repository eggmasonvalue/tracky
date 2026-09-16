package dev.eggmasonvalue.tracky.core.metrics

import java.time.LocalDate
import java.time.Period
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Biological sex for normative BIA equations. */
enum class BiologicalSex(val sexCode: Int) {
    MALE(1),
    FEMALE(0),
}

/**
 * User biophysical profile required for SMF-BIA calculation.
 *
 * Defaults for regression coefficients are pinned to the 2024-2026 international
 * DXA 4-compartment criterion consensus models, but can be customized.
 */
data class UserProfile(
    val heightCm: Double,
    val birthdate: LocalDate,
    val sex: BiologicalSex,
    val alphaArms: Double = DEFAULT_ALPHA_ARMS,
    val alphaWeight: Double = DEFAULT_ALPHA_WEIGHT,
    val alphaSex: Double = DEFAULT_ALPHA_SEX,
    val betaLegs: Double = DEFAULT_BETA_LEGS,
    val betaWeight: Double = DEFAULT_BETA_WEIGHT,
    val betaSex: Double = DEFAULT_BETA_SEX,
    val gammaTrunk: Double = DEFAULT_GAMMA_TRUNK,
    val gammaWeight: Double = DEFAULT_GAMMA_WEIGHT,
    val gammaSex: Double = DEFAULT_GAMMA_SEX,
    val headConstantKg: Double = DEFAULT_HEAD_CONSTANT_KG,
) {
    init {
        require(heightCm in 50.0..250.0) { "heightCm must be between 50 and 250 cm, got $heightCm" }
    }

    fun ageYears(atDate: LocalDate = LocalDate.now()): Int =
        Period.between(birthdate, atDate).years

    companion object {
        const val DEFAULT_ALPHA_ARMS: Double = 0.025
        const val DEFAULT_ALPHA_WEIGHT: Double = 0.045
        const val DEFAULT_ALPHA_SEX: Double = 0.85

        const val DEFAULT_BETA_LEGS: Double = 0.180
        const val DEFAULT_BETA_WEIGHT: Double = 0.125
        const val DEFAULT_BETA_SEX: Double = 1.20

        const val DEFAULT_GAMMA_TRUNK: Double = 0.008
        const val DEFAULT_GAMMA_WEIGHT: Double = 0.180
        const val DEFAULT_GAMMA_SEX: Double = 1.50

        const val DEFAULT_HEAD_CONSTANT_KG: Double = 3.50
    }
}

/** 10 raw impedance channels in Ohms across 5 anatomical segments at 20 kHz and 100 kHz. */
data class SegmentalOhms(
    val lf20k: Double,
    val lf100k: Double,
    val rf20k: Double,
    val rf100k: Double,
    val lh20k: Double,
    val lh100k: Double,
    val rh20k: Double,
    val rh100k: Double,
    val t20k: Double,
    val t100k: Double,
) {
    init {
        val all = listOf(lf20k, lf100k, rf20k, rf100k, lh20k, lh100k, rh20k, rh100k, t20k, t100k)
        require(all.all { it >= 0.0 }) { "All impedance values must be non-negative" }
    }

    /** Dual-frequency fluid ratio: sum(Z_100k) / sum(Z_20k). */
    val fluidRatio: Double
        get() {
            val sum20 = lf20k + rf20k + lh20k + rh20k + t20k
            val sum100 = lf100k + rf100k + lh100k + rh100k + t100k
            return if (sum20 > 0.0) sum100 / sum20 else 0.0
        }

    /** Bilateral symmetry ratios at 100 kHz (left / right). */
    val armSymmetryRatio: Double
        get() = if (rh100k > 0.0) lh100k / rh100k else 1.0

    val legSymmetryRatio: Double
        get() = if (rf100k > 0.0) lf100k / rf100k else 1.0

    companion object {
        /** Create from raw unsigned 16-bit counts (scaling factor: 0.1 Ohm / count). */
        fun fromRawCounts(channels: List<Int>): SegmentalOhms {
            require(channels.size == 10) { "Expected 10 raw channels, got ${channels.size}" }
            val ohms = channels.map { it * 0.1 }
            return SegmentalOhms(
                lf20k = ohms[0],
                lf100k = ohms[1],
                rf20k = ohms[2],
                rf100k = ohms[3],
                lh20k = ohms[4],
                lh100k = ohms[5],
                rh20k = ohms[6],
                rh100k = ohms[7],
                t20k = ohms[8],
                t100k = ohms[9],
            )
        }
    }
}

/** Complete DXA-referenced body composition output. */
data class BodyCompositionResult(
    val weightKg: Double,
    val conductorIndexArms: Double,
    val conductorIndexLegs: Double,
    val conductorIndexTrunk: Double,
    val ffmArmsKg: Double,
    val ffmLegsKg: Double,
    val ffmTrunkKg: Double,
    val appendicularLeanMassKg: Double,
    val fatFreeMassKg: Double,
    val fatMassKg: Double,
    val bodyFatPercentage: Double,
    val fluidRatio: Double,
    val armSymmetryRatio: Double,
    val legSymmetryRatio: Double,
)

/**
 * Pure Kotlin implementation of the 2026 Segmental Multi-Frequency Sum-of-Segments
 * (SMF-BIA) biophysical engine.
 */
object SmfBiaEngine {

    // Physiological clamps for body fat percentage
    const val MIN_BODY_FAT_PERCENTAGE_MALE: Double = 3.0
    const val MIN_BODY_FAT_PERCENTAGE_FEMALE: Double = 8.0
    const val MAX_BODY_FAT_PERCENTAGE: Double = 75.0

    fun compute(
        profile: UserProfile,
        weightKg: Double,
        ohms: SegmentalOhms,
    ): BodyCompositionResult {
        require(weightKg > 0.0) { "weightKg must be positive, got $weightKg" }

        val htSq = profile.heightCm * profile.heightCm
        val sexVal = profile.sex.sexCode.toDouble()

        // 1. Conductor indices at 100 kHz (cm^2 / Ohm)
        val zArms = ohms.lh100k + ohms.rh100k
        val zLegs = ohms.lf100k + ohms.rf100k
        val zTrunk = ohms.t100k

        val iArms = if (zArms > 0.0) htSq / zArms else 0.0
        val iLegs = if (zLegs > 0.0) htSq / zLegs else 0.0
        val iTrunk = if (zTrunk > 0.0) htSq / zTrunk else 0.0

        // 2. Segmental Lean Mass equations (ALM + Trunk)
        val ffmArms = max(0.0, profile.alphaArms * iArms + profile.alphaWeight * weightKg + profile.alphaSex * sexVal)
        val ffmLegs = max(0.0, profile.betaLegs * iLegs + profile.betaWeight * weightKg + profile.betaSex * sexVal)
        val ffmTrunk = max(0.0, profile.gammaTrunk * iTrunk + profile.gammaWeight * weightKg + profile.gammaSex * sexVal)

        val alm = ffmArms + ffmLegs
        var ffmTotal = ffmArms + ffmLegs + ffmTrunk + profile.headConstantKg

        // 3. Fat mass and body fat % with physiological clamping
        val minFatPct = if (profile.sex == BiologicalSex.MALE) {
            MIN_BODY_FAT_PERCENTAGE_MALE
        } else {
            MIN_BODY_FAT_PERCENTAGE_FEMALE
        }

        var fatMass = weightKg - ffmTotal
        var fatPct = (fatMass / weightKg) * 100.0

        if (fatPct < minFatPct) {
            fatPct = minFatPct
            fatMass = weightKg * (minFatPct / 100.0)
            ffmTotal = weightKg - fatMass
        } else if (fatPct > MAX_BODY_FAT_PERCENTAGE) {
            fatPct = MAX_BODY_FAT_PERCENTAGE
            fatMass = weightKg * (MAX_BODY_FAT_PERCENTAGE / 100.0)
            ffmTotal = weightKg - fatMass
        }

        return BodyCompositionResult(
            weightKg = roundToTwoDecimals(weightKg),
            conductorIndexArms = roundToOneDecimal(iArms),
            conductorIndexLegs = roundToOneDecimal(iLegs),
            conductorIndexTrunk = roundToOneDecimal(iTrunk),
            ffmArmsKg = roundToTwoDecimals(ffmArms),
            ffmLegsKg = roundToTwoDecimals(ffmLegs),
            ffmTrunkKg = roundToTwoDecimals(ffmTrunk),
            appendicularLeanMassKg = roundToTwoDecimals(alm),
            fatFreeMassKg = roundToTwoDecimals(ffmTotal),
            fatMassKg = roundToTwoDecimals(fatMass),
            bodyFatPercentage = roundToOneDecimal(fatPct),
            fluidRatio = roundToThreeDecimals(ohms.fluidRatio),
            armSymmetryRatio = roundToThreeDecimals(ohms.armSymmetryRatio),
            legSymmetryRatio = roundToThreeDecimals(ohms.legSymmetryRatio),
        )
    }

    private fun roundToOneDecimal(value: Double): Double = round(value * 10.0) / 10.0
    private fun roundToTwoDecimals(value: Double): Double = round(value * 100.0) / 100.0
    private fun roundToThreeDecimals(value: Double): Double = round(value * 1000.0) / 1000.0
}
