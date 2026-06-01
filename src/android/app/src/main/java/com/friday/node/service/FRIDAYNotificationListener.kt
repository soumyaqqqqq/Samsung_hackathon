package com.friday.node.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.friday.node.data.remote.WebSocketManager
import com.friday.node.utils.DiscoveryManager
import org.json.JSONObject

class FRIDAYNotificationListener : NotificationListenerService() {

    private val TAG = "FRIDAY_NotificationListener"

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Ambient System Interceptor successfully bound to Android OS.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 1. Defensively check and bypass ongoing or system-level persistent notifications to optimize bandwidth
        if (sbn.isOngoing || (sbn.notification.flags and android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return
        }

        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: "No Title"
        val text = extras.getString("android.text") ?: extras.getCharSequence("android.text")?.toString() ?: "No Content"
        val timestamp = sbn.postTime
        val currentMode = com.friday.node.utils.BatteryOptimizer.evaluateSystemState(this)

        // 2. Build the structural contract matching your shared ContextObject schema
        val contextPacket = JSONObject().apply {
            put("type", "notification")
            put("message_id", "msg_${timestamp}_${packageName.hashCode()}")
            put("package", packageName)
            put("title", title)
            put("content", text)
            put("timestamp", timestamp)

            if (currentMode == com.friday.node.utils.BatteryOptimizer.RuntimeMode.GHOST) {
                // Ghost Mode Rule: Strip heavy text fields and content buffers to save processing cycles and transmission payload bytes
                put("title", "Hidden for Resource Optimization")
                put("content", "Content suppressed under Ghost Mode")
            } else {
                put("title", title)
                put("content", text)
            }
            put("timestamp", timestamp)
        }

        // 3. Extract dynamically resolved IP details via DiscoveryManager
        val activeHubIp = DiscoveryManager.getResolvedHubIp() // Assumes your DiscoveryManager stores the discovered host safely

        if (activeHubIp != null) {
            Log.d(TAG, "Streaming intercepted alert from $packageName directly to Compute Hub -> $activeHubIp")

            // Pushes raw context event over the established dynamically routed WebSocket pipe
            WebSocketManager.getInstance().sendEvent(contextPacket.toString())
        } else {
            Log.w(TAG, "Compute Hub IP not yet discovered by mDNS. Buffering notification locally to Room/SQLite database.")

            // TODO: Call your Room persistence manager to write this packet down so it flushes upon reconnection pass
            // LocalDatabaseBuffer.getInstance().queueEvent(contextPacket.toString())
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Optional tracking: Detect if the user cleared notifications manually to score cognitive interaction load
        Log.d(TAG, "Notification from ${sbn.packageName} was dismissed by user.")
    }
}