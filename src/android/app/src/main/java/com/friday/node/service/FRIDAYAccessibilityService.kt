package com.friday.node.service

import android.view.accessibility.AccessibilityEvent
import android.util.Log
import com.friday.node.data.remote.WebSocketManager
import org.json.JSONObject

class FRIDAYAccessibilityService : android.accessibility.AccessibilityService() {

    private val TAG = "FRIDAY_Accessibility"

    // Tracking parameters for typing cadence metrics
    private var lastTypeTimestamp = 0L
    private var keyCount = 0
    private var totalDelatTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
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
                        totalDelatTime += gap
                    }
                }
                lastTypeTimestamp = currentTimestamp

                // Every 10 keystrokes, calculate typing speed indicators and stream to Compute Hub
                if (keyCount >= 10) {
                    val averageCadenceMs = totalDelatTime / keyCount

                    val cadencePacket = JSONObject().apply {
                        put("type", "typing_metrics")
                        put("average_delay_ms", averageCadenceMs)
                        put("timestamp", currentTimestamp)
                    }

                    Log.d(TAG, "Streaming evaluated typing speed cadence delta: $averageCadenceMs ms")
                    WebSocketManager.getInstance().sendEvent(cadencePacket.toString())

                    // Reset interval variables
                    keyCount = 0
                    totalDelatTime = 0L
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