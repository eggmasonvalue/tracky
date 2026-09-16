package dev.eggmasonvalue.tracky.core.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.eggmasonvalue.tracky.core.data.ProfileRepository
import dev.eggmasonvalue.tracky.core.healthconnect.HealthConnectManager
import dev.eggmasonvalue.tracky.core.metrics.SegmentalOhms
import dev.eggmasonvalue.tracky.core.metrics.SmfBiaEngine
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class SyncWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE)
    private val profileRepository = ProfileRepository(context)
    private val healthConnectManager = HealthConnectManager(context)

    override suspend fun doWork(): Result {
        val serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        val token = prefs.getString(KEY_AUTH_TOKEN, null)
        val lastCursor = prefs.getString(KEY_LAST_CURSOR, null)

        Log.i(TAG, "Starting sync from $serverUrl (cursor: $lastCursor)")

        try {
            val endpoint = buildString {
                append(serverUrl.trimEnd('/'))
                append("/api/v1/sessions/unsynced")
                if (!lastCursor.isNullOrBlank()) {
                    append("?since=")
                    append(URLEncoder.encode(lastCursor, StandardCharsets.UTF_8.name()))
                }
            }

            val url = URL(endpoint)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Sync server returned HTTP $responseCode")
                return Result.retry()
            }

            val responseBody = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val json = JSONObject(responseBody)
            val sessionsArray = json.getJSONArray("sessions")
            val newCursor = if (json.has("cursor") && !json.isNull("cursor")) json.getString("cursor") else null

            Log.i(TAG, "Fetched ${sessionsArray.length()} unsynced sessions")

            val profile = profileRepository.getProfile()
            var syncedCount = 0

            for (i in 0 until sessionsArray.length()) {
                val sessionObj = sessionsArray.getJSONObject(i)
                val weightKg = sessionObj.optDouble("weight_kg", 0.0)
                val channelsArray = sessionObj.optJSONArray("raw_impedance_channels")

                if (weightKg > 0.0 && channelsArray != null && channelsArray.length() == 10) {
                    val channels = (0 until 10).map { channelsArray.getInt(it) }
                    val ohms = SegmentalOhms.fromRawCounts(channels)
                    val metrics = SmfBiaEngine.compute(profile, weightKg, ohms)

                    val startedAtStr = sessionObj.getString("started_at_utc")
                    val parsedDateTime = OffsetDateTime.parse(startedAtStr)
                    val instant = parsedDateTime.toInstant()
                    val zoneOffset = parsedDateTime.offset ?: ZoneOffset.UTC

                    if (healthConnectManager.isAvailable() && healthConnectManager.hasAllPermissions()) {
                        healthConnectManager.writeBodyComposition(
                            time = instant,
                            zoneOffset = zoneOffset,
                            weightKg = metrics.weightKg,
                            bodyFatPercentage = metrics.bodyFatPercentage,
                            leanBodyMassKg = metrics.fatFreeMassKg,
                        )
                        syncedCount++
                    } else {
                        Log.w(TAG, "Health Connect permissions not granted; skipping record insert")
                    }
                }
            }

            if (!newCursor.isNullOrBlank()) {
                prefs.edit().putString(KEY_LAST_CURSOR, newCursor).apply()
            }

            Log.i(TAG, "Sync completed successfully: $syncedCount sessions written to Health Connect")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed with exception", e)
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "TrackySyncWorker"
        const val PREFS_SYNC = "tracky_sync_settings"
        const val KEY_SERVER_URL = "sync_server_url"
        const val KEY_AUTH_TOKEN = "sync_auth_token"
        const val KEY_LAST_CURSOR = "sync_last_cursor"

        const val DEFAULT_SERVER_URL = "http://debian-mini-fat.local:8765"
    }
}
