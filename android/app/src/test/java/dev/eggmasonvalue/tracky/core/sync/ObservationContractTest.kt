package dev.eggmasonvalue.tracky.core.sync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ObservationContractTest {
    @Test
    fun `parser keeps only Linux selected weight and opaque cursor`() {
        val page = ObservationPageParser.parse(
            envelope(
                nextCursor = "opaque-page-cursor",
                session = """
                    {
                      "contract_version": 1,
                      "session_id": "session-1",
                      "status": "complete",
                      "quality": "accepted",
                      "quality_version": 1,
                      "selection_policy_version": 1,
                      "started_at_utc": "2026-09-16T10:00:00Z",
                      "selected": {
                        "frame_sequence": 3,
                        "weight": {
                          "raw": 7890,
                          "divisor": 100,
                          "unit": "kg",
                          "provenance": "Linux decoder v1"
                        }
                      },
                      "versions": ${versions()}
                    }
                """.trimIndent(),
            ),
        )

        assertEquals("opaque-page-cursor", page.nextCursor)
        val source = page.observations.single()
        assertEquals("session-1", source.sessionId)
        assertEquals(3L, source.selectedFrameSequence)
        assertEquals(7890L, source.weight?.raw)
        assertEquals(100L, source.weight?.divisor)
        assertEquals("kg", source.weight?.unit)
        assertEquals(null, source.weight?.let { it.copy(raw = null).kilogramsOrNull() })
        assertTrue(ObservationValidator.validate(source) is ObservationValidation.Valid)
    }

    @Test
    fun `missing selected source weight is permanently quarantinable`() {
        val page = ObservationPageParser.parse(
            envelope(
                session = """
                    {
                      "contract_version": 1,
                      "session_id": "session-2",
                      "status": "complete",
                      "quality": "indeterminate",
                      "quality_version": 1,
                      "selection_policy_version": 1,
                      "started_at_utc": "2026-09-16T10:00:00Z",
                      "selected": null,
                      "weight_kg": 78.9,
                      "body_fat_percentage": 21.5,
                      "versions": ${versions()}
                    }
                """.trimIndent(),
            ),
        )

        val validation = ObservationValidator.validate(page.observations.single())
        assertTrue(validation is ObservationValidation.Invalid)
        assertEquals("no_safe_selection", (validation as ObservationValidation.Invalid).errorCode)
        assertFalse(page.observations.single().weight?.kilogramsOrNull() == 78.9)
    }

    @Test
    fun `future or malformed page versions fail before item quarantine`() {
        val future = envelope(versions = versions(decoderVersion = 3))
        assertThrows(ObservationContractException::class.java) {
            ObservationPageParser.parse(future)
        }

        val malformed = envelope(versions = "{\"transport_version\":1}")
        assertThrows(ObservationContractException::class.java) {
            ObservationPageParser.parse(malformed)
        }
    }

    @Test
    fun `retryable source state cannot carry a cursor`() {
        val page = envelope(
            sourceState = "{\"kind\":\"retryable\",\"code\":\"source_session_open\"}",
            nextCursor = null,
        )
        val parsed = ObservationPageParser.parse(page)
        assertEquals("retryable", parsed.sourceState.kind)
        assertEquals("source_session_open", parsed.sourceState.code)
        assertEquals(null, parsed.nextCursor)
    }

    @Test
    fun `source weight uses declared divisor without clamping`() {
        val source = RemoteObservation(
            sessionId = "session-3",
            contractVersion = 1,
            status = "complete",
            quality = "indeterminate",
            qualityVersion = 1,
            startedAt = Instant.parse("2026-09-16T10:00:00Z"),
            selectedFrameSequence = 2,
            selectionPolicyVersion = 1,
            versions = supportedVersions(),
            weight = SourceWeight(7890, 100, "kg", "source"),
        )

        val validation = ObservationValidator.validate(source) as ObservationValidation.Valid
        assertEquals(78.9, validation.projection.weightKg, 0.0)
        assertEquals(1L, validation.projection.clientRecordVersion)
        assertEquals(1, validation.projection.selectionPolicyVersion)
        assertEquals(null, validation.projection.derivationVersion)
        assertTrue(validation.projection.clientRecordId.startsWith("tracky-linux-weight-v1-"))
    }

    private fun envelope(
        session: String? = null,
        nextCursor: String? = "cursor",
        versions: String = versions(),
        sourceState: String = "{\"kind\":\"ready\"}",
    ): String = """
        {
          "ok": true,
          "contract_version": 1,
          "versions": $versions,
          "source_state": $sourceState,
          "count": ${if (session == null) 0 else 1},
          "next_cursor": ${nextCursor?.let { "\"$it\"" } ?: "null"},
          "sessions": [${session ?: ""}]
        }
    """.trimIndent()

    private fun versions(decoderVersion: Int = 2): String = """
        {
          "transport_version": 1,
          "decoder_version": $decoderVersion,
          "decoder_versions": [$decoderVersion],
          "selection_policy_version": 1,
          "quality_version": 1
        }
    """.trimIndent()

    private fun supportedVersions() = ObservationVersions(1, 2, listOf(2), 1, 1)
}
