package com.friday.node.data.remote

import okhttp3.*
import java.util.concurrent.TimeUnit

class WebSocketManager(private val serverUrl: String) {
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Connection established
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Handle reconnection logic here
            }
        })
    }

    fun sendMessage(jsonPayload: String) {
        webSocket?.send(jsonPayload)
    }

    fun disconnect() {
        webSocket?.close(1000, "Service stopping")
    }
}