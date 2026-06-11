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
import com.friday.node.utils.HealthKitManager
import com.friday.node.config.OnboardingConfigManager
import kotlinx.coroutines.*
import org.json.JSONObject

class FRIDAYForegroundService : Service() {

    private val TAG = "FRIDAY_ForegroundService"
    private val CHANNEL_ID = "FRIDAY_CORE_TELEMETRY"
    private val TRACKER_NOTIFICATION_ID = 4004
    private val DIGEST_NOTIFICATION_ID = 5005

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
                "com.friday.node.CONNECTION_STATE_CHANGED" -> {
                    val isConnected = intent.getBooleanExtra("is_connected", false)
                    if (!isConnected) {
                        Log.i(TAG, "Connection lost/disconnected. Restarting network discovery...")
                        discoveryManager.startSearching()
                    }
                }
            }
        }
    }

    private val digestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.friday.node.INACTIVITY_DIGEST_RECEIVED") {
                val summaryResult = intent.getStringExtra("summary") ?: ""
                triggerSystemDigestAlert(summaryResult)
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
            Log.i(TAG, "Target Compute Hub found! Connecting to $ipAddress:$port")

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
            addAction("com.friday.node.CONNECTION_STATE_CHANGED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sensorFeedReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(sensorFeedReceiver, filter)
        }

        // Register receiver for inactivity digest responses from backend
        val digestFilter = IntentFilter("com.friday.node.INACTIVITY_DIGEST_RECEIVED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(digestReceiver, digestFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(digestReceiver, digestFilter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromOnboarding = intent?.getBooleanExtra("from_onboarding", false) ?: false
        if (fromOnboarding) {
            Log.i(TAG, "Sensing Node started from onboarding completion. Loading active configuration.")
        }

        val baseNotification = buildTrackerNotification("FRIDAY Engine Status: Ambient Senses Initialized")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(TRACKER_NOTIFICATION_ID, baseNotification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(TRACKER_NOTIFICATION_ID, baseNotification)
        }

        com.friday.node.utils.BatteryOptimizer.evaluateSystemState(this)

        Log.i(TAG, "Starting automatic network discovery for FRIDAY Compute Hub...")
        discoveryManager.startSearching()

        val healthManager = HealthKitManager(this)
        val configManager = OnboardingConfigManager(this)
        if (configManager.isModuleEnabled("Location & Environment")) {
            Log.i(TAG, "Location & Environment telemetry module enabled. Sampling health baseline.")
            healthManager.sampleBiometricBaseline()
        } else {
            Log.i(TAG, "Location & Environment telemetry module disabled. Skipping baseline health sampling.")
        }

        // Start periodic context snapshot push (every 15 seconds)
        startContextPushLoop()

        // Handle specific notification actions from FRIDAYNotificationListener
        intent?.let {
            when (it.action) {
                "ACTION_STREAM_LIVE_NOTIF" -> {
                    val pkg = it.getStringExtra("EXTRA_PACKAGE_ID") ?: ""
                    val title = it.getStringExtra("EXTRA_TITLE") ?: ""
                    val content = it.getStringExtra("EXTRA_CONTENT") ?: ""
                    val ts = it.getDoubleExtra("EXTRA_TIMESTAMP", 0.0)

                    // 1. Refresh live tracking center panel updates
                    updateLiveTrackerDisplay(pkg, title, content)

                    // 2. Transmit across local network socket tunnel if link state is active
                    transmitLiveTelemetryFrame(pkg, title, content, ts)
                }
                "ACTION_PROCESS_INACTIVITY_BATCH" -> {
                    val rawMissedText = it.getStringExtra("EXTRA_BATCH_RAW_TEXT") ?: ""
                    requestAIInactivitySummary(rawMissedText)
                }
            }
        }

        return START_STICKY
    }

    private fun transmitLiveTelemetryFrame(pkg: String, title: String, content: String, ts: Double) {
        val payload = JSONObject().apply {
            put("type", "LIVE_TRACKER_SIGNAL")
            put("package_id", pkg)
            put("title", title)
            put("content", content)
            put("timestamp", ts)
        }
        WebSocketManager.getInstance().sendEvent(payload.toString())
    }

    private fun requestAIInactivitySummary(rawMissedText: String) {
        val payload = JSONObject().apply {
            put("type", "BATCH_SUMMARY_REQUEST")
            put("raw_payload", rawMissedText)
        }
        val dataString = payload.toString()

        val isConnected = WebSocketManager.getInstance().isConnected()
        if (isConnected) {
            WebSocketManager.getInstance().sendEvent(dataString)
        } else {
            // Local fallback rule logic handles execution context on local machine boundaries
            triggerSystemDigestAlert("Offline Summary Engine: You missed updates while away.")
        }
    }

    private fun updateLiveTrackerDisplay(pkg: String, title: String, content: String) {
        val cleanPkg = pkg.substringAfterLast(".")
        val updateText = "[$cleanPkg] $title: $content"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(TRACKER_NOTIFICATION_ID, buildTrackerNotification(updateText))
    }

    private fun triggerSystemDigestAlert(summaryText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alert = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FRIDAY Away Digest")
            .setContentText(summaryText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(DIGEST_NOTIFICATION_ID, alert)
    }

    private fun buildTrackerNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FRIDAY Live Telemetry Tracker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startContextPushLoop() {
        contextPushJob?.cancel()
        contextPushJob = serviceScope.launch {
            Log.i(TAG, "Context push loop started (session: ${contextBuilder?.sessionId})")
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
                    Log.d(TAG, "Hub not connected, context snapshot will be cached locally.")
                }

                try {
                    val contextObject = builder.buildAndReset()
                    wsManager.sendEvent(contextObject.toString())
                    Log.d(TAG, "Context snapshot pushed to hub.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to push context snapshot: ${e.message}")
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "FRIDAY Intelligence",
                NotificationManager.IMPORTANCE_DEFAULT
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
            Log.w(TAG, "Sensor receiver already unregistered: ${e.message}")
        }
        try {
            unregisterReceiver(digestReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Digest receiver already unregistered: ${e.message}")
        }
        discoveryManager.stopSearching()
        WebSocketManager.getInstance().disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
