package com.friday.node.data.remote

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import com.friday.node.utils.BatteryOptimizer
import com.friday.node.utils.LocalFallbackEngine
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Aggregates individual sensor events into the unified ContextObject
 * schema expected by the FRIDAY backend orchestrator.
 *
 * Backend expects:
 * {
 *   "metadata": { "timestamp", "device_id", "session_id", "message_id" },
 *   "user_state": { "stress_score", "emotion_label" },
 *   "sensor_data": { "battery_level", "location", "app_switches",
 *                     "notification_count", "typo_rate", "screen_on_time",
 *                     "active_media" },
 *   "active_task": { ... } (optional)
 * }
 */
class ContextObjectBuilder(private val context: Context) {

    private val TAG = "FRIDAY_ContextBuilder"

    // Accumulating counters (reset after each snapshot push)
    @Volatile var appSwitchCount: Int = 0
        private set
    @Volatile var notificationCount: Int = 0
        private set
    @Volatile var totalTypingDelayMs: Long = 0
        private set
    @Volatile var typingEventCount: Int = 0
        private set
    @Volatile var lastFocusedPackage: String = "unknown"
        private set
    @Volatile var lastLocation: String = "home"
        private set
    @Volatile var sessionStartTimeMs: Long = System.currentTimeMillis()
        private set

    // Session identifier persists across context snapshots
    val sessionId: String = "android_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
    val deviceId: String by lazy {
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android_node"
        } catch (e: Exception) {
            "android_node"
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Event ingestion methods (called by services via broadcast)
    // ────────────────────────────────────────────────────────────────

    @Synchronized
    fun recordAppSwitch(packageName: String) {
        appSwitchCount++
        lastFocusedPackage = packageName
    }

    @Synchronized
    fun recordNotification() {
        notificationCount++
    }

    @Synchronized
    fun recordTypingCadence(averageDelayMs: Long) {
        totalTypingDelayMs += averageDelayMs
        typingEventCount++
    }

    fun setLocation(location: String) {
        lastLocation = location
    }

    // ────────────────────────────────────────────────────────────────
    // Build the full ContextObject JSON
    // ────────────────────────────────────────────────────────────────

    @Synchronized
    fun buildContextObject(): JSONObject {
        val now = System.currentTimeMillis()
        val isoTimestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(now))

        // Calculate derived metrics
        val typoRate = if (typingEventCount > 0) {
            val avgDelay = totalTypingDelayMs / typingEventCount
            // Heuristic: very fast (<120ms) or very slow (>600ms) typing indicates stress
            when {
                avgDelay < 120 -> 0.08f
                avgDelay > 600 -> 0.06f
                else -> 0.02f
            }
        } else 0.0f

        val screenOnTimeSeconds = ((now - sessionStartTimeMs) / 1000).toInt()

        // Compute local stress score using existing engine
        val offlineResult = LocalFallbackEngine.evaluateOfflineContext(
            appSwitchesCount = appSwitchCount,
            averageTypingCadenceMs = if (typingEventCount > 0) totalTypingDelayMs / typingEventCount else 0L,
            notificationsPerMin = notificationCount
        )

        // Get battery level
        val batteryLevel = getBatteryLevel()

        // Emotion label from stress score
        val emotionLabel = when {
            offlineResult.stressScore >= 75 -> "stressed"
            offlineResult.stressScore >= 50 -> "focused"
            offlineResult.stressScore >= 30 -> "calm"
            else -> "relaxed"
        }

        val contextObject = JSONObject().apply {
            // metadata
            put("metadata", JSONObject().apply {
                put("timestamp", isoTimestamp)
                put("device_id", deviceId)
                put("session_id", sessionId)
                put("message_id", "msg_${now}_${UUID.randomUUID().toString().take(6)}")
            })

            // user_state
            put("user_state", JSONObject().apply {
                put("stress_score", offlineResult.stressScore)
                put("emotion_label", emotionLabel)
            })

            // sensor_data
            put("sensor_data", JSONObject().apply {
                put("battery_level", batteryLevel)
                put("location", lastLocation)
                put("app_switches", appSwitchCount)
                put("notification_count", notificationCount)
                put("typo_rate", typoRate.toDouble())
                put("screen_on_time", screenOnTimeSeconds)
                put("focused_app", lastFocusedPackage)
                activeMedia?.let { put("active_media", it) }
                activePage?.let { put("active_page", it) }
            })
        }

        Log.d(TAG, "Built ContextObject: stress=${offlineResult.stressScore}, " +
                "app_switches=$appSwitchCount, notifs=$notificationCount, " +
                "battery=$batteryLevel, typo_rate=$typoRate")

        return contextObject
    }

    /**
     * Build and reset counters for a fresh snapshot interval.
     */
    @Synchronized
    fun buildAndReset(): JSONObject {
        val ctx = buildContextObject()
        // Reset accumulators for next interval
        appSwitchCount = 0
        notificationCount = 0
        totalTypingDelayMs = 0
        typingEventCount = 0
        return ctx
    }

    // ────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────

    private fun getBatteryLevel(): Int {
        return try {
            val batteryStatus: Intent? = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) ((level.toFloat() / scale) * 100).toInt() else 100
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read battery level: ${e.message}")
            100
        }
    }

    companion object {
        @Volatile var activeMedia: JSONObject? = null
        @Volatile var activePage: JSONObject? = null
    }
}
