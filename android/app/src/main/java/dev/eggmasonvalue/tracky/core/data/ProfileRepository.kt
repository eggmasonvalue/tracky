package dev.eggmasonvalue.tracky.core.data

import android.content.Context
import android.content.SharedPreferences
import dev.eggmasonvalue.tracky.core.metrics.BiologicalSex
import dev.eggmasonvalue.tracky.core.metrics.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Manages local user profile storage (Height, Birthday, Biological Sex).
 * Keeps profile private to this device; never transmitted to scale or cloud.
 */
class ProfileRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _profileState = MutableStateFlow(loadProfile())
    val profile: StateFlow<UserProfile> = _profileState.asStateFlow()

    fun getProfile(): UserProfile = _profileState.value

    fun saveProfile(profile: UserProfile) {
        prefs.edit()
            .putFloat(KEY_HEIGHT_CM, profile.heightCm.toFloat())
            .putLong(KEY_BIRTHDATE_EPOCH_DAY, profile.birthdate.toEpochDay())
            .putString(KEY_SEX, profile.sex.name)
            .putFloat(KEY_ALPHA_ARMS, profile.alphaArms.toFloat())
            .putFloat(KEY_ALPHA_WEIGHT, profile.alphaWeight.toFloat())
            .putFloat(KEY_ALPHA_SEX, profile.alphaSex.toFloat())
            .putFloat(KEY_BETA_LEGS, profile.betaLegs.toFloat())
            .putFloat(KEY_BETA_WEIGHT, profile.betaWeight.toFloat())
            .putFloat(KEY_BETA_SEX, profile.betaSex.toFloat())
            .putFloat(KEY_GAMMA_TRUNK, profile.gammaTrunk.toFloat())
            .putFloat(KEY_GAMMA_WEIGHT, profile.gammaWeight.toFloat())
            .putFloat(KEY_GAMMA_SEX, profile.gammaSex.toFloat())
            .putFloat(KEY_HEAD_CONSTANT, profile.headConstantKg.toFloat())
            .apply()
        _profileState.value = profile
    }

    private fun loadProfile(): UserProfile {
        val height = prefs.getFloat(KEY_HEIGHT_CM, 175.0f).toDouble()
        val epochDay = prefs.getLong(KEY_BIRTHDATE_EPOCH_DAY, LocalDate.of(1995, 1, 1).toEpochDay())
        val sexName = prefs.getString(KEY_SEX, BiologicalSex.MALE.name) ?: BiologicalSex.MALE.name
        val sex = try {
            BiologicalSex.valueOf(sexName)
        } catch (e: Exception) {
            BiologicalSex.MALE
        }

        return UserProfile(
            heightCm = height,
            birthdate = LocalDate.ofEpochDay(epochDay),
            sex = sex,
            alphaArms = prefs.getFloat(KEY_ALPHA_ARMS, UserProfile.DEFAULT_ALPHA_ARMS.toFloat()).toDouble(),
            alphaWeight = prefs.getFloat(KEY_ALPHA_WEIGHT, UserProfile.DEFAULT_ALPHA_WEIGHT.toFloat()).toDouble(),
            alphaSex = prefs.getFloat(KEY_ALPHA_SEX, UserProfile.DEFAULT_ALPHA_SEX.toFloat()).toDouble(),
            betaLegs = prefs.getFloat(KEY_BETA_LEGS, UserProfile.DEFAULT_BETA_LEGS.toFloat()).toDouble(),
            betaWeight = prefs.getFloat(KEY_BETA_WEIGHT, UserProfile.DEFAULT_BETA_WEIGHT.toFloat()).toDouble(),
            betaSex = prefs.getFloat(KEY_BETA_SEX, UserProfile.DEFAULT_BETA_SEX.toFloat()).toDouble(),
            gammaTrunk = prefs.getFloat(KEY_GAMMA_TRUNK, UserProfile.DEFAULT_GAMMA_TRUNK.toFloat()).toDouble(),
            gammaWeight = prefs.getFloat(KEY_GAMMA_WEIGHT, UserProfile.DEFAULT_GAMMA_WEIGHT.toFloat()).toDouble(),
            gammaSex = prefs.getFloat(KEY_GAMMA_SEX, UserProfile.DEFAULT_GAMMA_SEX.toFloat()).toDouble(),
            headConstantKg = prefs.getFloat(KEY_HEAD_CONSTANT, UserProfile.DEFAULT_HEAD_CONSTANT_KG.toFloat()).toDouble(),
        )
    }

    companion object {
        private const val PREFS_NAME = "tracky_user_profile"
        private const val KEY_HEIGHT_CM = "profile_height_cm"
        private const val KEY_BIRTHDATE_EPOCH_DAY = "profile_birthdate_epoch_day"
        private const val KEY_SEX = "profile_sex"

        private const val KEY_ALPHA_ARMS = "coeff_alpha_arms"
        private const val KEY_ALPHA_WEIGHT = "coeff_alpha_weight"
        private const val KEY_ALPHA_SEX = "coeff_alpha_sex"
        private const val KEY_BETA_LEGS = "coeff_beta_legs"
        private const val KEY_BETA_WEIGHT = "coeff_beta_weight"
        private const val KEY_BETA_SEX = "coeff_beta_sex"
        private const val KEY_GAMMA_TRUNK = "coeff_gamma_trunk"
        private const val KEY_GAMMA_WEIGHT = "coeff_gamma_weight"
        private const val KEY_GAMMA_SEX = "coeff_gamma_sex"
        private const val KEY_HEAD_CONSTANT = "coeff_head_constant"
    }
}
