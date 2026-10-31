package dev.eggmasonvalue.tracky.core.sync

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant

const val OBSERVATION_CONTRACT_VERSION: Int = 2
const val TRANSPORT_VERSION: Int = 1
const val SUPPORTED_DECODER_VERSION: Int = 2
const val WEIGHT_SELECTION_POLICY_VERSION: Int = 1
const val QUALITY_VERSION: Int = 1

data class ObservationVersions(
    val transportVersion: Int,
    val decoderVersion: Int?,
    val decoderVersions: List<Int>,
    val selectionPolicyVersion: Int,
    val qualityVersion: Int,
    val derivationVersion: Long,
)

data class PageSourceState(val kind: String, val code: String?)

/** One source-declared weight value, not an interpretation of electrical evidence. */
data class SourceWeight(
    val raw: Long?,
    val divisor: Long?,
    val unit: String?,
    val provenance: String?,
) {
    fun kilogramsOrNull(): Double? {
        val rawValue = raw ?: return null
        val divisorValue = divisor ?: return null
        if (rawValue <= 0L || divisorValue <= 0L) return null
        val exact = BigDecimal.valueOf(rawValue)
            .divide(BigDecimal.valueOf(divisorValue), 12, RoundingMode.HALF_UP)
        val value = exact.toDouble()
        return value.takeIf { it.isFinite() && it > 0.0 }
    }
}

/** Only Linux composition fields with corresponding Health Connect record types. */
data class SourceComposition(
    val bodyFatPct: Double,
    val leanBodyMassKg: Double,
    val bodyWaterKg: Double,
    val boneMassKg: Double,
    val basalMetabolicRateKcalPerDay: Double,
)

data class RemoteObservation(
    val sessionId: String?,
    val contractVersion: Int,
    val status: String?,
    val quality: String?,
    val qualityVersion: Int?,
    val startedAt: Instant?,
    val selectedFrameSequence: Long?,
    val selectionPolicyVersion: Int,
    val versions: ObservationVersions,
    val weight: SourceWeight?,
    val composition: SourceComposition? = null,
)

data class ObservationPage(
    val observations: List<RemoteObservation>,
    /** Opaque server cursor; Android neither decodes nor constructs it. */
    val nextCursor: String?,
    val versions: ObservationVersions,
    val sourceState: PageSourceState,
)

data class SessionProjection(
    val sourceSessionId: String,
    /** Weight ID is also the session's ledger key. */
    val clientRecordId: String,
    val clientRecordVersion: Long,
    val contractVersion: Int,
    val selectionPolicyVersion: Int,
    val derivationVersion: Long,
    val weightKg: Double,
    val observedAt: Instant,
    val sourceQuality: String,
    val composition: SourceComposition? = null,
) {
    fun clientRecordIdFor(suffix: String): String =
        clientRecordId.removeSuffix("-weight") + "-$suffix"
}

sealed interface ObservationValidation {
    data class Valid(val projection: SessionProjection) : ObservationValidation
    data class Invalid(
        val projection: SessionProjection,
        val errorCode: String,
        val errorMessage: String,
    ) : ObservationValidation
}

/** Validates source-selected weight; Android never interprets opaque electrical evidence. */
object ObservationValidator {
    fun validate(
        observation: RemoteObservation,
        derivationVersion: Long = observation.versions.derivationVersion,
    ): ObservationValidation {
        val sessionId = observation.sessionId
        val projection = SessionProjection(
            sourceSessionId = sessionId.orEmpty().ifBlank { INVALID_SESSION_ID },
            clientRecordId = clientRecordId(sessionId ?: INVALID_SESSION_ID),
            clientRecordVersion = derivationVersion,
            contractVersion = observation.contractVersion,
            selectionPolicyVersion = observation.selectionPolicyVersion,
            derivationVersion = derivationVersion,
            weightKg = 0.0,
            observedAt = observation.startedAt ?: Instant.EPOCH,
            sourceQuality = observation.quality.orEmpty(),
        )
        fun invalid(code: String, message: String): ObservationValidation.Invalid =
            ObservationValidation.Invalid(projection, code, message)

        if (observation.contractVersion != OBSERVATION_CONTRACT_VERSION) {
            return invalid("unsupported_contract", "Observation contract version is not supported")
        }
        if (sessionId.isNullOrBlank() || !SESSION_ID_PATTERN.matches(sessionId)) {
            return invalid("invalid_session_id", "Observation session ID is missing or malformed")
        }
        if (observation.status != "complete") {
            return invalid("ineligible_status", "Only completed Linux sessions can be projected")
        }
        if (observation.quality != null && observation.quality !in ALLOWED_QUALITY) {
            return invalid("invalid_quality", "Observation quality is not recognized")
        }
        if (observation.qualityVersion != QUALITY_VERSION) {
            return invalid("unsupported_quality_version", "Observation quality version is not supported")
        }
        if (observation.selectionPolicyVersion != WEIGHT_SELECTION_POLICY_VERSION) {
            return invalid("unsupported_selection_policy", "Weight selection policy is not supported")
        }
        if (observation.selectedFrameSequence == null) {
            return invalid("no_safe_selection", "Linux completed the session without a safe selected frame")
        }
        val weight = observation.weight
            ?: return invalid("missing_weight", "The Linux selected frame has no source weight")
        if (weight.unit != "kg") {
            return invalid("unsupported_weight_unit", "Only the source-declared kg weight is supported")
        }
        if (weight.provenance.isNullOrBlank()) {
            return invalid("missing_weight_provenance", "The source weight has no provenance")
        }
        val kilograms = weight.kilogramsOrNull()
            ?: return invalid("invalid_weight", "The source weight is not a positive finite value")
        if (kilograms > MAX_WEIGHT_KG) {
            return invalid("weight_out_of_range", "The source weight exceeds the supported import range")
        }
        val observedAt = observation.startedAt
            ?: return invalid("missing_observed_at", "The source observation has no timestamp")
        return ObservationValidation.Valid(
            projection.copy(
                sourceSessionId = sessionId,
                weightKg = kilograms,
                observedAt = observedAt,
                sourceQuality = observation.quality ?: "indeterminate",
                composition = observation.composition,
            ),
        )
    }

    fun clientRecordId(sourceSessionId: String): String =
        "tracky-${sha256(sourceSessionId).take(32)}-weight"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private const val INVALID_SESSION_ID = "invalid-session"
    private const val MAX_WEIGHT_KG = 1000.0
    private val SESSION_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val ALLOWED_QUALITY = setOf("accepted", "contact_failed", "indeterminate")
}

class ObservationContractException(message: String) : Exception(message)

class SyncTransportException(
    val retryable: Boolean,
    val code: String,
    message: String,
) : Exception(message)

sealed interface SyncRunResult {
    data class Success(val imported: Int, val alreadyTerminal: Int, val quarantined: Int) : SyncRunResult
    data class Retry(val code: String) : SyncRunResult
    data class Failure(val code: String) : SyncRunResult
    data object NeedsConfiguration : SyncRunResult
    data object HealthConnectUnavailable : SyncRunResult
    data object HealthConnectPermissionsMissing : SyncRunResult
    data object LocalNetworkPermissionMissing : SyncRunResult
}
