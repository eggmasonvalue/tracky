package dev.eggmasonvalue.tracky.core.sync

import java.time.Instant

internal val testInstant: Instant = Instant.parse("2026-09-16T10:00:00Z")
internal val testComposition = SourceComposition(24.4, 57.8, 42.1, 3.41, 1618.0)
internal fun testVersions(version: Long = 2) = ObservationVersions(1, 2, listOf(2), 1, 1, version)

internal fun testObservation(id: String = "session-1", version: Long = 2) = RemoteObservation(
    sessionId = id,
    contractVersion = 2,
    status = "complete",
    quality = "accepted",
    qualityVersion = 1,
    startedAt = testInstant,
    selectedFrameSequence = 3,
    selectionPolicyVersion = 1,
    versions = testVersions(version),
    weight = SourceWeight(7890, 100, "kg", "Linux source"),
)

internal fun testProjection(version: Long = 2): SessionProjection =
    (ObservationValidator.validate(testObservation(version = version)) as ObservationValidation.Valid).projection
