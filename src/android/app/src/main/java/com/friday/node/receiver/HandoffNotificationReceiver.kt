package com.friday.node.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.friday.node.data.remote.WebSocketManager
import org.json.JSONObject

class HandoffNotificationReceiver : BroadcastReceiver() {
    private val TAG = "FRIDAY_HandoffReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        val actionId = intent.getStringExtra("action_id") ?: ""
        val type = intent.getStringExtra("type") ?: ""
        val url = intent.getStringExtra("url") ?: ""

        Log.i(TAG, "Received handoff notification action: $action, actionId: $actionId, url: $url")

        // Initialize WebSocketManager
        val wsManager = WebSocketManager.getInstance()
        wsManager.init(context)

        // Cancel the notification
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        manager?.cancel(actionId.hashCode())

        if (action == "com.friday.node.RESUME_HANDOFF_NOTIFICATION") {
            wsManager.sendFeedback(actionId, "helpful")

            if (url.isNotEmpty()) {
                try {
                    val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(openIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch handoff URL: ${e.message}")
                }
            }

            context.sendBroadcast(Intent("com.friday.node.ACTION_RECEIVED").apply {
                putExtra("action_payload", JSONObject().apply {
                    put("type", "CLEAR_HANDOFF")
                    put("action_id", actionId)
                    put("reason", "resumed")
                }.toString())
            })
        } else if (action == "com.friday.node.DISMISS_HANDOFF_NOTIFICATION") {
            wsManager.sendFeedback(actionId, "dismissed")

            context.sendBroadcast(Intent("com.friday.node.ACTION_RECEIVED").apply {
                putExtra("action_payload", JSONObject().apply {
                    put("type", "CLEAR_HANDOFF")
                    put("action_id", actionId)
                    put("reason", "dismissed")
                }.toString())
            })
        }
    }
}
