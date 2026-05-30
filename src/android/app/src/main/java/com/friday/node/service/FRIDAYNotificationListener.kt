package com.friday.node.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.friday.node.data.remote.WebSocketManager
import org.json.JSONObject

class FRIDAYNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val title = sbn.notification.extras.getString("android.title") ?: "No Title"
        val text = sbn.notification.extras.getString("android.text") ?: "No Content"

        // 1. Package the event into a JSON object
        val event = JSONObject().apply {
            put("type", "notification")
            put("package", packageName)
            put("title", title)
            put("timestamp", System.currentTimeMillis())
        }

        // 2. Send via WebSocketManager
        // Note: Ensure your WebSocketManager is a singleton or accessible
        // Example: WebSocketManager.getInstance().send(event.toString())
        Log.d("FRIDAY", "Intercepted: $title from $packageName")
    }
}