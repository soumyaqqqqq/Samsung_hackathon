package com.friday.node.utils

import android.content.BroadcastReceiver
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
    private var batteryReceiver: BroadcastReceiver? = null

    @Synchronized
    fun getCurrentMode(): RuntimeMode = currentMode

    /**
     * Registers a runtime broadcast receiver for real-time battery level and power state shifts.
     */
    fun registerBatteryListener(context: Context) {
        if (batteryReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (c != null) {
                    evaluateSystemState(c)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(receiver, filter)
        batteryReceiver = receiver
        Log.i(TAG, "Dynamic battery state broadcast receiver registered.")
    }

    /**
     * Unregisters the dynamic battery receiver when services stop.
     */
    fun unregisterBatteryListener(context: Context) {
        batteryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {}
            batteryReceiver = null
            Log.i(TAG, "Dynamic battery state broadcast receiver unregistered.")
        }
    }

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
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        if (level == -1 || scale == -1) return currentMode

        val batteryPct = (level / scale.toFloat()) * 100

        val computedMode = when {
            isCharging -> RuntimeMode.ACTIVE
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
                // Throttled baseline: disable heavy content sensors, reduce telemetry frequency
                toggleSystemSenses(context, enableAccessibility = false)
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