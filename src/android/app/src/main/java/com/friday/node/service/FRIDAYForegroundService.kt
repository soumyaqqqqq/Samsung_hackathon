package com.friday.node.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.friday.node.data.remote.WebSocketManager
import com.friday.node.utils.DiscoveryManager

class FRIDAYForegroundService : Service() {

    private val CHANNEL_ID = "FRIDAY_SERVICE_CHANNEL"
    private lateinit var discoveryManager: DiscoveryManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        discoveryManager = DiscoveryManager(this) { ipAddress, port ->
            Log.i("FRIDAY_SERVICE", "Target Compute Hub found! Connecting to $ipAddress:$port")

            // Stop scanning once found to save battery
            discoveryManager.stopSearching()

            // Initialize the WebSocket connection via Singleton
            val targetUrl = "ws://$ipAddress:$port/ws/android"
            WebSocketManager.getInstance().connect(targetUrl)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FRIDAY")
            .setContentText("Ambient Senses Active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)

        discoveryManager.startSearching()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "FRIDAY Foreground Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        discoveryManager.stopSearching()
        WebSocketManager.getInstance().disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
