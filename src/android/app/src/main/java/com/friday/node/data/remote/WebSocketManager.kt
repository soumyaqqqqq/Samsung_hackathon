package com.friday.node.data.remote

import android.content.Context
import android.content.Intent
import android.util.Log
import com.friday.node.data.local.EventEntity
import com.friday.node.data.local.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    }

    fun isConnected(): Boolean = isConnected

    fun getServerHttpUrl(): String? {
        return serverUrl?.replace("ws://", "http://")?.replace("wss://", "https://")?.replace("/ws/android", "")
    }

    fun connect(url: String) {
        this.serverUrl = url
        disconnect()

        Log.i(TAG, "Connecting to target hub: $url")
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket Connection successfully established!")
                isConnected = true
                onConnectionStateChanged?.invoke(true)
                context?.sendBroadcast(android.content.Intent("com.friday.node.CONNECTION_STATE_CHANGED").apply {
                    putExtra("is_connected", true)
                })
                flushLocalCache()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received message from Hub: $text")
                // Handle incoming actions/decisions from Hub (e.g., system actions, prompts)
                context?.let { ctx ->
                    val intent = Intent("com.friday.node.ACTION_RECEIVED").apply {
                        putExtra("action_payload", text)
                    }
                    ctx.sendBroadcast(intent)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket connection failure: ${t.message}")
                isConnected = false
                onConnectionStateChanged?.invoke(false)
                context?.sendBroadcast(android.content.Intent("com.friday.node.CONNECTION_STATE_CHANGED").apply {
                    putExtra("is_connected", false)
                })
                
                // Attempt automatic reconnection after 5 seconds if we have a URL
                serverUrl?.let { url ->
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(5000)
                        if (!isConnected) {
                            connect(url)
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing: $code / $reason")
                isConnected = false
                onConnectionStateChanged?.invoke(false)
                context?.sendBroadcast(android.content.Intent("com.friday.node.CONNECTION_STATE_CHANGED").apply {
                    putExtra("is_connected", false)
                })
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed.")
                isConnected = false
                onConnectionStateChanged?.invoke(false)
                context?.sendBroadcast(android.content.Intent("com.friday.node.CONNECTION_STATE_CHANGED").apply {
                    putExtra("is_connected", false)
                })
            }
        })
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
        webSocket?.close(1000, "Service stopping")
        webSocket = null
        isConnected = false
        onConnectionStateChanged?.invoke(false)
    }

    // Helper method to simulate delay without importing kotlinx.coroutines.delay
    private fun delay(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // Restore interrupted status
            Thread.currentThread().interrupt()
        }
    }
}
