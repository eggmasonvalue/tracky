package dev.eggmasonvalue.tracky.core.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SmfBiaEngineTest {

    private val maleProfile = UserProfile(
        heightCm = 178.0,
        birthdate = LocalDate.of(1992, 5, 15),
        sex = BiologicalSex.MALE,
    )

    private val femaleProfile = UserProfile(
        heightCm = 165.0,
        birthdate = LocalDate.of(1996, 8, 20),
        sex = BiologicalSex.FEMALE,
    )

    // Textbook physiological impedance readings from scale capture
    // (~320-350 Ohm limbs with ~14% frequency drop, ~20 Ohm trunk)
    private val standardOhms = SegmentalOhms(
        lf20k = 350.0,
        lf100k = 305.0,
        rf20k = 355.0,
        rf100k = 308.0,
        lh20k = 360.0,
        lh100k = 310.0,
        rh20k = 365.0,
        rh100k = 312.0,
        t20k = 25.0,
        t100k = 21.0,
    )

    @Test
    fun standardMaleCalculation_producesPhysiologicalResults() {
        val weightKg = 78.5
        val result = SmfBiaEngine.compute(maleProfile, weightKg, standardOhms)

        assertEquals(78.5, result.weightKg, 0.01)
        assertTrue("Conductor arms should be positive", result.conductorIndexArms > 40.0)
        assertTrue("Conductor legs should be positive", result.conductorIndexLegs > 40.0)
        assertTrue("Conductor trunk should be positive", result.conductorIndexTrunk > 1000.0)

        // For a 78.5 kg, 178 cm male: FFM ~55-65 kg, body fat ~18-28%
        assertTrue("FFM should be in 50-65 kg range, got ${result.fatFreeMassKg}", result.fatFreeMassKg in 50.0..65.0)
        assertTrue("Fat mass should be in 12-28 kg range, got ${result.fatMassKg}", result.fatMassKg in 12.0..28.0)
        assertTrue("Body fat % should be in 15-32% range, got ${result.bodyFatPercentage}", result.bodyFatPercentage in 15.0..32.0)

        // Fluid ratio at standard values: (305+308+310+312+21) / (350+355+360+365+25) = 1256 / 1455 = 0.863
        assertEquals(0.863, result.fluidRatio, 0.005)
        assertEquals(0.994, result.armSymmetryRatio, 0.005)
        assertEquals(0.990, result.legSymmetryRatio, 0.005)
    }

    @Test
    fun standardFemaleCalculation_reflectsFemaleNorms() {
        val weightKg = 62.0
        val result = SmfBiaEngine.compute(femaleProfile, weightKg, standardOhms)

        assertEquals(62.0, result.weightKg, 0.01)
        assertTrue("FFM should be in 38-52 kg range, got ${result.fatFreeMassKg}", result.fatFreeMassKg in 38.0..52.0)
        assertTrue("Body fat % should be in 20-38% range, got ${result.bodyFatPercentage}", result.bodyFatPercentage in 20.0..38.0)
    }

    @Test
    fun lowerBoundClamp_clampsExcessiveLeanMassToPhysiologicalFloor() {
        // Unusually low impedance produces huge conductor indices and extreme FFM
        val superAthleteOhms = SegmentalOhms(
            lf20k = 150.0,
            lf100k = 120.0,
            rf20k = 150.0,
            rf100k = 120.0,
            lh20k = 150.0,
            lh100k = 120.0,
            rh20k = 150.0,
            rh100k = 120.0,
            t20k = 10.0,
            t100k = 8.0,
        )
        val maleResult = SmfBiaEngine.compute(maleProfile, 75.0, superAthleteOhms)
        assertEquals(SmfBiaEngine.MIN_BODY_FAT_PERCENTAGE_MALE, maleResult.bodyFatPercentage, 0.01)
        assertEquals(75.0 * 0.03, maleResult.fatMassKg, 0.01)
        assertEquals(75.0 * 0.97, maleResult.fatFreeMassKg, 0.01)

        val femaleResult = SmfBiaEngine.compute(femaleProfile, 60.0, superAthleteOhms)
        assertEquals(SmfBiaEngine.MIN_BODY_FAT_PERCENTAGE_FEMALE, femaleResult.bodyFatPercentage, 0.01)
        assertEquals(60.0 * 0.08, femaleResult.fatMassKg, 0.01)
    }

    @Test
    fun upperBoundClamp_clampsExcessiveImpedanceToPhysiologicalCeiling() {
        // Very high weight with minimal conductor contribution to trigger >75% ceiling
        val minimalConductorProfile = maleProfile.copy(
            alphaWeight = 0.01,
            betaWeight = 0.01,
            gammaWeight = 0.01,
            alphaSex = 0.0,
            betaSex = 0.0,
            gammaSex = 0.0,
            headConstantKg = 0.0,
        )
        val highImpedance = SegmentalOhms(
            lf20k = 5000.0,
            lf100k = 5000.0,
            rf20k = 5000.0,
            rf100k = 5000.0,
            lh20k = 5000.0,
            lh100k = 5000.0,
            rh20k = 5000.0,
            rh100k = 5000.0,
            t20k = 1000.0,
            t100k = 1000.0,
        )
        val result = SmfBiaEngine.compute(minimalConductorProfile, 120.0, highImpedance)
        assertEquals(SmfBiaEngine.MAX_BODY_FAT_PERCENTAGE, result.bodyFatPercentage, 0.01)
        assertEquals(120.0 * 0.75, result.fatMassKg, 0.01)
    }

    @Test
    fun fromRawCounts_scalesIntegersByPointOne() {
        val raw = listOf(3500, 3050, 3550, 3080, 3600, 3100, 3650, 3120, 250, 210)
        val ohms = SegmentalOhms.fromRawCounts(raw)

        assertEquals(350.0, ohms.lf20k, 0.01)
        assertEquals(305.0, ohms.lf100k, 0.01)
        assertEquals(21.0, ohms.t100k, 0.01)
    }
}
