package com.friday.node.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.friday.node.data.remote.ContextObjectBuilder
import com.friday.node.data.remote.WebSocketManager
import com.friday.node.utils.BatteryOptimizer
import com.friday.node.utils.DiscoveryManager
import kotlinx.coroutines.*

class FRIDAYForegroundService : Service() {

    private val CHANNEL_ID = "FRIDAY_SERVICE_CHANNEL"
    private lateinit var discoveryManager: DiscoveryManager
    private var contextBuilder: ContextObjectBuilder? = null
    private var contextPushJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Broadcast receiver to feed sensor events into the ContextObjectBuilder
    private val sensorFeedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val builder = contextBuilder ?: return
            when (intent?.action) {
                "com.friday.node.APP_SWITCH_DETECTED" -> {
                    val pkg = intent.getStringExtra("package_name") ?: "unknown"
                    builder.recordAppSwitch(pkg)
                }
                "com.friday.node.TYPING_CADENCE_DETECTED" -> {
                    val delay = intent.getLongExtra("average_delay_ms", 0L)
                    builder.recordTypingCadence(delay)
                }
                "com.friday.node.NOTIFICATION_INTERCEPTED" -> {
                    builder.recordNotification()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        WebSocketManager.getInstance().init(this)
        createNotificationChannel()

        // Initialize the ContextObject builder
        contextBuilder = ContextObjectBuilder(this)

        discoveryManager = DiscoveryManager(this) { ipAddress, port ->
            Log.i("FRIDAY_SERVICE", "Target Compute Hub found! Connecting to $ipAddress:$port")

            // Stop scanning once found to save battery
            discoveryManager.stopSearching()

            // Initialize the WebSocket connection via Singleton
            val targetUrl = "ws://$ipAddress:$port/ws/android"
            WebSocketManager.getInstance().connect(targetUrl)
        }

        // Register receiver to listen for sensor events from Accessibility and Notification services
        val filter = IntentFilter().apply {
            addAction("com.friday.node.APP_SWITCH_DETECTED")
            addAction("com.friday.node.TYPING_CADENCE_DETECTED")
            addAction("com.friday.node.NOTIFICATION_INTERCEPTED")
        }
        registerReceiver(sensorFeedReceiver, filter, RECEIVER_EXPORTED)
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

        // Start periodic context snapshot push (every 15 seconds)
        startContextPushLoop()

        return START_STICKY
    }

    /**
     * Periodically aggregates sensor data into a full ContextObject and pushes
     * it over the WebSocket to the backend orchestrator.
     *
     * Interval: 15 seconds (matches backend expectation in main.py docs)
     * In GHOST mode: interval is extended to 60 seconds to save battery.
     */
    private fun startContextPushLoop() {
        contextPushJob?.cancel()
        contextPushJob = serviceScope.launch {
            Log.i("FRIDAY_SERVICE", "Context push loop started (session: ${contextBuilder?.sessionId})")
            while (isActive) {
                val intervalMs = if (BatteryOptimizer.getCurrentMode() == BatteryOptimizer.RuntimeMode.GHOST) {
                    60_000L // Ghost mode: push every 60s
                } else {
                    15_000L // Active/Aware mode: push every 15s
                }

                delay(intervalMs)

                val builder = contextBuilder ?: continue
                val wsManager = WebSocketManager.getInstance()

                if (!wsManager.isConnected()) {
                    Log.d("FRIDAY_SERVICE", "Hub not connected, context snapshot will be cached locally.")
                }

                try {
                    val contextObject = builder.buildAndReset()
                    wsManager.sendEvent(contextObject.toString())
                    Log.d("FRIDAY_SERVICE", "Context snapshot pushed to hub.")
                } catch (e: Exception) {
                    Log.e("FRIDAY_SERVICE", "Failed to push context snapshot: ${e.message}")
                }
            }
        }
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
        contextPushJob?.cancel()
        serviceScope.cancel()
        try {
            unregisterReceiver(sensorFeedReceiver)
        } catch (e: Exception) {
            Log.w("FRIDAY_SERVICE", "Receiver already unregistered: ${e.message}")
        }
        discoveryManager.stopSearching()
        WebSocketManager.getInstance().disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
