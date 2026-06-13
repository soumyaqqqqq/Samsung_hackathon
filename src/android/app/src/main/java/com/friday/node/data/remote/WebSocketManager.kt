package com.friday.node.data.remote

import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import com.friday.node.data.local.EventEntity
import com.friday.node.data.local.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class WebSocketManager private constructor() {
    private val TAG = "FRIDAY_WebSocketManager"
    private val client = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var serverUrl: String? = null
    private var context: Context? = null
    
    // UI state callback
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null

    companion object {
        @Volatile
        private var instance: WebSocketManager? = null

        fun getInstance(): WebSocketManager {
            return instance ?: synchronized(this) {
                instance ?: WebSocketManager().also { instance = it }
            }
        }
    }

    fun init(context: Context) {
        this.context = context.applicationContext
        
        val filter = IntentFilter().apply {
            addAction("com.friday.node.RESUME_HANDOFF_NOTIFICATION")
            addAction("com.friday.node.DISMISS_HANDOFF_NOTIFICATION")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.context?.registerReceiver(notificationActionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            this.context?.registerReceiver(notificationActionReceiver, filter)
        }
    }

    fun isConnected(): Boolean = isConnected

    fun getServerHttpUrl(): String? {
        val url = serverUrl ?: return null
        val cleanUrl = url.replace("ws://", "http://").replace("wss://", "https://")
        return when {
            cleanUrl.contains("/ws/android") -> cleanUrl.replace("/ws/android", "")
            cleanUrl.contains("/ws/laptop") -> cleanUrl.replace("/ws/laptop", "")
            else -> cleanUrl
        }
    }

    fun getVoiceWebSocketUrl(): String? {
        val url = serverUrl ?: return null
        return when {
            url.contains("/ws/android") -> url.replace("/ws/android", "/ws/voice")
            url.contains("/ws/laptop") -> url.replace("/ws/laptop", "/ws/voice")
            else -> if (url.endsWith("/")) "${url}ws/voice" else "$url/ws/voice"
        }
    }

    private fun attemptReconnection() {
        val url = serverUrl ?: return
        CoroutineScope(Dispatchers.IO).launch {
            delay(5000)
            if (!isConnected && serverUrl == url) {
                Log.i(TAG, "Attempting automatic reconnection to $url...")
                connect(url)
            }
        }
    }

    fun connect(url: String) {
        if (isConnected && serverUrl == url) {
            Log.i(TAG, "Already connected to $url. Skipping connection attempt.")
            return
        }

        disconnect()
        this.serverUrl = url

        Log.i(TAG, "Connecting to target hub: $url")
        val request = Request.Builder()
            .url(url)
            .header("ngrok-skip-browser-warning", "true")
            .build()
        val newWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket != this@WebSocketManager.webSocket) {
                    webSocket.close(1000, "Stale connection")
                    return
                }
                Log.i(TAG, "WebSocket Connection successfully established!")
                isConnected = true
                onConnectionStateChanged?.invoke(true)
                context?.sendBroadcast(android.content.Intent("com.friday.node.CONNECTION_STATE_CHANGED").apply {
                    putExtra("is_connected", true)
                })
                flushLocalCache()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket != this@WebSocketManager.webSocket) {
                    return
                }
                Log.d(TAG, "Received message from Hub: $text")
                handleBackendMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket != this@WebSocketManager.webSocket) {
                    return
                }
                Log.e(TAG, "WebSocket connection failure: ${t.message}")
                isConnected = false
                onConnectionStateChanged?.invoke(false)
                context?.sendBroadcast(android.content.Intent("com.friday.node.CONNECTION_STATE_CHANGED").apply {
                    putExtra("is_connected", false)
                })
                attemptReconnection()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != this@WebSocketManager.webSocket) {
                    return
                }
                Log.w(TAG, "WebSocket closing: $code / $reason")
                isConnected = false
                onConnectionStateChanged?.invoke(false)
                context?.sendBroadcast(android.content.Intent("com.friday.node.CONNECTION_STATE_CHANGED").apply {
                    putExtra("is_connected", false)
                })
                attemptReconnection()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != this@WebSocketManager.webSocket) {
                    return
                }
                Log.i(TAG, "WebSocket closed.")
                isConnected = false
                onConnectionStateChanged?.invoke(false)
                context?.sendBroadcast(android.content.Intent("com.friday.node.CONNECTION_STATE_CHANGED").apply {
                    putExtra("is_connected", false)
                })
                attemptReconnection()
            }
        })
        this.webSocket = newWebSocket
    }

    /**
     * Parse and dispatch backend messages based on their `type` field.
     *
     * Backend sends these message types:
     * - FRIDAY_CARD: Decision/action card from orchestrator
     * - ACK: Batch acknowledgment every 100 messages
     * - error: Validation/HMAC errors
     */
    private fun handleBackendMessage(text: String) {
        val ctx = context ?: return
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                "FRIDAY_CARD" -> {
                    // Full decision card from backend orchestrator
                    val actionId = json.optString("action_id", "")
                    val message = json.optString("message", "")
                    val score = json.optDouble("score", 0.0)
                    val condition = json.optString("condition", "default")
                    val agent = json.optString("agent", "decision")

                    Log.i(TAG, "FRIDAY_CARD received: action=$actionId, score=$score, condition=$condition, agent=$agent")

                    // Broadcast detailed action to UI
                    val intent = Intent("com.friday.node.ACTION_RECEIVED").apply {
                        putExtra("action_payload", text)
                        putExtra("action_id", actionId)
                        putExtra("suggested_action", message)
                        putExtra("score", score)
                        putExtra("condition", condition)
                        putExtra("agent", agent)
                    }
                    ctx.sendBroadcast(intent)
                }

                "MEDIA_HANDOFF", "PAGE_HANDOFF" -> {
                    val actionId = json.optString("action_id", "act_handoff_${System.currentTimeMillis()}")
                    val message = json.optString("message", "Resume leftover task")
                    val url = if (type == "MEDIA_HANDOFF") {
                        val videoId = json.optString("video_id", "")
                        val timestamp = json.optInt("playback_timestamp_seconds", 0)
                        "https://www.youtube.com/watch?v=${videoId}&t=${timestamp}s"
                    } else {
                        json.optString("url", "")
                    }

                    Log.i(TAG, "Handoff received: action=$actionId, message=$message, url=$url")

                    // Broadcast detailed action to UI
                    val intent = Intent("com.friday.node.ACTION_RECEIVED").apply {
                        putExtra("action_payload", text)
                        putExtra("action_id", actionId)
                        putExtra("suggested_action", message)
                    }
                    ctx.sendBroadcast(intent)

                    // Post high-priority system notification
                    val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    
                    val resumeIntent = Intent("com.friday.node.RESUME_HANDOFF_NOTIFICATION").apply {
                        putExtra("action_id", actionId)
                        putExtra("type", type)
                        putExtra("url", url)
                    }
                    val resumePending = PendingIntent.getBroadcast(
                        ctx,
                        actionId.hashCode() + 1,
                        resumeIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val dismissIntent = Intent("com.friday.node.DISMISS_HANDOFF_NOTIFICATION").apply {
                        putExtra("action_id", actionId)
                        putExtra("type", type)
                    }
                    val dismissPending = PendingIntent.getBroadcast(
                        ctx,
                        actionId.hashCode() + 2,
                        dismissIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val notification = androidx.core.app.NotificationCompat.Builder(ctx, "FRIDAY_CORE_TELEMETRY")
                        .setContentTitle(if (type == "MEDIA_HANDOFF") "Resume Watching Video" else "Resume Reading Page")
                        .setContentText(message)
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                        .setAutoCancel(true)
                        .addAction(android.R.drawable.ic_menu_slideshow, "Resume", resumePending)
                        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPending)
                        .build()

                    manager.notify(actionId.hashCode(), notification)
                }

                "INACTIVITY_DIGEST_RESPONSE" -> {
                    val summary = json.optString("summary", "")
                    Log.i(TAG, "INACTIVITY_DIGEST_RESPONSE received: summary=$summary")
                    val intent = Intent("com.friday.node.INACTIVITY_DIGEST_RECEIVED").apply {
                        putExtra("summary", summary)
                    }
                    ctx.sendBroadcast(intent)
                }

                "ACK" -> {
                    val count = json.optInt("count", 0)
                    Log.i(TAG, "Backend ACK received: $count messages processed")
                }

                "" -> {
                    // Check if it's an error response
                    val error = json.optString("error", "")
                    if (error.isNotEmpty()) {
                        Log.w(TAG, "Backend error: $error")
                    } else {
                        // Legacy/unknown format — broadcast raw payload
                        val intent = Intent("com.friday.node.ACTION_RECEIVED").apply {
                            putExtra("action_payload", text)
                        }
                        ctx.sendBroadcast(intent)
                    }
                }

                else -> {
                    Log.d(TAG, "Unhandled message type: $type")
                    val intent = Intent("com.friday.node.ACTION_RECEIVED").apply {
                        putExtra("action_payload", text)
                    }
                    ctx.sendBroadcast(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse backend message: ${e.message}")
            // Fallback: broadcast raw payload
            val intent = Intent("com.friday.node.ACTION_RECEIVED").apply {
                putExtra("action_payload", text)
            }
            ctx.sendBroadcast(intent)
        }
    }

    /**
     * Send RLHF feedback to the backend for a specific action card.
     *
     * @param actionId  The action_id from the FRIDAY_CARD
     * @param reaction  One of: "helpful", "dismissed", "ignored"
     */
    fun sendFeedback(actionId: String, reaction: String) {
        if (!isConnected || webSocket == null) {
            Log.w(TAG, "Cannot send feedback: not connected to hub")
            return
        }

        val feedbackPayload = JSONObject().apply {
            put("type", "feedback")
            put("action_id", actionId)
            put("user_reaction", reaction)
            put("timestamp", System.currentTimeMillis())
        }

        val sent = webSocket?.send(feedbackPayload.toString()) == true
        if (sent) {
            Log.i(TAG, "RLHF feedback sent: action=$actionId, reaction=$reaction")
        } else {
            Log.e(TAG, "Failed to send RLHF feedback for action: $actionId")
        }
    }

    fun sendEvent(jsonPayload: String) {
        val ctx = context
        if (isConnected && webSocket != null) {
            val success = webSocket?.send(jsonPayload) == true
            if (success) {
                Log.d(TAG, "Event streamed successfully to Hub.")
                return
            }
        }
        
        // If send fails or we are not connected, buffer the event locally
        if (ctx != null) {
            Log.w(TAG, "Hub unreachable. Caching event locally to Room/SQLite database.")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val json = JSONObject(jsonPayload)
                    val messageId = json.optString("message_id", "msg_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}")
                    val type = json.optString("type", "unknown")
                    val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                    
                    val db = RoomDatabase.getInstance(ctx)
                    val event = EventEntity(messageId, type, jsonPayload, timestamp)
                    db.insertEvent(event)
                } catch (e: Exception) {
                    Log.e(TAG, "Error caching event locally: ${e.message}")
                }
            }
        } else {
            Log.e(TAG, "Context not initialized in WebSocketManager, cannot cache event!")
        }
    }

    private fun flushLocalCache() {
        val ctx = context ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val db = RoomDatabase.getInstance(ctx)
            val cachedEvents = db.getAllEvents()
            if (cachedEvents.isNotEmpty()) {
                Log.i(TAG, "Reconnection established. Flushing ${cachedEvents.size} buffered events to Hub...")
                val successIds = mutableListOf<String>()
                for (event in cachedEvents) {
                    // Try to send each cached event
                    val payloadObj = JSONObject(event.payload).apply {
                        put("is_offline_replay", true) // Mark as a replayed offline event
                    }
                    val sent = webSocket?.send(payloadObj.toString()) == true
                    if (sent) {
                        successIds.add(event.messageId)
                    } else {
                        // Stop if we hit a failure mid-flush
                        break
                    }
                }
                if (successIds.isNotEmpty()) {
                    db.deleteEvents(successIds)
                    Log.i(TAG, "Successfully flushed and removed ${successIds.size} events from local database.")
                }
            }
        }
    }

    fun disconnect() {
        val ws = webSocket
        webSocket = null
        ws?.close(1000, "Service stopping")
        serverUrl = null
        isConnected = false
        onConnectionStateChanged?.invoke(false)
        try {
            this.context?.unregisterReceiver(notificationActionReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
    }

    private val notificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val actionId = intent?.getStringExtra("action_id") ?: return
            val type = intent.getStringExtra("type") ?: ""
            val url = intent.getStringExtra("url") ?: ""
            val manager = ctx?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

            manager?.cancel(actionId.hashCode())

            if (intent.action == "com.friday.node.RESUME_HANDOFF_NOTIFICATION") {
                sendFeedback(actionId, "helpful")

                if (url.isNotEmpty()) {
                    try {
                        val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        ctx?.startActivity(openIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch handoff URL: ${e.message}")
                    }
                }

                ctx?.sendBroadcast(Intent("com.friday.node.ACTION_RECEIVED").apply {
                    putExtra("action_payload", JSONObject().apply {
                        put("type", "CLEAR_HANDOFF")
                        put("action_id", actionId)
                    }.toString())
                })
            } else if (intent.action == "com.friday.node.DISMISS_HANDOFF_NOTIFICATION") {
                sendFeedback(actionId, "dismissed")

                ctx?.sendBroadcast(Intent("com.friday.node.ACTION_RECEIVED").apply {
                    putExtra("action_payload", JSONObject().apply {
                        put("type", "CLEAR_HANDOFF")
                        put("action_id", actionId)
                    }.toString())
                })
            }
        }
    }
}
