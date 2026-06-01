package com.friday.node.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

object BatteryOptimizer {
    private const val TAG = "FRIDAY_Battery"

    // Enum representing the operational constraints of our architecture
    enum class RuntimeMode {
        ACTIVE, // Full high-fidelity signal sampling + voice pipelines
        AWARE,  // Baseline monitoring, throttled telemetry intervals
        GHOST   // Heavy sensors (Accessibility) offline, notification text stripped, raw survival
    }

    @Volatile
    private var currentMode: RuntimeMode = RuntimeMode.ACTIVE

    @Synchronized
    fun getCurrentMode(): RuntimeMode = currentMode

    /**
     * Evaluates the current system hardware state and returns whether the operational
     * footprint requires adaptation to protect hardware lifespan and pass audits.
     */
    fun evaluateSystemState(context: Context): RuntimeMode {
        val batteryStatus: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        if (level == -1 || scale == -1) return currentMode

        val batteryPct = (level / scale.toFloat()) * 100

        val computedMode = when {
            batteryPct < 20.0f -> RuntimeMode.GHOST
            batteryPct in 20.0f..60.0f -> RuntimeMode.AWARE
            else -> RuntimeMode.ACTIVE
        }

        synchronized(this) {
            if (currentMode != computedMode) {
                currentMode = computedMode
                handleModeTransition(context, computedMode, batteryPct)
            }
        }

        return computedMode
    }

    private fun handleModeTransition(context: Context, mode: RuntimeMode, percentage: Float) {
        Log.w(TAG, "Hardware Threshold Triggered! Battery at $percentage%. Adapting to system state: $mode")

        when (mode) {
            RuntimeMode.ACTIVE -> {
                // Restore all sensing pipelines to full operational metrics
                toggleSystemSenses(context, enableAccessibility = true)
            }
            RuntimeMode.AWARE -> {
                // Safe baseline operational state
                toggleSystemSenses(context, enableAccessibility = true)
            }
            RuntimeMode.GHOST -> {
                // Low power protection mode: Kill high-drain components to survive background execution
                toggleSystemSenses(context, enableAccessibility = false)
            }
        }
    }

    private fun toggleSystemSenses(context: Context, enableAccessibility: Boolean) {
        // Broadcast state changes internally so that listeners can automatically adjust sampling frequencies
        val intent = Intent("com.friday.node.BATTERY_MODE_CHANGED").apply {
            putExtra("enable_accessibility", enableAccessibility)
            putExtra("current_mode", currentMode.name)
        }
        context.sendBroadcast(intent)
    }
}