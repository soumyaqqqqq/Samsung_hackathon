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
        WebSocketManager.getInstance().init(this)
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
        val fromOnboarding = intent?.getBooleanExtra("from_onboarding", false) ?: false
        if (fromOnboarding) {
            Log.i("FRIDAY_SERVICE", "Sensing Node started from onboarding completion. Loading active configuration.")
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FRIDAY")
            .setContentText("Ambient Senses Active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val healthManager = com.friday.node.utils.HealthKitManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
        com.friday.node.utils.BatteryOptimizer.evaluateSystemState(this)

        val configManager = com.friday.node.config.OnboardingConfigManager(this)
        if (configManager.isModuleEnabled("Location & Environment")) {
            Log.i("FRIDAY_SERVICE", "Location & Environment telemetry module enabled. Starting discovery & health baseline.")
            discoveryManager.startSearching()
            healthManager.sampleBiometricBaseline()
        } else {
            Log.i("FRIDAY_SERVICE", "Location & Environment telemetry module disabled. Skipping discovery scan.")
        }
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
