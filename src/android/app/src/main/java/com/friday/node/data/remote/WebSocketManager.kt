package com.friday.node.data.remote

import okhttp3.*
import java.util.concurrent.TimeUnit

class WebSocketManager private constructor() {
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    companion object {
        @Volatile
        private var instance: WebSocketManager? = null

        fun getInstance(): WebSocketManager {
            return instance ?: synchronized(this) {
                instance ?: WebSocketManager().also { instance = it }
            }
        }
    }

    fun connect(serverUrl: String) {
        // Disconnect existing if any
        disconnect()
        
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                android.util.Log.i("WebSocketManager", "Connected to Hub")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.e("WebSocketManager", "Connection failure: ${t.message}")
            }
        })
    }

    fun sendEvent(jsonPayload: String) {
        webSocket?.send(jsonPayload)
    }

    fun disconnect() {
        webSocket?.close(1000, "Service stopping")
        webSocket = null
    }
}
