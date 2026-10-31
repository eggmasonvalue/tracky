package dev.eggmasonvalue.tracky.core.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationContractTest {
    @Test
    fun `v2 parses Linux composition and ignores unknown fields and electrical evidence`() {
        val page = ObservationPageParser.parse(envelope(session(composition = composition())))
        assertEquals("opaque-cursor", page.nextCursor)
        assertEquals(2L, page.versions.derivationVersion)
        val source = page.observations.single()
        assertEquals(7890L, source.weight?.raw)
        assertEquals(testComposition, source.composition)
        val validated = ObservationValidator.validate(source) as ObservationValidation.Valid
        assertEquals(testComposition, validated.projection.composition)
        assertEquals(78.9, validated.projection.weightKg, 0.0)
    }

    @Test
    fun `explicit null composition is weight-only`() {
        val page = ObservationPageParser.parse(envelope(session()))
        assertNull(page.observations.single().composition)
        val result = ObservationValidator.validate(page.observations.single()) as ObservationValidation.Valid
        assertNull(result.projection.composition)
        assertEquals(2L, result.projection.clientRecordVersion)
    }

    @Test
    fun `empty page still carries derivation version`() {
        val page = ObservationPageParser.parse(envelope(version = 3))
        assertTrue(page.observations.isEmpty())
        assertEquals(3L, page.versions.derivationVersion)
    }

    @Test
    fun `derivation version is an exact positive Long not a truncated Int`() {
        val version = 2147483648L
        val page = ObservationPageParser.parse(envelope(session(composition(version), version), version))
        val validated = ObservationValidator.validate(page.observations.single()) as ObservationValidation.Valid
        assertEquals(version, page.versions.derivationVersion)
        assertEquals(version, validated.projection.clientRecordVersion)
        for (invalid in listOf("0", "-1", "2.5", "9223372036854775808")) {
            val body = envelope().replace("\"derivation_version\":2", "\"derivation_version\":$invalid")
            assertThrows(ObservationContractException::class.java) { ObservationPageParser.parse(body) }
        }
    }

    @Test
    fun `only v2 pages and sessions are accepted`() {
        val page = JSONObject(envelope(session())).put("contract_version", 1)
        assertThrows(ObservationContractException::class.java) { ObservationPageParser.parse(page.toString()) }
        val item = JSONObject(session()).put("contract_version", 1)
        assertThrows(ObservationContractException::class.java) { ObservationPageParser.parse(envelope(item.toString())) }
    }

    @Test
    fun `composition is required and versions must match page`() {
        val missing = JSONObject(session()).also { it.remove("composition") }
        assertThrows(ObservationContractException::class.java) { ObservationPageParser.parse(envelope(missing.toString())) }
        assertThrows(ObservationContractException::class.java) {
            ObservationPageParser.parse(envelope(session(composition(version = 3))))
        }
        assertThrows(ObservationContractException::class.java) {
            ObservationPageParser.parse(envelope(session(version = 3)))
        }
        val noVersion = JSONObject(versions()).also { it.remove("derivation_version") }
        val root = JSONObject(envelope()).put("versions", noVersion)
        assertThrows(ObservationContractException::class.java) { ObservationPageParser.parse(root.toString()) }
    }

    @Test
    fun `unsupported decoder or malformed versions fail before quarantine`() {
        val root = JSONObject(envelope()).put("versions", JSONObject("{\"transport_version\":1}"))
        assertThrows(ObservationContractException::class.java) { ObservationPageParser.parse(root.toString()) }
        val decoder = JSONObject(versions()).put("decoder_versions", org.json.JSONArray("[3]"))
        root.put("versions", decoder)
        assertThrows(ObservationContractException::class.java) { ObservationPageParser.parse(root.toString()) }
    }

    @Test
    fun `missing source-selected weight is quarantinable and unknown fallback is ignored`() {
        val item = JSONObject(session()).put("selected", JSONObject.NULL).put("weight_kg", 78.9)
        val observation = ObservationPageParser.parse(envelope(item.toString())).observations.single()
        val result = ObservationValidator.validate(observation) as ObservationValidation.Invalid
        assertEquals("no_safe_selection", result.errorCode)
        assertNull(observation.weight)
    }

    @Test
    fun `source weight validation remains unchanged and does not clamp`() {
        fun validate(weight: SourceWeight) = ObservationValidator.validate(testObservation().copy(weight = weight))
        val result = validate(SourceWeight(7890, 100, "kg", "source")) as ObservationValidation.Valid
        assertEquals(78.9, result.projection.weightKg, 0.0)
        assertNull(SourceWeight(1, 0, "kg", "source").kilogramsOrNull())
        assertEquals("weight_out_of_range", (validate(SourceWeight(100001, 100, "kg", "source")) as ObservationValidation.Invalid).errorCode)
        assertEquals("invalid_weight", (validate(SourceWeight(-1, 100, "kg", "source")) as ObservationValidation.Invalid).errorCode)
        assertEquals("unsupported_weight_unit", (validate(SourceWeight(7890, 100, "lb", "source")) as ObservationValidation.Invalid).errorCode)
    }

    @Test
    fun `retryable source state cannot advance a cursor`() {
        val root = JSONObject(envelope()).put("source_state", JSONObject("{\"kind\":\"retryable\",\"code\":\"source_session_open\"}"))
        assertThrows(ObservationContractException::class.java) { ObservationPageParser.parse(root.toString()) }
        root.put("next_cursor", JSONObject.NULL)
        assertEquals("source_session_open", ObservationPageParser.parse(root.toString()).sourceState.code)
    }

    @Test
    fun `identity is a stable SHA256 prefix with record-specific suffixes independent of version`() {
        val expectedBase = "tracky-ba7816bf8f01cfea414140de5dae2223"
        assertEquals("$expectedBase-weight", ObservationValidator.clientRecordId("abc"))
        val projection = (ObservationValidator.validate(testObservation("abc")) as ObservationValidation.Valid).projection
        listOf("weight", "body-fat", "lean-body-mass", "body-water-mass", "bone-mass", "bmr").forEach {
            assertEquals("$expectedBase-$it", projection.clientRecordIdFor(it))
        }
        val bumped = ObservationValidator.validate(testObservation("abc", 3)) as ObservationValidation.Valid
        assertEquals(projection.clientRecordId, bumped.projection.clientRecordId)
        assertEquals(3L, bumped.projection.clientRecordVersion)
    }

    private fun envelope(item: String? = null, version: Long = 2): String = """
        {"ok":true,"contract_version":2,"versions":${versions(version)},
         "source_state":{"kind":"ready"},"count":${if (item == null) 0 else 1},
         "next_cursor":"opaque-cursor","sessions":[${item ?: ""}],"future_page_field":{}}
    """.trimIndent()

    private fun session(composition: String = "null", version: Long = 2): String = """
        {"contract_version":2,"session_id":"session-1","status":"complete","quality":"accepted",
         "quality_version":1,"selection_policy_version":1,"started_at_utc":"2026-09-16T10:00:00Z",
         "selected":{"frame_sequence":3,"weight":{"raw":7890,"divisor":100,"unit":"kg","provenance":"Linux source"}},
         "versions":${versions(version)},"composition":$composition,"composition_unavailable":${if (composition == "null") "\"no_impedance\"" else "null"},
         "opaque_block_a":{"unknown_evidence":true},"opaque_block_b":[1,2],"impedances":"not for Android","future_session_field":true}
    """.trimIndent()

    private fun composition(version: Long = 2): String = """
        {"derivation_version":$version,"trunk_order":"wire","height_cm":170.0,
         "body_fat_pct":24.4,"lean_body_mass_kg":57.8,"body_water_kg":42.1,"bone_mass_kg":3.41,
         "protein_kg":12.08,"skeletal_muscle_kg":31.65,"basal_metabolic_rate_kcal_per_day":1618,"future_metric":123}
    """.trimIndent()

    private fun versions(version: Long = 2): String = """
        {"transport_version":1,"decoder_version":2,"decoder_versions":[2],
         "selection_policy_version":1,"quality_version":1,"derivation_version":$version}
    """.trimIndent()
}
