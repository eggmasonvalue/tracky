package dev.eggmasonvalue.tracky.core.sync

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime

/** Parses the complete versioned observation envelope before item validation. */
object ObservationPageParser {
    fun parse(body: String): ObservationPage {
        if (body.length > MAX_RESPONSE_BYTES) {
            throw ObservationContractException("Observation response is too large")
        }
        val root = try {
            JSONObject(body)
        } catch (_: RuntimeException) {
            throw ObservationContractException("Observation response is not valid JSON")
        }
        if (requiredBoolean(root, "ok") != true) {
            throw ObservationContractException("Observation response is not successful")
        }
        val contractVersion = requiredInt(root, "contract_version")
        if (contractVersion != OBSERVATION_CONTRACT_VERSION) {
            throw ObservationContractException("Unsupported observation contract version")
        }
        val versions = parseVersions(root.requiredObject("versions"))
        val sourceState = parseSourceState(root.requiredObject("source_state"))
        val sessions = root.requiredArray("sessions")
        val count = requiredInt(root, "count")
        if (count != sessions.length()) {
            throw ObservationContractException("Observation count does not match sessions")
        }
        val observations = buildList(sessions.length()) {
            for (index in 0 until sessions.length()) {
                val item = try {
                    sessions.getJSONObject(index)
                } catch (_: RuntimeException) {
                    throw ObservationContractException("Observation item is not an object")
                }
                add(parseObservation(item, contractVersion, versions))
            }
        }
        val nextCursor = optionalString(root, "next_cursor")
        if (nextCursor != null && nextCursor.length > MAX_CURSOR_LENGTH) {
            throw ObservationContractException("Observation cursor is too large")
        }
        if (observations.isNotEmpty() && nextCursor.isNullOrBlank()) {
            throw ObservationContractException("Non-empty observation page has no cursor")
        }
        if (sourceState.kind == SOURCE_RETRYABLE &&
            (observations.isNotEmpty() || nextCursor != null)
        ) {
            throw ObservationContractException("Retryable source state cannot advance a page")
        }
        return ObservationPage(observations, nextCursor, versions, sourceState)
    }

    private fun parseObservation(
        item: JSONObject,
        contractVersion: Int,
        pageVersions: ObservationVersions,
    ): RemoteObservation {
        val itemContractVersion = requiredInt(item, "contract_version")
        if (itemContractVersion != contractVersion) {
            throw ObservationContractException("Observation item contract version differs from page")
        }
        val itemVersions = parseVersions(item.requiredObject("versions"))
        if (!versionsCompatible(pageVersions, itemVersions)) {
            throw ObservationContractException("Observation item versions differ from page")
        }
        val selected = if (!item.has("selected") || item.isNull("selected")) {
            null
        } else {
            try {
                item.getJSONObject("selected")
            } catch (_: RuntimeException) {
                throw ObservationContractException("Selected observation is not an object")
            }
        }
        val selectedFrameSequence = selected?.let { requiredLong(it, "frame_sequence") }
        val weight = selected?.let {
            val weightObject = try {
                it.getJSONObject("weight")
            } catch (_: RuntimeException) {
                throw ObservationContractException("Selected weight is not an object")
            }
            SourceWeight(
                raw = requiredLong(weightObject, "raw"),
                divisor = requiredLong(weightObject, "divisor"),
                unit = requiredString(weightObject, "unit"),
                provenance = requiredString(weightObject, "provenance"),
            )
        }
        val startedAt = parseInstant(requiredString(item, "started_at_utc"))
        if (!item.has("composition")) {
            throw ObservationContractException("Observation has no composition field")
        }
        val composition = if (item.isNull("composition")) null else {
            val value = item.requiredObject("composition")
            if (requiredLong(value, "derivation_version") != pageVersions.derivationVersion) {
                throw ObservationContractException("Composition version differs from page")
            }
            SourceComposition(
                bodyFatPct = requiredDouble(value, "body_fat_pct"),
                leanBodyMassKg = requiredDouble(value, "lean_body_mass_kg"),
                bodyWaterKg = requiredDouble(value, "body_water_kg"),
                boneMassKg = requiredDouble(value, "bone_mass_kg"),
                basalMetabolicRateKcalPerDay = requiredDouble(value, "basal_metabolic_rate_kcal_per_day"),
            )
        }
        return RemoteObservation(
            sessionId = requiredString(item, "session_id"),
            contractVersion = itemContractVersion,
            status = requiredString(item, "status"),
            quality = requiredString(item, "quality"),
            qualityVersion = requiredInt(item, "quality_version"),
            startedAt = startedAt,
            selectedFrameSequence = selectedFrameSequence,
            selectionPolicyVersion = requiredInt(item, "selection_policy_version"),
            versions = itemVersions,
            weight = weight,
            composition = composition,
        )
    }

    private fun parseVersions(value: JSONObject): ObservationVersions {
        val transport = requiredInt(value, "transport_version")
        val selection = requiredInt(value, "selection_policy_version")
        val quality = requiredInt(value, "quality_version")
        if (transport != TRANSPORT_VERSION || selection != WEIGHT_SELECTION_POLICY_VERSION || quality != QUALITY_VERSION) {
            throw ObservationContractException("Unsupported observation version set")
        }
        val decoderVersions = value.requiredArray("decoder_versions").let { array ->
            buildList(array.length()) {
                for (index in 0 until array.length()) add(array.getInt(index))
            }
        }
        if (decoderVersions.isEmpty() || decoderVersions.any { it !in 1..SUPPORTED_DECODER_VERSION }) {
            throw ObservationContractException("Unsupported decoder version set")
        }
        val decoderVersion = optionalInt(value, "decoder_version")
        if (decoderVersion != null && decoderVersion !in decoderVersions) {
            throw ObservationContractException("Decoder version is not in decoder_versions")
        }
        val derivation = requiredLong(value, "derivation_version")
        if (derivation < 1L) throw ObservationContractException("Invalid derivation version")
        return ObservationVersions(transport, decoderVersion, decoderVersions, selection, quality, derivation)
    }

    private fun versionsCompatible(
        page: ObservationVersions,
        item: ObservationVersions,
    ): Boolean =
        page.transportVersion == item.transportVersion &&
            page.selectionPolicyVersion == item.selectionPolicyVersion &&
            page.qualityVersion == item.qualityVersion &&
            page.derivationVersion == item.derivationVersion &&
            item.decoderVersions.all(page.decoderVersions::contains)

    private fun parseSourceState(value: JSONObject): PageSourceState {
        val kind = requiredString(value, "kind")
        return when (kind) {
            SOURCE_READY -> PageSourceState(kind, null)
            SOURCE_RETRYABLE -> PageSourceState(kind, requiredString(value, "code"))
            else -> throw ObservationContractException("Unsupported source state")
        }
    }

    private fun parseInstant(value: String): Instant =
        runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrElse {
                throw ObservationContractException("Observation timestamp is invalid")
            }

    private fun requiredString(value: JSONObject, key: String): String =
        value.opt(key).let { raw ->
            if (raw !is String) throw ObservationContractException("$key is not a string")
            raw
        }

    private fun requiredInt(value: JSONObject, key: String): Int =
        requiredLong(value, key).let { number ->
            if (number !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                throw ObservationContractException("$key is outside the integer range")
            }
            number.toInt()
        }

    private fun optionalInt(value: JSONObject, key: String): Int? =
        if (!value.has(key) || value.isNull(key)) null else requiredInt(value, key)

    private fun requiredLong(value: JSONObject, key: String): Long {
        val raw = value.opt(key)
        if (raw !is Number) throw ObservationContractException("$key is not an integer")
        return try {
            BigDecimal(raw.toString()).longValueExact()
        } catch (_: ArithmeticException) {
            throw ObservationContractException("$key is outside the integer range or fractional")
        } catch (_: NumberFormatException) {
            throw ObservationContractException("$key is not an integer")
        }
    }

    private fun requiredBoolean(value: JSONObject, key: String): Boolean? =
        value.opt(key) as? Boolean

    private fun optionalString(value: JSONObject, key: String): String? =
        if (!value.has(key) || value.isNull(key)) null else requiredString(value, key)

    private fun requiredDouble(value: JSONObject, key: String): Double =
        value.opt(key).let { raw ->
            if (raw !is Number || !raw.toDouble().isFinite()) {
                throw ObservationContractException("$key is not a finite number")
            }
            raw.toDouble()
        }

    private fun JSONObject.requiredObject(key: String): JSONObject = try {
        getJSONObject(key)
    } catch (_: RuntimeException) {
        throw ObservationContractException("$key is not an object")
    }

    private fun JSONObject.requiredArray(key: String): JSONArray = try {
        getJSONArray(key)
    } catch (_: RuntimeException) {
        throw ObservationContractException("$key is not an array")
    }

    private const val MAX_RESPONSE_BYTES = 1_048_576
    private const val MAX_CURSOR_LENGTH = 1024
    private const val SOURCE_READY = "ready"
    private const val SOURCE_RETRYABLE = "retryable"
}
