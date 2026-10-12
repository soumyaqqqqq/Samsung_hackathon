package com.friday.node

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.friday.node.data.local.RoomDatabase
import com.friday.node.data.remote.WebSocketManager
import com.friday.node.data.remote.VoiceAssistantManager
import com.friday.node.data.remote.VoiceState
import com.friday.node.service.FRIDAYForegroundService
import com.friday.node.utils.LocalFallbackEngine
import com.friday.node.utils.DiscoveryManager
import com.friday.node.config.OnboardingConfigManager
import com.friday.node.data.remote.OnboardingService
import com.friday.node.onboarding.viewmodel.OnboardingViewModel
import com.friday.node.onboarding.viewmodel.OnboardingViewModelFactory
import com.friday.node.onboarding.ui.screens.OnboardingScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

data class TimelineEvent(
    val title: String,
    val description: String,
    val time: String,
    val type: String
)

class MainActivity : ComponentActivity() {

    private val TAG = "FRIDAY_MainActivity"
    private lateinit var voiceAssistantManager: VoiceAssistantManager

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ── Compose State ────────────────────────────────────────────────────────────
    private var showProactiveOverlay = mutableStateOf(false)
    private var assistantState = mutableStateOf(VoiceState.IDLE)
    private var transcriptText = mutableStateOf("")
    private var assistantResponse = mutableStateOf("")
    private var errorMessage = mutableStateOf("")
    private var connectionStatus = mutableStateOf("Searching for Hub...")
    private var isConnected = mutableStateOf(false)
    private var stressScore = mutableStateOf(0)
    private var appSwitchesCount = mutableStateOf(0)
    private var averageTypingCadenceMs = mutableStateOf(0L)
    private var notificationsCount = mutableStateOf(0)
    private var bufferedEventsCount = mutableStateOf(0)
    private var recentApp = mutableStateOf("None")
    private var lastNotification = mutableStateOf("No notifications yet")
    private var wellbeingPrompt = mutableStateOf("FRIDAY ready. Baseline tracking active.")
    private var lastPromptUpdateTimeMs = 0L
    private var isGhostMode = mutableStateOf(false)
    private var activeActionCard = mutableStateOf<JSONObject?>(null)
    private var isMemoryMomentDismissed = mutableStateOf(false)

    // ── User Profile ─────────────────────────────────────────────────────────────
    private var userName = mutableStateOf("")
    private var userRole = mutableStateOf("")
    private var productivityPercentage = mutableStateOf("Learning...")
    private var peakFocusTime = mutableStateOf("Learning...")
    private var stressSpikeTime = mutableStateOf("None")
    private var focusPercentage = mutableStateOf("Learning...")
    private var focusImprovementPercent = mutableStateOf("0%")
    private var confidencePercentage = mutableStateOf("Learning...")
    private var memoryMatchPercentage = mutableStateOf("Learning...")

    // ── Telemetry ────────────────────────────────────────────────────────────────
    private var sessionStartTimeMs = System.currentTimeMillis()
    private var focusTimeDisplay = mutableStateOf("0s")
    private var locationState = mutableStateOf("home")
    private var isLaptopConnected = mutableStateOf(false)
    private var attentionTasks = mutableStateListOf<JSONObject>()
    private val timelineEvents = mutableStateListOf<TimelineEvent>()
    private val activityIntensities = mutableStateListOf<Float>().apply { repeat(16) { add(0.02f) } }
    private val recentLaptopSites = mutableStateListOf<Triple<String, String, String>>()
    private var lastDeviceSwitchSite = mutableStateOf<String?>(null)
    private var activeTaskName = mutableStateOf("")
    private val activeTaskSteps = mutableStateListOf<Pair<String, String>>()

    // ── Settings State ───────────────────────────────────────────────────────────
    private var hubIp = mutableStateOf("")
    private var hubPort = mutableStateOf("8000")
    private var isFocusModeActive = mutableStateOf(false)
    private var isReduceInterruptActive = mutableStateOf(false)
    private val localMemories = mutableStateListOf(
        "User prefers working in dark mode on mobile and desktop.",
        "Typically starts evening wind-down around 9:30 PM.",
        "Likes to focus on coding during morning hours (9 AM - 12 PM).",
        "Prefers minimal notifications during high-focus sessions.",
        "VS Code is the primary development workspace."
    )
    private val localDevices = mutableStateListOf("Galaxy S26" to "ACTIVE", "Work Laptop" to "ACTIVE")

    // ── Dialog Flags ─────────────────────────────────────────────────────────────
    private var showMemoriesDialog = mutableStateOf(false)
    private var showDevicesDialog = mutableStateOf(false)
    private var showExplainStressDialog = mutableStateOf(false)
    private var showClearMemoriesConfirmDialog = mutableStateOf(false)
    private var showDisconnectDevicesConfirmDialog = mutableStateOf(false)

    // ── Model Download & AI State ────────────────────────────────────────────────
    private var isModelDownloadedState = mutableStateOf(false)
    private var isDownloadingModel = mutableStateOf(false)
    private var downloadProgress = mutableStateOf(0f)
    private var showConsentDialog = mutableStateOf(false)

    // ── Permission State ─────────────────────────────────────────────────────────
    private var isAccessibilityGranted = mutableStateOf(false)
    private var isNotificationAccessGranted = mutableStateOf(false)
    private var isPostNotificationGranted = mutableStateOf(false)

    // ── Broadcast Receiver ───────────────────────────────────────────────────────
    private val telemetryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            val timeStr = sdf.format(Date())
            when (intent?.action) {
                "com.friday.node.APP_SWITCH_DETECTED" -> {
                    val pkg = intent.getStringExtra("package_name") ?: "Unknown"
                    recentApp.value = pkg
                    appSwitchesCount.value += 1
                    recalculateLocalStress()
                    timelineEvents.add(0, TimelineEvent("App Shift", "Opened $pkg", timeStr, "app"))
                    if (timelineEvents.size > 50) timelineEvents.removeLast()
                }
                "com.friday.node.TYPING_CADENCE_DETECTED" -> {
                    averageTypingCadenceMs.value = intent.getLongExtra("average_delay_ms", 0L)
                    recalculateLocalStress()
                }
                "com.friday.node.NOTIFICATION_INTERCEPTED" -> {
                    val pkg = intent.getStringExtra("package_name") ?: ""
                    val title = intent.getStringExtra("title") ?: ""
                    val content = intent.getStringExtra("content") ?: ""
                    val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
                    lastNotification.value = "$title: $content ($pkg)"
                    notificationsCount.value += 1
                    recalculateLocalStress()
                    timelineEvents.add(0, TimelineEvent("Alert Muted", "$title ($pkg)", timeStr, "notification"))
                    if (timelineEvents.size > 50) timelineEvents.removeLast()
                    attentionTasks.add(JSONObject().apply {
                        put("type", "notification")
                        put("action_id", "notif_${timestamp}_${pkg.hashCode()}")
                        put("package", pkg)
                        put("title", title)
                        put("message", content)
                        put("agent", getAppNameFromPackage(pkg))
                        put("timestamp", timestamp)
                    })
                    if (attentionTasks.size > 20) attentionTasks.removeAt(0)
                }
                "com.friday.node.BATTERY_MODE_CHANGED" -> {
                    isGhostMode.value = (intent.getStringExtra("current_mode") ?: "ACTIVE") == "GHOST"
                }
                "com.friday.node.CONNECTION_STATE_CHANGED" -> {
                    val connected = intent.getBooleanExtra("is_connected", false)
                    isConnected.value = connected
                    connectionStatus.value = if (connected) "Connected to Hub" else "Hub Offline (Caching)"
                }
                "com.friday.node.LOCATION_CHANGED" -> {
                    val loc = intent.getStringExtra("location") ?: "home"
                    locationState.value = loc
                    timelineEvents.add(0, TimelineEvent("Location Shift", "Moved to ${loc.uppercase(Locale.getDefault())}", timeStr, "location"))
                }
                "com.friday.node.ACTION_RECEIVED" -> {
                    val payload = intent.getStringExtra("action_payload") ?: ""
                    try {
                        val json = JSONObject(payload)
                        val type = json.optString("type", "")

                        if (json.has("laptop_active")) {
                            isLaptopConnected.value = json.optBoolean("laptop_active", false)
                            val recentTabsArr = json.optJSONArray("recent_tabs")
                            if (recentTabsArr != null && recentTabsArr.length() > 0) {
                                recentLaptopSites.clear()
                                for (i in 0 until minOf(recentTabsArr.length(), 3)) {
                                    val tab = recentTabsArr.optJSONObject(i) ?: continue
                                    val title = tab.optString("title", "Untitled")
                                    val displayUrl = tab.optString("url", "")
                                    val fullUrl = tab.optString("link", displayUrl)
                                    recentLaptopSites.add(Triple(title, displayUrl, fullUrl))
                                }
                                val topSite = recentLaptopSites.firstOrNull()
                                if (topSite != null && topSite.first != lastDeviceSwitchSite.value) {
                                    lastDeviceSwitchSite.value = topSite.first
                                    timelineEvents.add(0, TimelineEvent("Device Switch", "Last on Laptop: ${topSite.first}", timeStr, "continuity"))
                                }
                            }
                        }

                        when (type) {
                            "CLEAR_HANDOFF" -> {
                                val actionId = json.optString("action_id", "")
                                val reason = json.optString("reason", "dismissed")
                                if (actionId.isNotEmpty()) {
                                    attentionTasks.removeAll { it.optString("action_id") == actionId }
                                    if (activeActionCard.value?.optString("action_id") == actionId) activeActionCard.value = null
                                    val t = if (reason == "resumed") "Handoff Resumed" else "Handoff Dismissed"
                                    val m = if (reason == "resumed") "Resumed laptop task" else "Dismissed laptop task"
                                    timelineEvents.add(0, TimelineEvent(t, m, timeStr, "continuity"))
                                }
                            }
                            "FRIDAY_CARD", "MEDIA_HANDOFF", "PAGE_HANDOFF" -> {
                                activeActionCard.value = json
                                val message = json.optString("message", "")
                                if (message.isNotEmpty()) {
                                    wellbeingPrompt.value = message
                                    if (attentionTasks.none { it.optString("action_id") == json.optString("action_id") }) attentionTasks.add(json)
                                    val agent = json.optString("agent", "Orchestrator")
                                    val eventType = if (type == "FRIDAY_CARD") "suggestion" else "continuity"
                                    val eventTitle = if (type == "FRIDAY_CARD") "FRIDAY Action" else "Continuity Shift"
                                    timelineEvents.add(0, TimelineEvent(eventTitle, "[$agent Agent] $message", timeStr, eventType))
                                }
                            }
                            "LAPTOP_STATUS" -> { /* handled above via laptop_active */ }
                            else -> {
                                val suggestion = json.optString("suggested_action")
                                if (!suggestion.isNullOrEmpty()) wellbeingPrompt.value = suggestion
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse Action payload: ${e.message}")
                    }
                }
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────
    private fun startFridayServices(fromOnboarding: Boolean = false) {
        try {
            startForegroundService(Intent(this, FRIDAYForegroundService::class.java).apply {
                putExtra("from_onboarding", fromOnboarding)
            })
        } catch (e: Exception) { Log.e(TAG, "Failed to launch foreground service: ${e.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WebSocketManager.getInstance().init(this)
        voiceAssistantManager = VoiceAssistantManager(this).apply {
            onStateChanged = { assistantState.value = it }
            onResultReceived = { transcript, response ->
                transcriptText.value = transcript
                assistantResponse.value = response
                if (!showProactiveOverlay.value) showToast("FRIDAY: $response")
            }
            onErrorOccurred = { error ->
                errorMessage.value = error
                if (!showProactiveOverlay.value) showToast("FRIDAY Error: $error")
            }
        }

        val configManager = OnboardingConfigManager(this)
        val isOnboardingComplete = configManager.isOnboardingComplete()
        userName.value = configManager.getUserName().ifEmpty { getString(R.string.default_user_name) }
        userRole.value = getString(R.string.default_user_role)

        if (isOnboardingComplete && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }
        if (isOnboardingComplete) {
            startFridayServices()
            checkPermissionsState()
            recalculateLocalStress()
        }

        // Event-driven buffer count update (no continuous polling loop)
        updateBufferedEventsCount()
        isModelDownloadedState.value = LocalFallbackEngine.isModelDownloaded(this)
        isDownloadingModel.value = LocalFallbackEngine.isDownloading()

        val filter = IntentFilter().apply {
            addAction("com.friday.node.APP_SWITCH_DETECTED")
            addAction("com.friday.node.TYPING_CADENCE_DETECTED")
            addAction("com.friday.node.NOTIFICATION_INTERCEPTED")
            addAction("com.friday.node.BATTERY_MODE_CHANGED")
            addAction("com.friday.node.CONNECTION_STATE_CHANGED")
            addAction("com.friday.node.ACTION_RECEIVED")
            addAction("com.friday.node.LOCATION_CHANGED")
        }
        registerReceiver(telemetryReceiver, filter, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else RECEIVER_EXPORTED)

        setContent {
            val showOnboarding = remember { mutableStateOf(!isOnboardingComplete) }
            FridayTheme {
                if (showOnboarding.value) {
                    val onboardingService = OnboardingService(this@MainActivity, configManager)
                    val factory = OnboardingViewModelFactory(configManager, onboardingService)
                    val vm = androidx.lifecycle.ViewModelProvider(this@MainActivity, factory)[OnboardingViewModel::class.java]
                    val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "android_node"
                    OnboardingScreen(viewModel = vm, deviceId = deviceId, onOnboardingFinished = {
                        userName.value = configManager.getUserName().ifEmpty { userName.value }
                        showOnboarding.value = false
                        // Onboarding complete: launch services immediately using instant heuristic fallback (<10ms, 0MB)
                        startFridayServices(fromOnboarding = true)
                        checkPermissionsState()
                        recalculateLocalStress()
                        updateBufferedEventsCount()
                    })
                } else {
                    MainContainer()
                    RenderSettingsDialogs()
                }
            }
        }
    }

    private var sessionTimerJob: kotlinx.coroutines.Job? = null

    private fun startSessionTimer() {
        sessionTimerJob?.cancel()
        sessionTimerJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                val ms = System.currentTimeMillis() - sessionStartTimeMs
                val mins = (ms / 60000).toInt()
                val secs = ((ms / 1000) % 60).toInt()
                val formatted = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
                if (formatted != focusTimeDisplay.value) {
                    withContext(Dispatchers.Main) {
                        focusTimeDisplay.value = formatted
                    }
                }
                delay(1000)
            }
        }
    }

    private fun stopSessionTimer() {
        sessionTimerJob?.cancel()
        sessionTimerJob = null
    }

    private fun updateBufferedEventsCount() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = RoomDatabase.getInstance(this@MainActivity)
                val count = db.getEventCount()
                withContext(Dispatchers.Main) {
                    bufferedEventsCount.value = count
                }
            } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        val connected = WebSocketManager.getInstance().isConnected()
        isConnected.value = connected
        connectionStatus.value = if (connected) "Connected to Hub" else "Searching for Hub..."
        if (connected) triggerTelemetryRefresh {}
        checkPermissionsState()
        isModelDownloadedState.value = LocalFallbackEngine.isModelDownloaded(this)
        isDownloadingModel.value = LocalFallbackEngine.isDownloading()
        updateBufferedEventsCount()
        startSessionTimer()
    }

    override fun onPause() {
        super.onPause()
        stopSessionTimer()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { voiceAssistantManager.cancel() } catch (_: Exception) {}
        try { unregisterReceiver(telemetryReceiver) } catch (e: Exception) { Log.e(TAG, "Unregister failed: ${e.message}") }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────
    private fun getDynamicGreeting(name: String): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return getString(when (hour) {
            in 0..11 -> R.string.greeting_morning
            in 12..16 -> R.string.greeting_afternoon
            in 17..21 -> R.string.greeting_evening
            else -> R.string.greeting_night
        }, name)
    }

    private fun getDynamicDate(): String = try {
        SimpleDateFormat("EEEE,\nMMMM d, yyyy", Locale.getDefault()).format(Date())
    } catch (_: Exception) { "Sunday,\nJune 14, 2026" }

    private fun getMotivatingSubtitle(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Rise and shine! A fresh start to achieve your goals today."
            in 12..16 -> "Keep going! You're making great progress. Stay focused."
            in 17..21 -> "Great job today. Take a moment to appreciate your effort."
            else -> "Rest well. Sleep is the fuel for tomorrow's success."
        }
    }

    private fun simulateNetworkDrop(context: Context) {
        showToast("Simulating Network Failure...")
        // Disconnect WebSocket to force offline mode
        WebSocketManager.getInstance().disconnect()
        
        // Trigger local BPE fallback tokenization
        val prompt = "User state: high stress detected. Recommendation: active coaching required."
        val tokenIds = com.friday.node.utils.LocalFallbackEngine.LlamaTokenizer.encode(prompt)
        val tokenListStr = tokenIds.joinToString(", ")
        Log.i("FRIDAY_OFFLINE_DEMO", "Offline Fallback Tokenizer Active!")
        Log.i("FRIDAY_OFFLINE_DEMO", "Input Prompt: \"$prompt\"")
        Log.i("FRIDAY_OFFLINE_DEMO", "Generated BPE Token IDs: [$tokenListStr]")
        
        // Run rule-based or mock ONNX inference to verify
        val mockContext = JSONObject().apply {
            val meta = JSONObject().apply {
                put("session_id", "demo_samsung_phone")
                put("timestamp", System.currentTimeMillis())
            }
            val sensor = JSONObject().apply {
                put("focused_app", "com.android.chrome")
                put("typo_rate", 0.18)
                put("app_switches", 12)
            }
            val user = JSONObject().apply {
                put("stress_score", 88)
            }
            put("metadata", meta)
            put("sensor_data", sensor)
            put("user_state", user)
        }
        
        // Directly send context through local fallback to trigger action card
        CoroutineScope(Dispatchers.Main).launch {
            val result = com.friday.node.utils.LocalFallbackEngine.processOfflineContext(mockContext)
            Log.i("FRIDAY_OFFLINE_DEMO", "Local Fallback Inference: ${result.message}")
            showToast("Offline fallback active: ${result.message.take(30)}...")
            
            val cardPayload = JSONObject().apply {
                put("type", "FRIDAY_CARD")
                put("action_id", "act_offline_${System.currentTimeMillis()}")
                put("message", result.message)
                put("agent", result.agent)
                put("score", result.stressScore ?: 88)
                put("condition", "offline_fallback")
                put("tier", result.tier)
                put("timestamp", System.currentTimeMillis())
            }
            
            activeActionCard.value = cardPayload
        }
    }

    private fun simulateReconnect(context: Context) {
        showToast("Reconnecting Hub...")
        // Try to connect manually to our normal ws url
        val ip = hubIp.value.ifEmpty { "10.0.2.2" } // default emulator host loopback
        val port = hubPort.value.ifEmpty { "8000" }
        WebSocketManager.getInstance().connect("ws://$ip:$port/ws/android")
    }

    private fun getAppNameFromPackage(pkg: String): String = when {
        pkg.contains("whatsapp", true) -> "WhatsApp"
        pkg.contains("slack", true) -> "Slack"
        pkg.contains("gmail", true) || pkg.contains("android.gm", true) -> "Gmail"
        pkg.contains("telegram", true) -> "Telegram"
        pkg.contains("discord", true) -> "Discord"
        pkg.contains("teams", true) -> "MS Teams"
        pkg.contains("instagram", true) -> "Instagram"
        pkg.contains("facebook", true) -> "Facebook"
        pkg.contains("twitter", true) -> "Twitter"
        else -> pkg.split(".").lastOrNull()?.replaceFirstChar { it.titlecase(Locale.getDefault()) } ?: "App"
    }

    private fun getSafeTimestamp(json: JSONObject): Long {
        val ts = json.optLong("timestamp", 0L)
        return when {
            ts == 0L -> System.currentTimeMillis()
            ts < 10_000_000_000L -> ts * 1000L
            else -> ts
        }
    }

    private fun getProcessedAttentionTasks(tasks: List<JSONObject>): List<JSONObject> {
        val cards = tasks.filter { it.optString("type") != "notification" }
        val groupedNotifs = tasks.filter { it.optString("type") == "notification" }
            .groupBy { it.optString("package", it.optString("agent")) }

        val processedNotifs = groupedNotifs.map { (pkg, group) ->
            if (group.size == 1) return@map group[0]
            val sorted = group.sortedByDescending { getSafeTimestamp(it) }
            val agentName = if (pkg.contains(".")) getAppNameFromPackage(pkg) else pkg
            val summary = "${group.size} notifications: " + sorted.take(3).joinToString("; ") {
                val t = it.optString("title", ""); val m = it.optString("message", "")
                if (t.isNotEmpty()) "$t: $m" else m
            } + if (group.size > 3) "..." else ""
            JSONObject().apply {
                put("type", "notification"); put("action_id", sorted[0].optString("action_id"))
                put("package", pkg); put("agent", agentName)
                put("message", summary); put("timestamp", getSafeTimestamp(sorted[0]))
            }
        }
        return (cards + processedNotifs).sortedByDescending { getSafeTimestamp(it) }.take(6)
    }

    private fun checkPermissionsState() {
        val configManager = OnboardingConfigManager(this)
        val accEnabled = isAccessibilityServiceEnabled(this, com.friday.node.service.FRIDAYAccessibilityService::class.java)
        isAccessibilityGranted.value = accEnabled
        configManager.setModuleEnabled("Accessibility & Touch", accEnabled)

        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val notifEnabled = listeners != null && listeners.contains(packageName)
        isNotificationAccessGranted.value = notifEnabled
        configManager.setModuleEnabled("Notification & Activity", notifEnabled)

        val postNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        else true
        isPostNotificationGranted.value = postNotif

        val locGranted = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        configManager.setModuleEnabled("Location & Environment", locGranted)
    }

    private var lastStressCalcTimeMs = 0L

    private fun recalculateLocalStress() {
        val now = System.currentTimeMillis()
        // Debounce to at most once per 200ms to eliminate UI thread looper saturation
        if (now - lastStressCalcTimeMs < 200L) return
        lastStressCalcTimeMs = now

        val switches = appSwitchesCount.value
        val cadence = averageTypingCadenceMs.value
        val notifs = notificationsCount.value

        CoroutineScope(Dispatchers.Default).launch {
            val result = LocalFallbackEngine.evaluateOfflineContext(
                appSwitchesCount = switches,
                averageTypingCadenceMs = cadence,
                notificationsPerMin = notifs,
                context = this@MainActivity
            )
            withContext(Dispatchers.Main) {
                stressScore.value = result.stressScore
                if ((now - lastPromptUpdateTimeMs >= 60_000L || wellbeingPrompt.value.startsWith("FRIDAY ready"))
                    && (!isConnected.value || activeActionCard.value == null)) {
                    val newPrompt = result.empatheticRecommendation
                    if (newPrompt != wellbeingPrompt.value) {
                        wellbeingPrompt.value = newPrompt
                        lastPromptUpdateTimeMs = now
                    }
                }
                val intensity = (result.stressScore / 100f).coerceIn(0.02f, 1f)
                if (activityIntensities.size >= 16) activityIntensities.removeAt(0)
                activityIntensities.add(intensity)

                productivityPercentage.value = "${(96 - switches * 2 - notifs).coerceIn(10, 100)}%"
                focusPercentage.value = "${(92 - switches * 3).coerceIn(10, 100)}%"
                confidencePercentage.value = "${(88 - result.stressScore / 5).coerceIn(10, 100)}%"
                memoryMatchPercentage.value = "${(82 + switches).coerceIn(50, 99)}%"
                if (result.stressScore > 60) {
                    stressSpikeTime.value = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                }
            }
        }
    }

    private fun triggerTelemetryRefresh(onComplete: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val connected = WebSocketManager.getInstance().isConnected()
            val eventCount = RoomDatabase.getInstance(this@MainActivity).getEventCount()
            if (connected) {
                try {
                    WebSocketManager.getInstance().sendEvent(JSONObject().apply {
                        put("type", "sync_request")
                        put("timestamp", System.currentTimeMillis())
                    }.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Sync request failed: ${e.message}")
                }
            }
            withContext(Dispatchers.Main) {
                isConnected.value = connected
                connectionStatus.value = if (connected) "Connected to Hub" else "Searching for Hub..."
                bufferedEventsCount.value = eventCount
                checkPermissionsState()
                recalculateLocalStress()
            }
            delay(400)
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
        val expected = ComponentName(context, service)
        val setting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(setting) }
        while (splitter.hasNext()) {
            val c = ComponentName.unflattenFromString(splitter.next())
            if (c != null && c == expected) return true
        }
        return false
    }

    private fun handleAttentionTaskClick(task: JSONObject) {
        val isNotif = task.optString("type") == "notification"
        val actionId = task.optString("action_id")
        if (isNotif) {
            val pkg = task.optString("package")
            attentionTasks.removeAll { it.optString("package") == pkg || it.optString("action_id") == actionId }
            showToast("Alert cleared")
        } else {
            val message = task.optString("message", "Attention required")
            activeTaskName.value = "Optimize: $message"
            activeTaskSteps.clear()
            activeTaskSteps.addAll(listOf("Analyze VS Code metrics" to "COMPLETED", "Verify DB query efficiency" to "IN_PROGRESS", "Generate execution plan" to "PENDING"))
            showToast("Journey started: $message")
        }
    }

    private fun handleActionDismiss(actionId: String) {
        WebSocketManager.getInstance().sendFeedback(actionId, "dismissed")
        attentionTasks.removeAll { it.optString("action_id") == actionId }
        activeActionCard.value = null
        wellbeingPrompt.value = "Ambient tracking active. System stable."
        timelineEvents.add(0, TimelineEvent("Handoff Dismissed", "Dismissed laptop task", SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()), "continuity"))
        showToast("Recommendation dismissed")
    }

    private fun handleActionAccept(card: JSONObject) {
        val actionId = card.optString("action_id", "")
        val type = card.optString("type", "")
        val isHandoff = type == "MEDIA_HANDOFF" || type == "PAGE_HANDOFF"
        val url = if (type == "MEDIA_HANDOFF")
            "https://www.youtube.com/watch?v=${card.optString("video_id")}&t=${card.optInt("playback_timestamp_seconds")}s"
        else card.optString("url", "")

        WebSocketManager.getInstance().sendFeedback(actionId, "helpful")
        attentionTasks.removeAll { it.optString("action_id") == actionId }
        activeActionCard.value = null
        if (isHandoff && url.isNotEmpty()) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }) }
            catch (e: Exception) { Log.e(TAG, "URL launch failed: ${e.message}") }
        }
        wellbeingPrompt.value = "Feedback logged. Optimizing model."
        timelineEvents.add(0, TimelineEvent("Handoff Resumed", "Resumed laptop task", SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()), "continuity"))
        showToast(if (isHandoff) "Task resumed!" else "Thank you for your feedback!")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // MAIN CONTAINER
    // ─────────────────────────────────────────────────────────────────────────────
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainContainer() {
        val dashboardScrollState = rememberScrollState()
        val mindScrollState = rememberScrollState()
        val continuityScrollState = rememberScrollState()
        val settingsScrollState = rememberScrollState()
        var activeTab by remember { mutableStateOf(0) }
        var isRefreshing by remember { mutableStateOf(false) }

        Scaffold(
            bottomBar = {
                CustomBottomNavBar(
                    activeTab = activeTab,
                    onTabSelected = { activeTab = it; showProactiveOverlay.value = false },
                    onSparkClicked = { showProactiveOverlay.value = !showProactiveOverlay.value }
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                when (activeTab) {
                    0 -> {
                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = { isRefreshing = true; triggerTelemetryRefresh { isRefreshing = false } },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            DashboardTab(
                                scrollState = dashboardScrollState,
                                userName = userName.value,
                                isConnected = isConnected.value,
                                connectionStatus = connectionStatus.value,
                                stressScore = stressScore.value,
                                attentionTasks = attentionTasks,
                                activeActionCard = activeActionCard.value,
                                onActionDismiss = ::handleActionDismiss,
                                onActionAccept = ::handleActionAccept,
                                onAttentionClick = ::handleAttentionTaskClick
                            )
                        }
                    }
                    1 -> MindTab(
                        scrollState = mindScrollState,
                        stress = stressScore.value,
                        appSwitches = appSwitchesCount.value,
                        typingCadenceMs = averageTypingCadenceMs.value,
                        notificationsCount = notificationsCount.value,
                        focusPercentage = focusPercentage.value,
                        confidencePercentage = confidencePercentage.value,
                        memoryMatchPercentage = memoryMatchPercentage.value,
                        locationState = locationState.value,
                        focusTimeDisplay = focusTimeDisplay.value,
                        isGhostMode = isGhostMode.value,
                        isFocusModeActive = isFocusModeActive.value,
                        isReduceInterruptActive = isReduceInterruptActive.value,
                        onToggleFocusMode = {
                            isFocusModeActive.value = !isFocusModeActive.value
                            if (isFocusModeActive.value) { sessionStartTimeMs = System.currentTimeMillis(); focusTimeDisplay.value = "0s"; showToast("Focus mode activated!") }
                            else showToast("Focus mode deactivated!")
                        },
                        onToggleReduceInterrupt = {
                            isReduceInterruptActive.value = !isReduceInterruptActive.value
                            showToast(if (isReduceInterruptActive.value) "Interruption blocker active." else "Deactivated.")
                        },
                        onExplainStress = { showExplainStressDialog.value = true }
                    )
                    2 -> ContinuityTab(
                        scrollState = continuityScrollState,
                        stressScore = stressScore.value,
                        productivityPercentage = productivityPercentage.value,
                        peakFocusTime = peakFocusTime.value,
                        focusTimeDisplay = focusTimeDisplay.value,
                        stressSpikeTime = stressSpikeTime.value,
                        locationState = locationState.value,
                        appSwitchesCount = appSwitchesCount.value,
                        notificationsCount = notificationsCount.value,
                        isLaptopConnected = isLaptopConnected.value,
                        activeTaskName = activeTaskName.value,
                        activeTaskSteps = activeTaskSteps,
                        attentionTasks = attentionTasks,
                        recentLaptopSites = recentLaptopSites,
                        timelineEvents = timelineEvents,
                        activityIntensities = activityIntensities,
                        isMemoryMomentDismissed = isMemoryMomentDismissed.value,
                        onDismissMemoryMoment = { isMemoryMomentDismissed.value = true },
                        onPrioritizeTasks = {
                            val sorted = attentionTasks.sortedByDescending { it.optDouble("score", 0.0) }
                            attentionTasks.clear()
                            attentionTasks.addAll(sorted)
                            showToast("Tasks prioritized by urgency score.")
                        },
                        onSimulateJourney = {
                            val mock = JSONObject().apply {
                                put("type", "FRIDAY_CARD")
                                put("action_id", "act_sim_01")
                                put("message", "High CPU on VS Code detected. Run profile_schema.py to inspect queries.")
                                put("agent", "Decision")
                                put("score", 0.94)
                                put("laptop_active", true)
                            }
                            attentionTasks.add(mock)
                            activeActionCard.value = mock
                            isLaptopConnected.value = true
                            showToast("Workspace journey simulated!")
                        },
                        onAttentionClick = ::handleAttentionTaskClick,
                        onOpenUrl = { url ->
                            if (url.isNotEmpty()) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }) } catch (e: Exception) { Log.e(TAG, "URL open failed: ${e.message}") }
                        },
                        recentApp = recentApp.value,
                        lastNotification = lastNotification.value,
                        bufferedEventsCount = bufferedEventsCount.value
                    )
                    3 -> SettingsTab(
                        scrollState = settingsScrollState,
                        userName = userName.value,
                        userRole = userRole.value,
                        isAccessibilityGranted = isAccessibilityGranted.value,
                        isNotificationAccessGranted = isNotificationAccessGranted.value,
                        isPostNotificationGranted = isPostNotificationGranted.value,
                        localMemoriesCount = localMemories.size,
                        bufferedEventsCount = bufferedEventsCount.value,
                        isModelDownloaded = isModelDownloadedState.value,
                        isDownloadingModel = isDownloadingModel.value,
                        downloadProgress = downloadProgress.value,
                        localDevices = localDevices,
                        hubIp = hubIp.value,
                        hubPort = hubPort.value,
                        onHubIpChanged = { hubIp.value = it },
                        onHubPortChanged = { hubPort.value = it },
                        onConnectHub = {
                            if (hubIp.value.isNotEmpty()) WebSocketManager.getInstance().connect("ws://${hubIp.value}:${hubPort.value}/ws/android")
                        },
                        onManageMemories = { showMemoriesDialog.value = true },
                        onManageDevices = { showDevicesDialog.value = true },
                        onDownloadModel = { showConsentDialog.value = true },
                        onDeleteModel = {
                            LocalFallbackEngine.deleteModel(this@MainActivity)
                            isModelDownloadedState.value = false
                            showToast("Model deleted. Reclaimed 2.3 GB of storage.")
                        },
                        onCancelDownload = {
                            LocalFallbackEngine.cancelDownload(this@MainActivity)
                            isDownloadingModel.value = false
                            downloadProgress.value = 0f
                            showToast("Model download cancelled.")
                        },
                        onSimulateDrop = { simulateNetworkDrop(this@MainActivity) },
                        onSimulateReconnect = { simulateReconnect(this@MainActivity) },
                        onDisconnectDevicesConfirm = { showDisconnectDevicesConfirmDialog.value = true },
                        onClearMemoriesConfirm = { showClearMemoriesConfirmDialog.value = true },
                        onRequestAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onRequestNotificationAccess = { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) },
                        onRequestPostNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
                            }
                        },
                        onOpenPermissionManager = {
                            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) }) }
                            catch (_: Exception) { showToast(getString(R.string.msg_open_permission_manager)) }
                        },
                        onToast = ::showToast
                    )
                }
                if (showProactiveOverlay.value) {
                    ProactiveOverlayScreen(
                        assistantState = assistantState.value,
                        transcriptText = transcriptText.value,
                        assistantResponse = assistantResponse.value,
                        errorMessage = errorMessage.value,
                        onMicClick = {
                            when (assistantState.value) {
                                VoiceState.IDLE, VoiceState.RESPONDING, VoiceState.ERROR -> {
                                    val ok = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    if (ok) voiceAssistantManager.startRecording() else requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 102)
                                }
                                VoiceState.RECORDING -> voiceAssistantManager.stopRecordingAndTranscribe()
                                else -> {}
                            }
                        },
                        onReset = { voiceAssistantManager.cancel() },
                        onSendTextQuery = { cmd -> voiceAssistantManager.sendTextQuery(cmd) },
                        onClose = { showProactiveOverlay.value = false }
                    )
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // DIALOGS
    // ─────────────────────────────────────────────────────────────────────────────
    @Composable
    private fun RenderSettingsDialogs() {
        // Memories dialog — includes inline "Clear All" so the separate Forget button is gone
        if (showMemoriesDialog.value) {
            var newText by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showMemoriesDialog.value = false },
                title = { Text("FRIDAY Memories", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Behavioural models and preferences stored in FRIDAY's memory graph.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = newText, onValueChange = { newText = it }, label = { Text("New habit / info", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                            Button(onClick = { if (newText.isNotBlank()) { localMemories.add(newText.trim()); newText = ""; showToast("Stored!") } }, shape = RoundedCornerShape(8.dp)) { Text("Add", fontSize = 11.sp) }
                        }
                        Spacer(Modifier.height(4.dp))
                        if (localMemories.isEmpty()) {
                            Text("No memories stored yet.", fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(localMemories.size) { i ->
                                    Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(localMemories[i], fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { localMemories.removeAt(i); showToast("Deleted") }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Delete, "Delete", tint = Color.Red, modifier = Modifier.size(16.dp)) }
                                    }
                                }
                            }
                        }
                        // Clear All inline — no separate dialog/button needed
                        if (localMemories.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { localMemories.clear(); showToast("All memories cleared") }, modifier = Modifier.align(Alignment.End)) {
                                Text("Clear All", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showMemoriesDialog.value = false }) { Text("Done", fontWeight = FontWeight.Bold) } }
            )
        }

        // Devices dialog
        if (showDevicesDialog.value) {
            var newDevice by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showDevicesDialog.value = false },
                title = { Text("Synced Devices", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Link a new device or manage existing sessions.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = newDevice, onValueChange = { newDevice = it }, label = { Text("Device name", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                            Button(onClick = { if (newDevice.isNotBlank()) { localDevices.add(newDevice.trim() to "ACTIVE"); newDevice = ""; showToast("Device linked!") } }, shape = RoundedCornerShape(8.dp)) { Text("Link", fontSize = 11.sp) }
                        }
                        Spacer(Modifier.height(4.dp))
                        if (localDevices.isEmpty()) {
                            Text("No devices linked.", fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(localDevices.size) { i ->
                                    val (name, status) = localDevices[i]
                                    Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) { Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface); Text(status, fontSize = 9.sp, color = Color.Green, fontWeight = FontWeight.Bold) }
                                        TextButton(onClick = { localDevices.removeAt(i); showToast("$name disconnected") }) { Text("Disconnect", color = Color.Red, fontSize = 11.sp) }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showDevicesDialog.value = false }) { Text("Done", fontWeight = FontWeight.Bold) } }
            )
        }

        // Clear memories confirm
        if (showClearMemoriesConfirmDialog.value) {
            AlertDialog(
                onDismissRequest = { showClearMemoriesConfirmDialog.value = false },
                title = { Text("Clear All Memories?", fontWeight = FontWeight.Bold) },
                text = { Text("This cannot be undone. FRIDAY will forget all learned behaviours.") },
                confirmButton = { TextButton(onClick = { localMemories.clear(); showClearMemoriesConfirmDialog.value = false; showToast("All memories cleared!") }) { Text("Clear All", color = Color.Red, fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton(onClick = { showClearMemoriesConfirmDialog.value = false }) { Text("Cancel") } }
            )
        }

        // Disconnect devices confirm
        if (showDisconnectDevicesConfirmDialog.value) {
            AlertDialog(
                onDismissRequest = { showDisconnectDevicesConfirmDialog.value = false },
                title = { Text("Disconnect All Devices?", fontWeight = FontWeight.Bold) },
                text = { Text("This will terminate active sync sessions on all linked devices.") },
                confirmButton = { TextButton(onClick = { localDevices.clear(); showDisconnectDevicesConfirmDialog.value = false; showToast("All devices disconnected!") }) { Text("Disconnect All", color = Color.Red, fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton(onClick = { showDisconnectDevicesConfirmDialog.value = false }) { Text("Cancel") } }
            )
        }

        // Explain stress
        if (showExplainStressDialog.value) {
            AlertDialog(
                onDismissRequest = { showExplainStressDialog.value = false },
                title = { Text("Stress Analysis", fontWeight = FontWeight.Black) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("FRIDAY evaluates stress via three signals:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("① App Switching — ${appSwitchesCount.value} switches. High frequency indicates divided focus.", fontSize = 12.sp)
                        Text("② Typing Rhythm — ${if (averageTypingCadenceMs.value == 0L) "No input yet" else "${averageTypingCadenceMs.value}ms"}. Outliers indicate fatigue or urgency.", fontSize = 12.sp)
                        Text("③ Notification Density — ${notificationsCount.value} interrupts. High volume causes chronic cognitive load.", fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("Current index: ${stressScore.value}% — ${when { stressScore.value >= 75 -> "Elevated. Enable Focus Mode to suppress notifications."; stressScore.value >= 50 -> "Moderate. Try reducing multitasking."; else -> "Optimal. Great steady focus!" }}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                },
                confirmButton = { TextButton(onClick = { showExplainStressDialog.value = false }) { Text("Close", fontWeight = FontWeight.Bold) } }
            )
        }

        // User Consent Dialog for 2.3 GB Neural Model Download
        if (showConsentDialog.value) {
            AlertDialog(
                onDismissRequest = { showConsentDialog.value = false },
                title = { Text("Download On-Device Neural AI?", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "The Phi-3 Mini INT4 model requires approximately 2.3 GB of storage. It enables offline generative intelligence when the Compute Hub is unreachable.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "A Wi-Fi connection is strongly recommended before downloading to avoid mobile data limits.",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showConsentDialog.value = false
                            isDownloadingModel.value = true
                            downloadProgress.value = 0f
                            val resolvedHub = if (hubIp.value.isNotEmpty()) hubIp.value else DiscoveryManager.getResolvedHubIp()
                            LocalFallbackEngine.downloadModel(
                                context = this@MainActivity,
                                hubIp = resolvedHub,
                                onProgress = { p ->
                                    downloadProgress.value = p
                                },
                                onComplete = {
                                    isDownloadingModel.value = false
                                    isModelDownloadedState.value = true
                                    CoroutineScope(Dispatchers.IO).launch {
                                        LocalFallbackEngine.initializeEngine(this@MainActivity)
                                    }
                                    showToast("Neural model downloaded and ready!")
                                },
                                onError = { err ->
                                    isDownloadingModel.value = false
                                    downloadProgress.value = 0f
                                    showToast("Download failed: $err")
                                }
                            )
                        }
                    ) {
                        Text("Agree & Download (2.3 GB)")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConsentDialog.value = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
