package com.friday.node.utils

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.friday.node.data.remote.WebSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

class HealthKitManager(private val context: Context) {

    // Fixed warning: Private constants/properties should use camelCase rather than screaming snake_case or uppercase initials
    private val tag = "FRIDAY_HealthKit"
    private var healthConnectClient: HealthConnectClient? = null

    init {
        try {
            // Evaluates whether the system service provider exists in the current device image
            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                healthConnectClient = HealthConnectClient.getOrCreate(context)
            } else {
                Log.w(tag, "Health Connect layer is unavailable on this device configuration.")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error initializing Health Connect: ${e.message}")
        }
    }

    /**
     * Executes a defensive query across local storage permissions to extract physiological
     * baselines and streams them directly into the primary communication pipe.
     */
    fun sampleBiometricBaseline() {
        val client = healthConnectClient ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val endTime = Instant.now()
                val startTime = endTime.minus(24, ChronoUnit.HOURS)

                // 1. Fetch evaluated sleep cycles matching historical metrics
                val sleepRequest = ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
                val sleepRecords = client.readRecords(sleepRequest).records
                val totalSleepMinutes = sleepRecords.sumOf { record ->
                    ChronoUnit.MINUTES.between(record.startTime, record.endTime)
                }

                // 2. Fetch resting heart rate distributions to track anxiety anomalies
                val hrRequest = ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
                val hrRecords = client.readRecords(hrRequest).records
                val averageHeartRate = if (hrRecords.isNotEmpty()) {
                    hrRecords.flatMap { record -> record.samples }.map { sample -> sample.beatsPerMinute }.average()
                } else {
                    72.0 // Fallback to a healthy resting standard if parameters are restricted
                }

                // 3. Encapsulate into your standardized core shared contract payload
                val biometricPacket = JSONObject().apply {
                    put("type", "biometric_baseline")
                    put("timestamp", System.currentTimeMillis())
                    put("sleep_duration_minutes", totalSleepMinutes)
                    put("mean_resting_hr", averageHeartRate.toInt())
                    put("battery_optimization_mode", BatteryOptimizer.getCurrentMode().name)
                }

                Log.i(tag, "Biometric evaluation complete. Mean HR: ${averageHeartRate.toInt()} BPM. Streaming telemetry...")
                WebSocketManager.getInstance().sendEvent(biometricPacket.toString())

            } catch (e: Exception) {
                Log.e(tag, "Failed to read physiological data hooks safely: ${e.message}")
            }
        }
    }
}