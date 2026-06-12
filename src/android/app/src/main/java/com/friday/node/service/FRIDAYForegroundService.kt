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
import android.widget.RemoteViews
import android.graphics.Color
import android.util.TypedValue
import com.friday.node.R
import com.friday.node.utils.LocalFallbackEngine
import com.friday.node.data.remote.ContextObjectBuilder
import com.friday.node.data.remote.WebSocketManager
import com.friday.node.utils.BatteryOptimizer
import com.friday.node.utils.DiscoveryManager
import com.friday.node.utils.HealthKitManager
import com.friday.node.utils.WakeWordEngine
import com.friday.node.config.OnboardingConfigManager
import kotlinx.coroutines.*
import org.json.JSONObject

class FRIDAYForegroundService : Service() {

    private val TAG = "FRIDAY_ForegroundService"
    private val CHANNEL_ID = "FRIDAY_CORE_TELEMETRY"
    private val TRACKER_NOTIFICATION_ID = 4004
    private val DIGEST_NOTIFICATION_ID = 5005

    private lateinit var discoveryManager: DiscoveryManager
    private lateinit var wakeWordEngine: WakeWordEngine
    private var contextBuilder: ContextObjectBuilder? = null
    private var contextPushJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Rolling window of the last 9 stress score evaluations
    private val stressHistory = mutableListOf<Int>()

    // Cached last notification for stress level layout
    private var lastInterceptedPkg: String? = null
    private var lastInterceptedTitle: String? = null
    private var lastInterceptedContent: String? = null

    // Broadcast receiver to feed sensor events into the ContextObjectBuilder
    private val sensorFeedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val builder = contextBuilder ?: return
            when (intent?.action) {
                "com.friday.node.APP_SWITCH_DETECTED" -> {
                    val pkg = intent.getStringExtra("package_name") ?: "unknown"
                    builder.recordAppSwitch(pkg)
                    updateNotificationLayout()
                }
                "com.friday.node.TYPING_CADENCE_DETECTED" -> {
                    val delay = intent.getLongExtra("average_delay_ms", 0L)
                    builder.recordTypingCadence(delay)
                    updateNotificationLayout()
                }
                "com.friday.node.NOTIFICATION_INTERCEPTED" -> {
                    builder.recordNotification()
                    val pkg = intent.getStringExtra("package_name")
                    val title = intent.getStringExtra("title")
                    val content = intent.getStringExtra("content")
                    if (pkg != null && title != null && content != null) {
                        lastInterceptedPkg = pkg
                        lastInterceptedTitle = title
                        lastInterceptedContent = content
                    }
                    updateNotificationLayout()
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

    private fun updateNotificationLayout() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(TRACKER_NOTIFICATION_ID, buildCustomStressNotification())
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
        wakeWordEngine = WakeWordEngine(this)

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

        val baseNotification = buildCustomStressNotification()

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
                "ACTION_UPDATE_WAKE_WORD_STATE" -> {
                    val sharedPrefs = getSharedPreferences("friday_settings", Context.MODE_PRIVATE)
                    val wakeWordEnabled = sharedPrefs.getBoolean("wake_word_enabled", true)
                    Log.i(TAG, "ACTION_UPDATE_WAKE_WORD_STATE: wakeWordEnabled=$wakeWordEnabled")
                    if (wakeWordEnabled) {
                        wakeWordEngine.startListening {
                            startKeywordVerification()
                        }
                    } else {
                        wakeWordEngine.stopListening()
                    }
                }
                "ACTION_PAUSE_WAKE_WORD" -> {
                    Log.i(TAG, "ACTION_PAUSE_WAKE_WORD: Pausing background wake-word listening loop.")
                    wakeWordEngine.stopListening()
                }
                "ACTION_RESUME_WAKE_WORD" -> {
                    val sharedPrefs = getSharedPreferences("friday_settings", Context.MODE_PRIVATE)
                    val wakeWordEnabled = sharedPrefs.getBoolean("wake_word_enabled", true)
                    Log.i(TAG, "ACTION_RESUME_WAKE_WORD: Resuming wake-word listening (enabled=$wakeWordEnabled)")
                    if (wakeWordEnabled) {
                        wakeWordEngine.startListening {
                            startKeywordVerification()
                        }
                    } else {
                        wakeWordEngine.stopListening()
                    }
                }
            }
        }

        // Start listening for the wake word if enabled in settings
        val sharedPrefs = getSharedPreferences("friday_settings", Context.MODE_PRIVATE)
        val wakeWordEnabled = sharedPrefs.getBoolean("wake_word_enabled", true)
        if (wakeWordEnabled) {
            wakeWordEngine.startListening {
                startKeywordVerification()
            }
        } else {
            wakeWordEngine.stopListening()
        }

        return START_STICKY
    }

    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isVerifyingKeyword = false

    private fun startKeywordVerification() {
        if (isVerifyingKeyword) return
        isVerifyingKeyword = true

        // 1. Temporarily stop the wake word engine so it releases the microphone
        wakeWordEngine.stopListening()

        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
                }

                val recognizerIntent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }

                speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        Log.i(TAG, "Keyword recognizer ready for speech.")
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        Log.i(TAG, "Keyword recognizer end of speech.")
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "Keyword recognizer error: $error")
                        cleanupAndRestartWakeWord()
                    }

                    override fun onResults(results: android.os.Bundle?) {
                        val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                        var keywordDetected = false
                        if (matches != null) {
                            for (match in matches) {
                                Log.i(TAG, "Recognized text candidate: $match")
                                val text = match.lowercase()
                                if (text.contains("friday") || text.contains("hey friday") || text.contains("hi friday")) {
                                    keywordDetected = true
                                    break
                                }
                            }
                        }

                        if (keywordDetected) {
                            Log.i(TAG, "Keyword 'Hey Friday' confirmed!")
                            triggerWakeWordEvent()
                            isVerifyingKeyword = false
                        } else {
                            Log.i(TAG, "Keyword not found in recognized text. Restarting wake word engine.")
                            cleanupAndRestartWakeWord()
                        }
                    }

                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })

                speechRecognizer?.startListening(recognizerIntent)

                // Timeout safety: if SpeechRecognizer hangs or doesn't return, stop it after 5 seconds
                mainHandler.postDelayed({
                    if (isVerifyingKeyword) {
                        Log.w(TAG, "Keyword recognizer safety timeout reached.")
                        cleanupAndRestartWakeWord()
                    }
                }, 5000)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize/start SpeechRecognizer: ${e.message}")
                cleanupAndRestartWakeWord()
            }
        }
    }

    private fun cleanupAndRestartWakeWord() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {}
            isVerifyingKeyword = false
            
            val sharedPrefs = getSharedPreferences("friday_settings", Context.MODE_PRIVATE)
            val wakeWordEnabled = sharedPrefs.getBoolean("wake_word_enabled", true)
            if (wakeWordEnabled) {
                wakeWordEngine.startListening {
                    startKeywordVerification()
                }
            }
        }
    }

    private fun triggerWakeWordEvent() {
        Log.i(TAG, "Wake-word triggered! Launching FRIDAY voice assistant...")
        
        // 1. Send broadcast to MainActivity (if active)
        val broadcastIntent = Intent("com.friday.node.WAKE_WORD_DETECTED")
        sendBroadcast(broadcastIntent)
        
        // 2. Play a brief haptic feedback (vibration)
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to vibrate: ${e.message}")
        }

        // 3. Launch MainActivity with trigger_voice_assistant extra to show overlay
        try {
            val activityIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("trigger_voice_assistant", true)
            }
            if (activityIntent != null) {
                startActivity(activityIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-launch MainActivity: ${e.message}")
        }
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
        lastInterceptedPkg = pkg
        lastInterceptedTitle = title
        lastInterceptedContent = content
        
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(TRACKER_NOTIFICATION_ID, buildCustomStressNotification())
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

    private fun buildCustomStressNotification(): Notification {
        val builder = contextBuilder
        val appSwitches = builder?.appSwitchCount ?: 0
        val typingDelay = if ((builder?.typingEventCount ?: 0) > 0) {
            (builder?.totalTypingDelayMs ?: 0L) / (builder?.typingEventCount ?: 1)
        } else 0L
        val notifications = builder?.notificationCount ?: 0

        val result = LocalFallbackEngine.evaluateOfflineContext(appSwitches, typingDelay, notifications)
        val stressScore = result.stressScore
        val statusString = when (result.cognitiveLoadStatus) {
            "CRITICAL_OVERLOAD" -> "Overload"
            "ELEVATED_STRESS" -> "Elevated"
            else -> "Stable"
        }

        // Update rolling stress history
        stressHistory.add(stressScore)
        if (stressHistory.size > 9) {
            stressHistory.removeAt(0)
        }

        val recommendationText = if (lastInterceptedPkg != null && lastInterceptedTitle != null && lastInterceptedContent != null) {
            val cleanPkg = lastInterceptedPkg?.substringAfterLast(".") ?: ""
            "Latest Alert: [$cleanPkg] $lastInterceptedTitle: $lastInterceptedContent"
        } else {
            result.empatheticRecommendation
        }

        // Determine dynamic resources and colors depending on current stress level
        val pillBgRes: Int
        val pillTextColorHex: String
        val scoreColorHex: String

        when {
            stressScore >= 75 -> { // Critical Overload
                pillBgRes = R.drawable.pill_status_critical
                pillTextColorHex = "#721C24"
                scoreColorHex = "#EF4444"
            }
            stressScore >= 50 -> { // Elevated Stress
                pillBgRes = R.drawable.pill_status_elevated
                pillTextColorHex = "#856404"
                scoreColorHex = "#F59E0B"
            }
            else -> { // Stable / Calm
                pillBgRes = R.drawable.pill_status_stable
                pillTextColorHex = "#1E5A34"
                scoreColorHex = "#10B981"
            }
        }

        val dynamicTitle = when {
            stressScore >= 75 -> "FRIDAY: Overload Alert"
            stressScore >= 50 -> "FRIDAY: Elevated Stress"
            appSwitches > 12 -> "FRIDAY: High Switching"
            typingDelay > 2000L -> "FRIDAY: Typing Delay"
            notifications > 8 -> "FRIDAY: High Notification Load"
            else -> "FRIDAY: Optimal Flow"
        }

        val remoteViewsCollapsed = RemoteViews(packageName, R.layout.custom_stress_notification_collapsed).apply {
            setTextViewText(R.id.notif_title, dynamicTitle)
            setTextViewText(R.id.notif_score, "$stressScore/100")
            setTextViewText(R.id.notif_status_pill, statusString)
            setInt(R.id.notif_status_pill, "setBackgroundResource", pillBgRes)
            setTextColor(R.id.notif_status_pill, Color.parseColor(pillTextColorHex))
            setTextColor(R.id.notif_score, Color.parseColor(scoreColorHex))
            setInt(R.id.notif_icon_bg, "setColorFilter", Color.parseColor(scoreColorHex))
        }

        val remoteViewsExpanded = RemoteViews(packageName, R.layout.custom_stress_notification).apply {
            setTextViewText(R.id.notif_title, dynamicTitle)
            setTextViewText(R.id.notif_score, stressScore.toString())
            setTextViewText(R.id.notif_status_pill, statusString)
            setInt(R.id.notif_status_pill, "setBackgroundResource", pillBgRes)
            setTextColor(R.id.notif_status_pill, Color.parseColor(pillTextColorHex))
            setTextColor(R.id.notif_score, Color.parseColor(scoreColorHex))
            setInt(R.id.notif_icon_bg, "setColorFilter", Color.parseColor(scoreColorHex))
            setTextViewText(R.id.notif_description, recommendationText)

            // Setup dynamic trend bars based on rolling history
            val displayHistory = ArrayList<Int>()
            val padCount = 9 - stressHistory.size
            for (i in 0 until padCount) {
                // Pad early history with a gentle ramp
                displayHistory.add(20 + i * 5)
            }
            displayHistory.addAll(stressHistory)

            val barIds = arrayOf(
                R.id.notif_bar_1, R.id.notif_bar_2, R.id.notif_bar_3,
                R.id.notif_bar_4, R.id.notif_bar_5, R.id.notif_bar_6,
                R.id.notif_bar_7, R.id.notif_bar_8, R.id.notif_bar_9
            )

            for (i in 0..8) {
                val histScore = displayHistory[i]
                val barId = barIds[i]

                // Proportional height (min 8dp, max 42dp)
                val heightDp = 8 + (histScore.toFloat() / 100f * 34f).toInt()

                // Capsule color matching stress level
                val barCapsuleRes = when {
                    histScore >= 75 -> R.drawable.capsule_red
                    histScore >= 50 -> R.drawable.capsule_orange
                    else -> R.drawable.capsule_green
                }

                setInt(barId, "setBackgroundResource", barCapsuleRes)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setViewLayoutHeight(barId, heightDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                }
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.friday.node.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setCustomContentView(remoteViewsCollapsed)
            .setCustomBigContentView(remoteViewsExpanded)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
            wakeWordEngine.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop wake-word engine: ${e.message}")
        }
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
