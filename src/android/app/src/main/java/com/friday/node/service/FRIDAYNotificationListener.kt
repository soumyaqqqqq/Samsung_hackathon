package com.friday.node.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.UUID

class FRIDAYNotificationListener : NotificationListenerService() {

    private val TAG = "FRIDAY_NotificationListener"
    private var isUserActive = true
    private val missedBuffer = mutableListOf<Map<String, Any>>()
    private var sequenceNumber = 0L

    // Strict regex compiler to drop cleartext passwords, PII tokens, and credit card patterns
    private val piiSanitizerRegex = Regex(
        "(?i)(password|passwd|pin|secret|credit|card|cvv|ssn|token|bearer|auth|key)[\\s\\:=]*[a-zA-Z0-9_\\-\\.\\s]{4,}",
        RegexOption.IGNORE_CASE
    )

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    // When the user unlocks or wakes the screen, process any backlog summary
                    if (!isUserActive) {
                        isUserActive = true
                        dispatchInactivityDigest()
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isUserActive = false
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Ambient System Interceptor successfully bound to Android OS.")
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenStateReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Screen state receiver already unregistered: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbnRef = sbn ?: return
        
        val configManager = com.friday.node.config.OnboardingConfigManager(this)
        if (!configManager.isModuleEnabled("Notification & Activity")) {
            return
        }

        // Defensively check and bypass ongoing or system-level persistent notifications to optimize bandwidth
        if (sbnRef.isOngoing || (sbnRef.notification.flags and android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return
        }

        val packageName = sbnRef.packageName

        // Prevent rendering feedback loops from our own foreground alerts
        if (packageName == this.packageName || packageName == "com.friday.node") return

        val extras: Bundle = sbnRef.notification.extras
        val title = extras.getString("android.title") ?: "Unknown Sender"
        val rawText = extras.getCharSequence("android.text")?.toString() ?: extras.getString("android.text") ?: ""
        
        // Strip out high-risk matching character arrays before saving or transmission
        val cleanText = sanitizeInput(rawText)

        // Respect battery saver Ghost mode
        val currentMode = com.friday.node.utils.BatteryOptimizer.evaluateSystemState(this)
        val finalTitle = if (currentMode == com.friday.node.utils.BatteryOptimizer.RuntimeMode.GHOST) {
            "Hidden for Resource Optimization"
        } else {
            title
        }
        val finalText = if (currentMode == com.friday.node.utils.BatteryOptimizer.RuntimeMode.GHOST) {
            "Content suppressed under Ghost Mode"
        } else {
            cleanText
        }

        val eventMap = mapOf(
            "message_id" to "msg_${System.currentTimeMillis()}_${packageName.hashCode()}",
            "sequence_number" to sequenceNumber++,
            "timestamp" to System.currentTimeMillis() / 1000.0,
            "package_id" to packageName,
            "title" to finalTitle,
            "content" to finalText,
            "priority" to sbnRef.notification.priority
        )

        // Send local broadcast for UI updates
        val localIntent = android.content.Intent("com.friday.node.NOTIFICATION_INTERCEPTED").apply {
            putExtra("package_name", packageName)
            putExtra("title", finalTitle)
            putExtra("content", finalText)
            putExtra("timestamp", System.currentTimeMillis())
        }
        sendBroadcast(localIntent)

        if (!isUserActive) {
            synchronized(missedBuffer) {
                missedBuffer.add(eventMap)
            }
            Log.d(TAG, "Device Inactive. Enqueued to offline buffer bundle: $packageName")
        } else {
            // Active tracker updates stream down the live WebSocket pipe instantly
            sendEventToService("ACTION_STREAM_LIVE_NOTIF", eventMap)
        }
    }

    private fun sanitizeInput(input: String): String {
        if (input.isBlank()) return ""
        return input.replace(piiSanitizerRegex, "[REDACTED_SENSITIVE_DATA]")
    }

    private fun sendEventToService(actionStr: String, payload: Map<String, Any>) {
        val intent = Intent(this, FRIDAYForegroundService::class.java).apply {
            action = actionStr
            putExtra("EXTRA_PACKAGE_ID", payload["package_id"] as String)
            putExtra("EXTRA_TITLE", payload["title"] as String)
            putExtra("EXTRA_CONTENT", payload["content"] as String)
            putExtra("EXTRA_TIMESTAMP", payload["timestamp"] as Double)
        }
        startService(intent)
    }

    private fun dispatchInactivityDigest() {
        val accumulatedEvents = synchronized(missedBuffer) {
            if (missedBuffer.isEmpty()) return
            val copy = ArrayList(missedBuffer)
            missedBuffer.clear()
            copy
        }

        // Format into an atomic telemetry frame to request processing
        val intent = Intent(this, FRIDAYForegroundService::class.java).apply {
            action = "ACTION_PROCESS_INACTIVITY_BATCH"
            putExtra("EXTRA_BATCH_SIZE", accumulatedEvents.size)
            
            // Format into structural text blocks for model processing
            val stringPayload = accumulatedEvents.joinToString("\n") { item ->
                "[App: ${item["package_id"]}] ${item["title"]}: ${item["content"]}"
            }
            putExtra("EXTRA_BATCH_RAW_TEXT", stringPayload)
        }
        startService(intent)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let {
            Log.d(TAG, "Notification from ${it.packageName} was dismissed by user.")
        }
    }
}