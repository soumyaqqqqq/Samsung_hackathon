package com.friday.node.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.friday.node.data.remote.WebSocketManager
import com.friday.node.utils.BatteryOptimizer
import org.json.JSONObject

class FRIDAYAccessibilityService : AccessibilityService() {

    private val TAG = "FRIDAY_Accessibility"

    // Tracking parameters for typing cadence metrics
    private var lastTypeTimestamp = 0L
    private var keyCount = 0
    private var totalDeltaTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Check our localized optimizer before running operations
        if (BatteryOptimizer.getCurrentMode() == BatteryOptimizer.RuntimeMode.GHOST) {
            // Ghost Mode Rule: Accessibility processing is disabled to protect battery life
            return
        }

        when (event.eventType) {
            // 1. Capture App Switching and Window Focus shifts
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val currentPackage = event.packageName?.toString() ?: return

                val contextPacket = JSONObject().apply {
                    put("type", "app_switch")
                    put("package_name", currentPackage)
                    put("timestamp", System.currentTimeMillis())
                }

                Log.d(TAG, "Window switched to focus app: $currentPackage")
                WebSocketManager.getInstance().sendEvent(contextPacket.toString())
            }

            // 2. Capture Content Changes to calculate Typing Cadence (without collecting raw keystrokes)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val currentTimestamp = System.currentTimeMillis()

                if (lastTypeTimestamp != 0L) {
                    val gap = currentTimestamp - lastTypeTimestamp
                    // Only process consecutive character interactions within a normal threshold (2 seconds)
                    if (gap in 10..2000) {
                        keyCount++
                        totalDeltaTime += gap
                    }
                }
                lastTypeTimestamp = currentTimestamp

                // Every 10 keystrokes, calculate typing speed indicators and stream to Compute Hub
                if (keyCount >= 10) {
                    val averageCadenceMs = if (keyCount > 0) totalDeltaTime / keyCount else 0

                    val cadencePacket = JSONObject().apply {
                        put("type", "typing_metrics")
                        put("average_delay_ms", averageCadenceMs)
                        put("timestamp", currentTimestamp)
                    }

                    Log.d(TAG, "Streaming evaluated typing speed cadence delta: $averageCadenceMs ms")
                    WebSocketManager.getInstance().sendEvent(cadencePacket.toString())

                    // Reset interval variables
                    keyCount = 0
                    totalDeltaTime = 0L
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility sensing stream interrupted by system resource constraints.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility monitor successfully connected and active.")
    }
}
