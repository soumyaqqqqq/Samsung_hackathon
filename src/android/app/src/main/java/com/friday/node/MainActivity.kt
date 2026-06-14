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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.friday.node.config.OnboardingConfigManager
import com.friday.node.data.remote.OnboardingService
import com.friday.node.onboarding.viewmodel.OnboardingViewModel
import com.friday.node.onboarding.viewmodel.OnboardingViewModelFactory
import com.friday.node.onboarding.ui.screens.OnboardingScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
                    attentionTasks.add(JSONObject().apply {
                        put("type", "notification")
                        put("action_id", "notif_${timestamp}_${pkg.hashCode()}")
                        put("package", pkg)
                        put("title", title)
                        put("message", content)
                        put("agent", getAppNameFromPackage(pkg))
                        put("timestamp", timestamp)
                    })
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

        // DB buffer count poller
        CoroutineScope(Dispatchers.Main).launch {
            val db = RoomDatabase.getInstance(this@MainActivity)
            while (true) { bufferedEventsCount.value = db.getEventCount(); delay(1000) }
        }
        // Session timer
        CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                val ms = System.currentTimeMillis() - sessionStartTimeMs
                val mins = (ms / 60000).toInt(); val secs = ((ms / 1000) % 60).toInt()
                focusTimeDisplay.value = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
                delay(1000)
            }
        }

        val filter = IntentFilter().apply {
            addAction("com.friday.node.APP_SWITCH_DETECTED")
            addAction("com.friday.node.TYPING_CADENCE_DETECTED")
            addAction("com.friday.node.NOTIFICATION_INTERCEPTED")
            addAction("com.friday.node.BATTERY_MODE_CHANGED")
            addAction("com.friday.node.CONNECTION_STATE_CHANGED")
            addAction("com.friday.node.ACTION_RECEIVED")
            addAction("com.friday.node.LOCATION_CHANGED")
        }
        registerReceiver(telemetryReceiver, filter, RECEIVER_EXPORTED)

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
                        startFridayServices(fromOnboarding = true)
                        checkPermissionsState()
                        recalculateLocalStress()
                    })
                } else {
                    MainContainer()
                    RenderSettingsDialogs()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val connected = WebSocketManager.getInstance().isConnected()
        isConnected.value = connected
        connectionStatus.value = if (connected) "Connected to Hub" else "Searching for Hub..."
        if (connected) triggerTelemetryRefresh {}
        checkPermissionsState()
    }

    override fun onPause() { super.onPause() }

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

    private fun recalculateLocalStress() {
        val result = LocalFallbackEngine.evaluateOfflineContext(
            appSwitchesCount = appSwitchesCount.value,
            averageTypingCadenceMs = averageTypingCadenceMs.value,
            notificationsPerMin = notificationsCount.value
        )
        stressScore.value = result.stressScore
        val now = System.currentTimeMillis()
        if ((now - lastPromptUpdateTimeMs >= 60_000L || wellbeingPrompt.value.startsWith("FRIDAY ready"))
            && (!isConnected.value || activeActionCard.value == null)) {
            val newPrompt = result.empatheticRecommendation
            if (newPrompt != wellbeingPrompt.value) { wellbeingPrompt.value = newPrompt; lastPromptUpdateTimeMs = now }
        }
        val intensity = (result.stressScore / 100f).coerceIn(0.02f, 1f)
        if (activityIntensities.size >= 16) activityIntensities.removeAt(0)
        activityIntensities.add(intensity)

        productivityPercentage.value = "${(96 - appSwitchesCount.value * 2 - notificationsCount.value).coerceIn(10, 100)}%"
        focusPercentage.value = "${(92 - appSwitchesCount.value * 3).coerceIn(10, 100)}%"
        confidencePercentage.value = "${(88 - result.stressScore / 5).coerceIn(10, 100)}%"
        memoryMatchPercentage.value = "${(82 + appSwitchesCount.value).coerceIn(50, 99)}%"
        if (result.stressScore > 60) stressSpikeTime.value = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
    }

    private fun triggerTelemetryRefresh(onComplete: () -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            val connected = WebSocketManager.getInstance().isConnected()
            isConnected.value = connected
            connectionStatus.value = if (connected) "Connected to Hub" else "Searching for Hub..."
            checkPermissionsState(); recalculateLocalStress()
            bufferedEventsCount.value = RoomDatabase.getInstance(this@MainActivity).getEventCount()
            if (connected) {
                try { WebSocketManager.getInstance().sendEvent(JSONObject().apply { put("type", "sync_request"); put("timestamp", System.currentTimeMillis()) }.toString()) }
                catch (e: Exception) { Log.e(TAG, "Sync request failed: ${e.message}") }
            }
            delay(800); onComplete()
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

    // ── Color Tokens ─────────────────────────────────────────────────────────────
    var isDarkThemeGlobal by mutableStateOf(false)
    val ColorBlockLime get() = if (isDarkThemeGlobal) Color(0xFF2A3600) else Color(0xFFD6FF3D)
    val ColorBlockLilac get() = if (isDarkThemeGlobal) Color(0xFF1B1437) else Color(0xFFC6BFFF)
    val ColorBlockCream get() = if (isDarkThemeGlobal) Color(0xFF252216) else Color(0xFFFFF9E3)
    val ColorBlockMint get() = if (isDarkThemeGlobal) Color(0xFF092416) else Color(0xFFB2FFD6)
    val ColorBlockPink get() = if (isDarkThemeGlobal) Color(0xFF321028) else Color(0xFFFFB8EB)
    val ColorBlockCoral get() = if (isDarkThemeGlobal) Color(0xFF3E120A) else Color(0xFFFF8C70)
    val ColorBlockNavy get() = if (isDarkThemeGlobal) Color(0xFF0C1022) else Color(0xFF10162F)
    val ColorBackground get() = if (isDarkThemeGlobal) Color(0xFF121212) else Color(0xFFF9F9F9)

    // ─────────────────────────────────────────────────────────────────────────────
    // THEME
    // ─────────────────────────────────────────────────────────────────────────────
    @Composable
    fun FridayTheme(content: @Composable () -> Unit) {
        isDarkThemeGlobal = isSystemInDarkTheme()
        val cs = if (isDarkThemeGlobal) darkColorScheme(
            primary = Color.White, background = Color(0xFF121212), surface = Color(0xFF1E1E1E),
            onBackground = Color(0xFFF1F1F1), onSurface = Color(0xFFF1F1F1), error = Color(0xFFEF4444),
            surfaceVariant = Color(0xFF2A2A2A), onSurfaceVariant = Color(0xFFB0B0B0)
        ) else lightColorScheme(
            primary = Color.Black, background = ColorBackground, surface = Color.White,
            onBackground = Color.Black, onSurface = Color.Black, error = Color(0xFFEF4444)
        )
        MaterialTheme(colorScheme = cs, content = content)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // MAIN CONTAINER
    // ─────────────────────────────────────────────────────────────────────────────
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainContainer() {
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
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { isRefreshing = true; triggerTelemetryRefresh { isRefreshing = false } },
                modifier = Modifier.padding(innerPadding).fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (activeTab) {
                        0 -> DashboardTab(); 1 -> MindTab(); 2 -> ContinuityTab(); 3 -> SettingsTab()
                    }
                    if (showProactiveOverlay.value) ProactiveOverlayScreen { showProactiveOverlay.value = false }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // BOTTOM NAV
    // ─────────────────────────────────────────────────────────────────────────────
    @Composable
    fun CustomBottomNavBar(activeTab: Int, onTabSelected: (Int) -> Unit, onSparkClicked: () -> Unit) {
        Box(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .border(width = 1.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                .padding(bottom = 8.dp, top = 8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                NavItem("HOME", Icons.Default.Home, activeTab == 0) { onTabSelected(0) }
                NavItem("INSIGHTS", Icons.Default.Favorite, activeTab == 1) { onTabSelected(1) }
                Spacer(modifier = Modifier.size(48.dp))
                NavItem("TIMELINE", Icons.Default.Refresh, activeTab == 2) { onTabSelected(2) }
                NavItem("SETTINGS", Icons.Default.Settings, activeTab == 3) { onTabSelected(3) }
            }
            Box(modifier = Modifier.align(Alignment.TopCenter).offset(y = (-24).dp)) {
                Button(
                    onClick = onSparkClicked,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface),
                    shape = CircleShape,
                    modifier = Modifier.size(56.dp).border(2.dp, ColorBlockLime, CircleShape),
                    contentPadding = PaddingValues(0.dp)
                ) { Icon(Icons.Default.Mic, "AI Assistant", tint = ColorBlockLime, modifier = Modifier.size(24.dp)) }
            }
        }
    }

    @Composable
    private fun NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
        val tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(8.dp)) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(label, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = tint)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // HOME TAB — Connection · Greeting · Brief · Active Card · Top-3 Attention
    // ─────────────────────────────────────────────────────────────────────────────
    @Composable
    fun DashboardTab() {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("FRIDAY", fontSize = 26.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
                    Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Text(userName.value.take(1).ifEmpty { "U" }, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
            }
            // Connection banner
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp)).padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stringResource(R.string.title_node_connection), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text(connectionStatus.value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if (isConnected.value) Color(0xFF10B981) else Color(0xFFEF4444)))
                }
            }
            // Greeting
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(getDynamicGreeting(userName.value), fontSize = 40.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W300, letterSpacing = (-0.96).sp, lineHeight = 44.sp, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(10.dp))
                    Text(getMotivatingSubtitle(), fontSize = 17.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W300, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                }
            }
            // Briefing card
            item { NotificationBriefingCard(tasks = attentionTasks, stressScore = stressScore.value) }
            // Active recommendation (conditional)
            if (activeActionCard.value != null) item { ActiveRecommendationCard() }
            // Top-3 attention
            item { AttentionPanel() }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    @Composable
    private fun ActiveRecommendationCard() {
        val card = activeActionCard.value ?: return
        val actionId = card.optString("action_id", "")
        val type = card.optString("type", "")
        val message = card.optString("message", "")
        val score = card.optDouble("score", 0.0)
        val agentName = card.optString("agent", "Orchestrator").replaceFirstChar { it.titlecase(Locale.getDefault()) }
        val isHandoff = type == "MEDIA_HANDOFF" || type == "PAGE_HANDOFF"
        val url = if (type == "MEDIA_HANDOFF")
            "https://www.youtube.com/watch?v=${card.optString("video_id")}&t=${card.optInt("playback_timestamp_seconds")}s"
        else card.optString("url", "")

        fun dismiss() {
            WebSocketManager.getInstance().sendFeedback(actionId, "dismissed")
            attentionTasks.removeAll { it.optString("action_id") == actionId }
            activeActionCard.value = null
            wellbeingPrompt.value = "Ambient tracking active. System stable."
            timelineEvents.add(0, TimelineEvent("Handoff Dismissed", "Dismissed laptop task", SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()), "continuity"))
            showToast("Recommendation dismissed")
        }

        fun accept() {
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

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().border(1.dp, ColorBlockLime.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(ColorBlockCoral))
                        Spacer(Modifier.width(8.dp))
                        Text("$agentName Agent".uppercase(Locale.getDefault()), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Box(modifier = Modifier.background(ColorBlockLime.copy(alpha = 0.2f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        val displayScore = if (score > 1.0) score.toInt() else (score * 100).toInt()
                        Text("SCORE: $displayScore%", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Text(message, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, lineHeight = 22.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = ::dismiss) { Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = ::accept, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface), shape = RoundedCornerShape(12.dp)) {
                        Icon(if (isHandoff) Icons.Default.PlayArrow else Icons.Default.Check, null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isHandoff) "Resume" else "Helpful", color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    private fun AttentionPanel() {
        val tasks = getProcessedAttentionTasks(attentionTasks).take(3)
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockCream), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.title_what_needs_attention), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                if (tasks.isEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("FRIDAY is learning your flow...", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(4.dp))
                        Text("Move around and start your routines. I'll prioritize what needs attention here.", fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        tasks.forEachIndexed { i, task ->
                            AttentionTaskRow(index = i, task = task, totalCount = tasks.size)
                        }
                    }
                }
                if (attentionTasks.size > 3) {
                    Text("+${attentionTasks.size - 3} more — see Timeline tab", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.End))
                }
            }
        }
    }

    // Shared task row used in both Home (condensed) and Continuity (full list)
    @Composable
    private fun AttentionTaskRow(index: Int, task: JSONObject, totalCount: Int) {
        val actionId = task.optString("action_id", "act_$index")
        val agent = task.optString("agent", "Orchestrator")
        val message = task.optString("message", "Attention required")
        val isNotif = task.optString("type") == "notification"

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(String.format("%02d", index + 1), fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                Spacer(Modifier.width(12.dp))
                Text("[$agent] $message", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (isNotif) {
                        val pkg = task.optString("package")
                        attentionTasks.removeAll { it.optString("package") == pkg || it.optString("action_id") == actionId }
                        showToast("Alert cleared")
                    } else {
                        activeTaskName.value = "Optimize: $message"
                        activeTaskSteps.clear()
                        activeTaskSteps.addAll(listOf("Analyze VS Code metrics" to "COMPLETED", "Verify DB query efficiency" to "IN_PROGRESS", "Generate execution plan" to "PENDING"))
                        showToast("Journey started: $message")
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface),
                shape = RoundedCornerShape(9999.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                modifier = Modifier.height(32.dp)
            ) { Text(if (isNotif) "Clear" else stringResource(R.string.btn_action), fontSize = 11.sp, color = MaterialTheme.colorScheme.surface) }
        }
        if (index < totalCount - 1) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // MIND TAB — Status · Insight · Cognitive gauge · Radar · Metrics · Agents ·
    //            Signals · Anomaly · Memory correlation · Action buttons
    // ─────────────────────────────────────────────────────────────────────────────
    @Composable
    fun MindTab() {
        val stress = stressScore.value
        val subtitleText = when {
            stress == 0 -> "Learning routine..."
            stress >= 75 -> "Elevated Stress"
            stress >= 50 -> "Focused & Engaged"
            else -> "Calm & Balanced"
        }
        val insightText = when {
            stress == 0 -> "Ambient tracking active. As we collect telemetry from your app switching and typing speeds, personalized cognitive insights will appear here."
            stress >= 75 -> "Your stress score is high ($stress%). We detect multiple context transitions. A short break is recommended."
            appSwitchesCount.value > 8 -> "Frequent app switching detected. Try to isolate your environment to stay in deep focus."
            else -> "Focus metrics look solid today. Your typing cadence is consistent and app switching is minimal."
        }
        val burnoutScore = if (stress == 0) 10f else (stress * 0.7f).coerceIn(10f, 100f)
        val fatigueScore = if (stress == 0) 15f else (appSwitchesCount.value * 6f + averageTypingCadenceMs.value / 25f).coerceIn(10f, 100f)
        val socialScore = if (notificationsCount.value == 0) 12f else (notificationsCount.value * 12f).coerceIn(10f, 100f)

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header
            item {
                Spacer(Modifier.height(24.dp))
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockLime), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(stringResource(R.string.title_current_status), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.title_mindspace), fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(8.dp))
                        Text(subtitleText, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            // Insight prose
            item { Text(insightText, fontSize = 18.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W300, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) }

            // Cognitive Load gauge (dynamic label, no hardcoded "High switching" text)
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockMint), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.title_cognitive_pulse), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                // Dynamic label based on actual data — replaces hardcoded "High switching detected recently."
                                val gaugeLabel = when {
                                    stress == 0 -> "Awaiting telemetry data."
                                    stress >= 75 -> "High cognitive load detected."
                                    stress >= 50 -> "Moderate load. Stay focused."
                                    else -> "Low load. You're in the zone."
                                }
                                Text(gaugeLabel, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (isGhostMode.value) "Ghost mode active. Telemetry simplified." else "High-fidelity telemetry streaming.",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(100.dp)) {
                                CircularProgressIndicator(progress = { stress / 100f }, modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.onSurface, strokeWidth = 10.dp, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                Text("$stress%", fontSize = 22.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }

            // Radar chart (separate lazy item for performance)
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockNavy), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.title_cognitive_risk_radar), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.Start))
                        Spacer(Modifier.height(16.dp))
                        BiometricsRadarChart(stress = stress.toFloat(), burnout = burnoutScore, fatigue = fatigueScore, social = socialScore)
                    }
                }
            }

            // Metrics grid (separate lazy item)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.title_current_state), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        BiometricMetricItem("Stress", "$stress%", when { stress >= 75 -> "Elevated"; stress >= 50 -> "Moderate"; else -> "Stable" })
                        BiometricMetricItem("Focus", focusPercentage.value, when { focusPercentage.value == "Learning..." -> "Learning..."; appSwitchesCount.value > 5 -> "Distracted"; else -> "High Perf" })
                        BiometricMetricItem("Cadence", if (averageTypingCadenceMs.value == 0L) "--" else "${averageTypingCadenceMs.value}ms", when {
                            averageTypingCadenceMs.value == 0L -> "No Input"; averageTypingCadenceMs.value in 10..180 -> "Fast"; averageTypingCadenceMs.value > 600 -> "Slow"; else -> "Consistent"
                        })
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        BiometricMetricItem("Confidence", confidencePercentage.value, when { confidencePercentage.value == "Learning..." -> "Learning..."; stress >= 75 -> "Fluctuating"; else -> "Stable" })
                        BiometricMetricItem("Social Load", if (notificationsCount.value == 0) "0%" else "${socialScore.toInt()}%", when { notificationsCount.value == 0 -> "Quiet"; socialScore >= 60 -> "Demanding"; else -> "Introvert" })
                    }
                }
            }

            // Emotion agent (separate lazy item)
            item {
                val eTitle = when { stress == 0 -> "Stable"; stress >= 70 -> "High Arousal"; else -> "Focused & Confident" }
                val eDesc = when { stress == 0 -> "Ambient tracking active. Analyzing focus patterns."; stress >= 70 -> "Slight stress spike detected. Take deep breaths."; else -> "Stable typing rhythms and low application switching detected." }
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockMint), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.title_emotion_agent), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Text(eTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(eDesc, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(confidencePercentage.value, fontSize = 24.sp, fontWeight = FontWeight.Black)
                            Text(stringResource(R.string.title_confidence_score), fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
            }

            // Burnout agent (separate lazy item)
            item {
                val bTitle = when { stress == 0 -> "Risk: Stable"; burnoutScore >= 60 -> "Risk: High"; burnoutScore >= 35 -> "Risk: Moderate"; else -> "Risk: Low" }
                val bDesc = when { stress == 0 -> "No active stress patterns detected. Tracking balance."; burnoutScore >= 60 -> "High continuous stress. Prioritize rest cycles immediately."; else -> "Normal sleep cycles, consistent output, and language neutrality." }
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockPink), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Build, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.title_burnout_agent), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Text(bTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(bDesc, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                    }
                }
            }

            // Social agent (separate lazy item)
            item {
                val sTitle = when { notificationsCount.value == 0 -> "Risk: Stable"; socialScore >= 60 -> "Risk: High"; socialScore >= 35 -> "Risk: Moderate"; else -> "Risk: Low" }
                val sDesc = when { notificationsCount.value == 0 -> "No recent communication spikes. Social load is light."; socialScore >= 60 -> "High messaging volume. Consider putting phone on Do Not Disturb."; else -> "Impending tasks stable. Social load is well within limits." }
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockCream), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.title_social_agent), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Text(sTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(sDesc, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                    }
                }
            }

            // Signals (separate lazy item)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.title_why_signals), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SignalRowItem("App Switching", "${appSwitchesCount.value} events")
                        SignalRowItem("Active Location", locationState.value.uppercase(Locale.getDefault()))
                        SignalRowItem("Typing Delay", "${averageTypingCadenceMs.value} ms")
                        SignalRowItem("Session Duration", focusTimeDisplay.value)
                    }
                }
            }

            // Behavioral Anomaly (separate lazy item — split from Memory Correlation for lazy loading)
            item {
                val anomaly = remember(appSwitchesCount.value, averageTypingCadenceMs.value, stress) {
                    (appSwitchesCount.value * 2 + (averageTypingCadenceMs.value / 200).toInt() + stress / 4).coerceIn(2, 98)
                }
                val dayOfWeek = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
                val (aLabel, aColor, aBg, aDesc) = when {
                    anomaly > 60 -> Quad("ELEVATED DEVIATION", Color(0xFFE57373), Color(0x1AE57373), "Frequent app switching and altered cadence indicate context fatigue.")
                    anomaly > 35 -> Quad("MODERATE DEVIATION", Color(0xFFFFB74D), Color(0x1AFFB74D), "Mild pacing variation detected. Monitor focus cadence.")
                    else -> Quad("STABLE SYSTEM PATTERN", Color(0xFF81C784), Color(0x1A81C784), "Current usage aligns cleanly with historical baseline.")
                }
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(24.dp))) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.title_behavioral_anomaly), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(aBg).padding(horizontal = 8.dp, vertical = 4.dp)) { Text(aLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = aColor) }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Pattern Analysis: $anomaly% deviation from standard $dayOfWeek baseline.", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Text(aDesc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Memory Correlation (separate lazy item — split from Behavioral Anomaly for lazy loading)
            item {
                val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MONTH, -3) }
                val pastMonth = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
                val matchPct = memoryMatchPercentage.value.replace("%", "").toIntOrNull() ?: 0
                val (mLabel, mColor, mBg) = when {
                    matchPct > 75 -> Triple("HIGH HISTORICAL FIT", Color(0xFF64B5F6), Color(0x1A64B5F6))
                    matchPct > 45 -> Triple("MODERATE FIT", Color(0xFFBA68C8), Color(0x1ABA68C8))
                    else -> Triple("NOVEL PATTERNFLOW", Color(0xFF90A4AE), Color(0x1A90A4AE))
                }
                val outcome = when { stress >= 75 -> "High cognitive load detected. Recommend task transition."; stress >= 50 -> "Moderate stress. High focus but stable output."; else -> "Balanced flow state. Smooth task transitions." }
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(24.dp))) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.title_memory_correlation), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(mBg).padding(horizontal = 8.dp, vertical = 4.dp)) { Text(mLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = mColor) }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Similar Past Events: ${memoryMatchPercentage.value} Match to $pastMonth Baseline.", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Text("Outcome: $outcome", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Action buttons
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            isFocusModeActive.value = !isFocusModeActive.value
                            if (isFocusModeActive.value) { sessionStartTimeMs = System.currentTimeMillis(); focusTimeDisplay.value = "0s"; showToast("Focus mode activated!") }
                            else showToast("Focus mode deactivated!")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isFocusModeActive.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface),
                        shape = RoundedCornerShape(9999.dp), modifier = Modifier.weight(1f)
                    ) { Text(if (isFocusModeActive.value) "Stop Focus" else "Start Focus", color = if (isFocusModeActive.value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.surface, fontSize = 11.sp) }

                    Button(
                        onClick = { isReduceInterruptActive.value = !isReduceInterruptActive.value; showToast(if (isReduceInterruptActive.value) "Interruption blocker active." else "Deactivated.") },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isReduceInterruptActive.value) MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f) else Color.Transparent),
                        shape = RoundedCornerShape(9999.dp), modifier = Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(9999.dp))
                    ) { Text(if (isReduceInterruptActive.value) "Allow Interrupt" else "Reduce Interrupt", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp) }

                    Button(
                        onClick = { showExplainStressDialog.value = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(9999.dp), modifier = Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(9999.dp))
                    ) { Text("Explain Stress", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp) }
                }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // CONTINUITY TAB — Date · Key metrics · Intensity chart · Story · Memory
    //                  Moment (read-only) · Devices · Task Journey · Full task
    //                  list + Simulate · Cognitive balance status · System logs ·
    //                  Recent sites · Live log
    // ─────────────────────────────────────────────────────────────────────────────
    @Composable
    fun ContinuityTab() {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header
            item {
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.title_life_timeline), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 4.dp))
                Text(getDynamicDate(), fontSize = 40.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W300, letterSpacing = (-0.96).sp, lineHeight = 44.sp, color = MaterialTheme.colorScheme.onBackground)
            }

            // Key metrics row (the only metrics summary — the duplicate LazyRow has been removed)
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("PRODUCTIVITY", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold); Text(productivityPercentage.value, fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface) }
                    Column { Text("PEAK FOCUS", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold); Text(peakFocusTime.value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
                    Column { Text("SESSION", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold); Text(focusTimeDisplay.value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
                    Column { Text("STRESS SPIKE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold); Text(stressSpikeTime.value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
                }
            }

            // Activity intensity visualizer
            item { ProductivityIntensityVisualizer() }

            // Today's story
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.title_today_story), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    val story = if (stressScore.value == 0)
                        "Ambient tracking active. As we collect telemetry from your device switches and notification intercepts, your personalized daily story will build here."
                    else
                        "Today's journey began in the ${locationState.value.lowercase(Locale.getDefault())} context. ${appSwitchesCount.value} application switches logged, ${notificationsCount.value} distractions intercepted. Cognitive stress at ${stressScore.value}%."
                    Text(story, fontSize = 20.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W300, color = MaterialTheme.colorScheme.onSurface, lineHeight = 28.sp)
                }
            }

            // Memory Moment — read-only callout (no action buttons; those live on Mind tab)
            if (!isMemoryMomentDismissed.value) {
                item {
                    val loc = locationState.value
                    val memTitle = if (loc.isEmpty() || loc == "unknown") "Learning Routines" else loc.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                    val memBody = if (loc.isEmpty() || loc == "unknown")
                        "Move around and let me learn your focus triggers. Your routines will surface here automatically."
                    else
                        "You typically activate 'Deep Focus' in this context. Head to the Insights tab to start a focus session."
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                Text("MEMORY MOMENT", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                IconButton(onClick = { isMemoryMomentDismissed.value = true }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
                                }
                            }
                            Text(memTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(memBody, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                        }
                    }
                }
            }

            // Devices
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.title_device_continuity), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    DeviceContinuityRow("phone", "Galaxy Phone", "Active: ${focusTimeDisplay.value}")
                    DeviceContinuityRow("laptop", "Work Laptop", if (isLaptopConnected.value) "Active (WebSocket Connected)" else "Offline")
                }
            }

            // Task Journey
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.title_task_journey), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(24.dp))) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            if (activeTaskName.value.isEmpty()) {
                                Text("Awaiting task journey...", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Spacer(Modifier.height(4.dp))
                                Text("Start a task from the What Needs Attention section below.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            } else {
                                Text(activeTaskName.value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(16.dp))
                                activeTaskSteps.forEach { (title, status) ->
                                    TaskJourneyStep(title, status, status == "COMPLETED", status == "IN_PROGRESS")
                                }
                            }
                        }
                    }
                }
            }

            // Full attention list — shown only if non-empty; Simulate button shown
            // as a standalone control (no wrapper card) only when the list is empty
            item {
                val allTasks = getProcessedAttentionTasks(attentionTasks)
                if (allTasks.isNotEmpty()) {
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockCream), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("ALL PENDING ACTIONS", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                allTasks.forEachIndexed { i, task -> AttentionTaskRow(i, task, allTasks.size) }
                            }
                            Button(
                                onClick = {
                                    val sorted = attentionTasks.sortedByDescending { it.optDouble("score", 0.0) }
                                    attentionTasks.clear(); attentionTasks.addAll(sorted); showToast("Tasks prioritized by urgency score.")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                shape = RoundedCornerShape(9999.dp),
                                modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(9999.dp))
                            ) { Text(stringResource(R.string.btn_prioritize_automatically), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
                        }
                    }
                } else {
                    // Standalone Simulate button — no wrapper card
                    Button(
                        onClick = {
                            val mock = JSONObject().apply { put("type", "FRIDAY_CARD"); put("action_id", "act_sim_01"); put("message", "High CPU on VS Code detected. Run profile_schema.py to inspect queries."); put("agent", "Decision"); put("score", 0.94); put("laptop_active", true) }
                            attentionTasks.add(mock); activeActionCard.value = mock; isLaptopConnected.value = true; showToast("Workspace journey simulated!")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(9999.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(9999.dp))
                    ) { Text("Simulate Workspace Journey", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
                }
            }

            // Cognitive balance status card (the one meaningful card retained from the removed bento)
            item {
                val highStress = stressScore.value > 65
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = if (highStress) (if (isDarkThemeGlobal) Color(0xFF4C0505) else Color(0xFFFEE2E2)) else (if (isDarkThemeGlobal) Color(0xFF064E3B) else Color(0xFFD1FAE5))),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val iconTint = if (highStress) (if (isDarkThemeGlobal) Color(0xFFFCA5A5) else Color(0xFF991B1B)) else (if (isDarkThemeGlobal) Color(0xFF34D399) else Color(0xFF065F46))
                        val bodyColor = if (highStress) (if (isDarkThemeGlobal) Color(0xFFFECACA) else Color(0xFF7F1D1D)) else (if (isDarkThemeGlobal) Color(0xFFA7F3D0) else Color(0xFF065F46))
                        Icon(if (highStress) Icons.Default.Warning else Icons.Default.Check, null, tint = iconTint, modifier = Modifier.size(20.dp))
                        Text(if (highStress) "Fatigue Predicted" else "Cognitive Balance Stable", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = iconTint)
                        Text(if (highStress) "Sleep cycle correction needed. Rest suggested before 11:30 PM." else "Great job keeping your focus window clear of context switching.", fontSize = 13.sp, color = bodyColor)
                    }
                }
            }

            // System logs (navy)
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockNavy), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("SYSTEM LOGS & ACTIVITY", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f))
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SystemLogRow("Active App", "Focused: ${recentApp.value}")
                            SystemLogRow("Last Notification", lastNotification.value)
                            SystemLogRow("Offline Cache", "Buffered events: ${bufferedEventsCount.value}")
                        }
                    }
                }
            }

            // Recent laptop sites
            if (recentLaptopSites.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("RECENT SITES · LAPTOP", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        recentLaptopSites.take(3).forEach { (title, displayUrl, fullUrl) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                                    .clickable { if (fullUrl.isNotEmpty()) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }) } catch (e: Exception) { Log.e(TAG, "URL open failed: ${e.message}") } }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(40.dp).background(ColorBlockLilac, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp)) }
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(displayUrl.ifEmpty { fullUrl }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            // Live activity log
            if (timelineEvents.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("LIVE ACTIVITY LOG", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        timelineEvents.take(10).forEach { TimelineRowItem(it) }
                    }
                }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SETTINGS TAB
    // "Forget Memory" has been removed — its functionality (Clear All) now lives
    // inside the memories dialog opened by "View & Manage Memories".
    // ─────────────────────────────────────────────────────────────────────────────
    @Composable
    fun SettingsTab() {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.title_trust_center), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 4.dp))
                Text("Settings", fontSize = 40.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, letterSpacing = (-0.96).sp, color = MaterialTheme.colorScheme.onBackground)
                Text("Manage privacy, devices and personalization.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Profile
            item {
                Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(24.dp)).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface), contentAlignment = Alignment.Center) {
                        Text(userName.value.take(1).ifEmpty { "U" }, color = MaterialTheme.colorScheme.surface, fontSize = 32.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(userName.value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(userRole.value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Privacy & Data
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockLime), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.title_privacy_data), fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                        val prefs = remember { getSharedPreferences("friday_settings", MODE_PRIVATE) }
                        var dataProcessing by remember { mutableStateOf(prefs.getBoolean("data_processing", true)) }
                        var cloudBackup by remember { mutableStateOf(prefs.getBoolean("cloud_backup", true)) }
                        var dataEncryption by remember { mutableStateOf(prefs.getBoolean("data_encryption", true)) }
                        ToggleRow(stringResource(R.string.hint_data_processing), stringResource(R.string.hint_mostly_on_device), dataProcessing) { dataProcessing = it; prefs.edit().putBoolean("data_processing", it).apply(); showToast(if (it) "On-device processing enabled" else "Disabled") }
                        ToggleRow(stringResource(R.string.hint_cloud_backup), checked = cloudBackup) { cloudBackup = it; prefs.edit().putBoolean("cloud_backup", it).apply(); showToast(if (it) "Cloud backup enabled" else "Disabled") }
                        ToggleRow(stringResource(R.string.hint_data_encryption), checked = dataEncryption) { dataEncryption = it; prefs.edit().putBoolean("data_encryption", it).apply(); showToast(if (it) "Encryption enabled" else "Disabled") }
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = { try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) }) } catch (_: Exception) { showToast(getString(R.string.msg_open_permission_manager)) } },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        ) { Text(stringResource(R.string.btn_manage_permissions), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            // System Permissions
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(24.dp))) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.title_system_permissions), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PermissionRow("Accessibility Service", "For active application context switches", isAccessibilityGranted.value) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                        PermissionRow("Notification Listener", "For intercepting notifications", isNotificationAccessGranted.value) { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            PermissionRow("Post Notifications", "Foreground sensing alert channel", isPostNotificationGranted.value) { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101) }
                        }
                    }
                }
            }

            // Memory — single "View & Manage" button (Clear All lives inside dialog)
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockLilac), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.title_memory), fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("${localMemories.size}", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface); Text("STORED", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                            Column { Text("${bufferedEventsCount.value}", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface); Text("EVENTS", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                            Column { Text(String.format(Locale.US, "%.1f MB", 0.4f + localMemories.size * 0.15f), fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface); Text("SIZE", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                        }
                        // Single button — "Clear All" lives inside the dialog itself
                        Button(onClick = { showMemoriesDialog.value = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Text("View & Manage Memories", color = MaterialTheme.colorScheme.surface)
                        }
                    }
                }
            }

            // Devices
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockNavy), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column {
                            Text(stringResource(R.string.title_devices), fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.White)
                            Text("Synced 2m ago", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.White.copy(alpha = 0.4f), fontWeight = FontWeight.Bold)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (localDevices.isEmpty()) {
                                Text("No synced devices.", color = Color.White.copy(alpha = 0.6f), fontStyle = FontStyle.Italic, fontSize = 14.sp)
                            } else {
                                localDevices.forEachIndexed { idx, (name, status) ->
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(if (name.lowercase().contains("laptop") || name.lowercase().contains("mac") || name.lowercase().contains("pc")) Icons.Default.Build else Icons.Default.Phone, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(12.dp)); Text(name, color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                        Box(modifier = Modifier.background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(status, fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                                    }
                                    if (idx < localDevices.size - 1) HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                                }
                            }
                        }
                        Button(onClick = { showDevicesDialog.value = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_manage_devices), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            // Hub IP
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(24.dp))) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.title_hub_override_ip_config), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(value = hubIp.value, onValueChange = { hubIp.value = it }, label = { Text("Hub IP", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, focusedBorderColor = MaterialTheme.colorScheme.onSurface, unfocusedBorderColor = Color.LightGray), modifier = Modifier.weight(2f))
                            OutlinedTextField(value = hubPort.value, onValueChange = { hubPort.value = it }, label = { Text("Port", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, focusedBorderColor = MaterialTheme.colorScheme.onSurface, unfocusedBorderColor = Color.LightGray), modifier = Modifier.weight(1f))
                        }
                        Button(onClick = { if (hubIp.value.isNotEmpty()) WebSocketManager.getInstance().connect("ws://${hubIp.value}:${hubPort.value}/ws/android") }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_connect_manually), color = MaterialTheme.colorScheme.surface) }
                    }
                }
            }

            // Assistance style
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(24.dp))) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.title_assistance_style), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val prefs = remember { getSharedPreferences("friday_settings", MODE_PRIVATE) }
                        var style by remember { mutableStateOf(prefs.getString("assistance_style", "Balanced") ?: "Balanced") }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Quiet", "Balanced", "Proactive").forEach { s ->
                                Row(modifier = Modifier.fillMaxWidth().clickable { style = s; prefs.edit().putString("assistance_style", s).apply(); showToast("Style: $s") }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = style == s, onClick = { style = s; prefs.edit().putString("assistance_style", s).apply(); showToast("Style: $s") }, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.onSurface))
                                    Spacer(Modifier.width(8.dp)); Text(s, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }

            // Danger Zone
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = if (isDarkThemeGlobal) Color(0xFF4C0505) else Color(0xFFFEE2E2)), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.title_danger_zone), fontSize = 32.sp, fontWeight = FontWeight.Black, color = if (isDarkThemeGlobal) Color(0xFFFECACA) else Color(0xFF7F1D1D))
                        Button(onClick = { showDisconnectDevicesConfirmDialog.value = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_disconnect_devices), color = Color(0xFFEF4444), fontWeight = FontWeight.Bold) }
                        Button(onClick = { showClearMemoriesConfirmDialog.value = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_clear_memories), color = Color(0xFFEF4444), fontWeight = FontWeight.Bold) }
                    }
                }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SHARED COMPOSABLES
    // ─────────────────────────────────────────────────────────────────────────────
    @Composable
    fun BiometricMetricItem(label: String, value: String, subtitle: String) {
        Column(modifier = Modifier.width(90.dp)) {
            Text(label.uppercase(), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground)
        }
    }

    @Composable
    fun SignalRowItem(label: String, value: String, isAlert: Boolean = false) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (isAlert) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurface)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
    }

    @Composable
    private fun SystemLogRow(title: String, subtitle: String) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Star, null, tint = ColorBlockLime, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    @Composable
    private fun PermissionRow(title: String, subtitle: String, granted: Boolean, onClick: () -> Unit) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = if (granted) Color(0xFF10B981) else Color(0xFFEF4444)), shape = RoundedCornerShape(9999.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                Text(if (granted) "Granted" else "Grant", fontSize = 11.sp, color = Color.White)
            }
        }
    }

    @Composable
    fun BiometricsRadarChart(stress: Float, burnout: Float, fatigue: Float, social: Float) {
        Box(modifier = Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(200.dp)) {
                val c = size.width / 2; val maxR = size.width / 2 * 0.8f
                for (i in 1..4) {
                    val r = maxR * (i / 4f)
                    drawRect(Color.White.copy(alpha = 0.15f), androidx.compose.ui.geometry.Offset(c - r, c - r), androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(1.dp.toPx()))
                }
                drawLine(Color.White.copy(alpha = 0.2f), androidx.compose.ui.geometry.Offset(c, c - maxR), androidx.compose.ui.geometry.Offset(c, c + maxR), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.2f), androidx.compose.ui.geometry.Offset(c - maxR, c), androidx.compose.ui.geometry.Offset(c + maxR, c), 1.dp.toPx())
                val path = Path().apply {
                    moveTo(c, c - maxR * (stress / 100f)); lineTo(c + maxR * (social / 100f), c)
                    lineTo(c, c + maxR * (burnout / 100f)); lineTo(c - maxR * (fatigue / 100f), c); close()
                }
                drawPath(path, ColorBlockLime.copy(alpha = 0.4f))
                drawPath(path, ColorBlockLime, style = Stroke(2.dp.toPx()))
            }
            Box(modifier = Modifier.fillMaxSize()) {
                Text("STRESS (${stress.toInt()})", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.TopCenter))
                Text("BURNOUT (${burnout.toInt()})", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.BottomCenter))
                Text("FATIGUE (${fatigue.toInt()})", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
                Text("SOCIAL (${social.toInt()})", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
            }
        }
    }

    @Composable
    fun ProductivityIntensityVisualizer() {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockLime), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("ACTIVITY INTENSITY", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth().height(100.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                    activityIntensities.forEach { h ->
                        Box(modifier = Modifier.weight(1f).fillMaxHeight(h.coerceIn(0.02f, 1f)).background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)))
                    }
                }
            }
        }
    }

    @Composable
    fun TimelineRowItem(event: TimelineEvent) {
        val icon = when (event.type) {
            "app" -> Icons.Default.Build; "notification" -> Icons.Default.Notifications
            "location" -> Icons.Default.Home; "suggestion" -> Icons.Default.Star
            "continuity" -> Icons.Default.PlayArrow; else -> Icons.Default.Info
        }
        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.background, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(event.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(event.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Text(event.time, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    fun DeviceContinuityRow(icon: String, name: String, active: String) {
        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.background, CircleShape), contentAlignment = Alignment.Center) { Icon(if (icon == "phone") Icons.Default.Phone else Icons.Default.Build, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(16.dp))
            Column { Text(name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface); Text(active, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    @Composable
    fun TaskJourneyStep(title: String, status: String, isDone: Boolean = false, isCurrent: Boolean = false) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(20.dp).clip(CircleShape).background(when { isDone -> MaterialTheme.colorScheme.onSurface; isCurrent -> ColorBlockLime; else -> Color.LightGray }), contentAlignment = Alignment.Center) {
                if (isDone) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(12.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, fontSize = 14.sp, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, color = MaterialTheme.colorScheme.onBackground)
                Text(status, fontSize = 11.sp, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, color = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    fun ToggleRow(label: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primaryContainer, uncheckedThumbColor = MaterialTheme.colorScheme.outline, uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // NOTIFICATION BRIEFING CARD
    // ─────────────────────────────────────────────────────────────────────────────
    data class BriefItem(val title: String, val message: String, val category: String)

    @Composable
    fun NotificationBriefingCard(tasks: List<JSONObject>, stressScore: Int) {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val briefTitle = when (hour) { in 5..11 -> "Morning Brief"; in 12..16 -> "Afternoon Brief"; in 17..21 -> "Evening Brief"; else -> "Night Brief" }
        val unreadText = if (tasks.isEmpty()) "No Alerts" else "${tasks.size} Unread"

        val briefItems = remember(tasks, stressScore) {
            val notifs = tasks.filter { it.optString("type") == "notification" }
            if (notifs.isNotEmpty()) {
                notifs.map { task ->
                    val msg = task.optString("message", "")
                    val isDeadline = msg.lowercase().let { it.contains("deadline") || it.contains("thesis") || it.contains("due") }
                    val isWeather = msg.lowercase().let { it.contains("weather") || it.contains("rain") }
                    BriefItem(if (isDeadline) "Academic Alert" else if (isWeather) "Weather Update" else "Alert: ${task.optString("agent", "App")}", msg, if (isDeadline) "academic" else if (isWeather) "weather" else "general")
                }.take(2)
            } else {
                when (hour) {
                    in 5..11 -> listOf(BriefItem("Morning Focus Window", "Telemetry indicates optimal recovery. High-focus activity recommended.", "academic"), BriefItem("System Status", "Background processing clean. Energy profile optimal.", "general"))
                    in 12..16 -> listOf(BriefItem(if (stressScore > 50) "Cognitive Rest Recommended" else "Mid-Day Performance", if (stressScore > 50) "High cognitive load. Consider a 5-minute offline break." else "Cognitive load stable. Maintain your current work cadence.", if (stressScore > 50) "academic" else "general"), BriefItem("Environmental State", "Conditions normal. Perfect for productive tasks.", "weather"))
                    in 17..21 -> listOf(BriefItem("Evening Reflection", "Core focus targets completed. Clear cognitive peaks recorded.", "academic"), BriefItem("Security", "Local telemetry synchronized. All logs encrypted locally.", "general"))
                    else -> listOf(BriefItem("Sleep Cycle", "Recommended sleep window approaching. Filters activated.", "weather"), BriefItem("Maintenance", "On-device ML optimizations running under sleep cycles.", "general"))
                }
            }
        }

        val footer = when { stressScore > 75 -> "High stress detected. Recommending a short break."; stressScore > 45 -> "Elevated load. Keeping notifications quiet."; else -> "You seem rested. Prioritizing high-value tasks." }

        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)), modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(24.dp))) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp)) }
                        Text(briefTitle, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Box(modifier = Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(9999.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) { Text(unreadText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    briefItems.forEach { item ->
                        val (iconColor, iconBg, icon) = when (item.category) {
                            "academic" -> Triple(Color(0xFFEF4444), Color(0xFFFEE2E2), Icons.Default.Warning)
                            "weather" -> Triple(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f), Icons.Default.Info)
                            else -> Triple(if (isDarkThemeGlobal) Color(0xFFD6FF3D) else Color(0xFF2A3600), (if (isDarkThemeGlobal) Color(0xFFD6FF3D) else Color(0xFF2A3600)).copy(alpha = 0.15f), Icons.Default.Notifications)
                        }
                        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f), RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) { Icon(icon, null, tint = iconColor, modifier = Modifier.size(18.dp)) }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(2.dp))
                                Text(item.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
                Text("\"$footer\"", fontSize = 13.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // PROACTIVE OVERLAY  (Voice)
    // ─────────────────────────────────────────────────────────────────────────────
    @Composable
    fun ProactiveOverlayScreen(onClose: () -> Unit) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val assistantState by this@MainActivity.assistantState
        val transcriptText by this@MainActivity.transcriptText
        val assistantResponse by this@MainActivity.assistantResponse
        val errorMessage by this@MainActivity.errorMessage

        DisposableEffect(Unit) {
            if (voiceAssistantManager.state == VoiceState.IDLE) {
                val ok = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (ok) voiceAssistantManager.startRecording()
            }
            onDispose {}
        }

        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.98f)).clickable {}) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onClose, modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f), CircleShape)) { Icon(Icons.Default.Close, "Close", tint = MaterialTheme.colorScheme.onBackground) }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when (assistantState) { VoiceState.RECORDING -> ColorBlockLime; VoiceState.TRANSCRIBING -> ColorBlockLilac; VoiceState.RESPONDING -> ColorBlockMint; VoiceState.ERROR -> ColorBlockCoral; else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f) }
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor)); Spacer(Modifier.width(8.dp))
                    Text("STATUS: ${assistantState.name}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                Text(when (assistantState) { VoiceState.IDLE -> "Speak to FRIDAY"; VoiceState.RECORDING -> "FRIDAY is listening..."; VoiceState.TRANSCRIBING -> "FRIDAY is thinking..."; VoiceState.RESPONDING -> "FRIDAY's Response"; VoiceState.ERROR -> "Voice Error" }, fontSize = 44.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W300, letterSpacing = (-0.96).sp, lineHeight = 48.sp, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(40.dp))

                val transition = rememberInfiniteTransition(label = "pulse")
                val scale by transition.animateFloat(1f, 1.35f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "scale")
                val alpha by transition.animateFloat(0.6f, 0f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "alpha")

                Box(modifier = Modifier.align(Alignment.CenterHorizontally).size(120.dp), contentAlignment = Alignment.Center) {
                    if (assistantState == VoiceState.RECORDING) Box(modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale).clip(CircleShape).background(ColorBlockLime.copy(alpha = alpha)))
                    Box(
                        modifier = Modifier.size(80.dp).clip(CircleShape).background(if (assistantState == VoiceState.RECORDING) ColorBlockLime else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).clickable {
                            when (assistantState) {
                                VoiceState.IDLE, VoiceState.RESPONDING, VoiceState.ERROR -> {
                                    val ok = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    if (ok) voiceAssistantManager.startRecording() else requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 102)
                                }
                                VoiceState.RECORDING -> voiceAssistantManager.stopRecordingAndTranscribe()
                                else -> {}
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Crossfade(targetState = assistantState, label = "mic") { s ->
                            when (s) {
                                VoiceState.RECORDING -> PulsatingDotsCompose(if (isDarkThemeGlobal) Color(0xFFD6FF3D) else Color.Black)
                                VoiceState.TRANSCRIBING -> CircularProgressIndicator(color = if (isDarkThemeGlobal) Color(0xFFD6FF3D) else Color.Black, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                                VoiceState.RESPONDING -> RippleWaveLoaderCompose()
                                else -> Icon(Icons.Default.Star, "Mic", tint = ColorBlockLime, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(when (assistantState) { VoiceState.IDLE -> "Tap to start recording."; VoiceState.RECORDING -> "Tap again when done."; VoiceState.TRANSCRIBING -> "Analyzing via whisper.cpp..."; VoiceState.RESPONDING -> "Ready for next query."; VoiceState.ERROR -> "Tap to try again." }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(40.dp))

                when (assistantState) {
                    VoiceState.RESPONDING -> {
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockLilac), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(20.dp)) { Text("YOU", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)); Spacer(Modifier.height(8.dp)); Text("\"$transcriptText\"", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface) }
                        }
                        Spacer(Modifier.height(16.dp))
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockLime), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(20.dp)) { Text("FRIDAY AI", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)); Spacer(Modifier.height(8.dp)); Text(assistantResponse, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
                        }
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { voiceAssistantManager.cancel() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground), shape = RoundedCornerShape(9999.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Clear & Ask Something Else", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.background) }
                    }
                    VoiceState.ERROR -> {
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockCoral), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(20.dp)) { Text("ENGINE ERROR", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.7f)); Spacer(Modifier.height(8.dp)); Text(errorMessage, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                        }
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { voiceAssistantManager.cancel() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground), shape = RoundedCornerShape(9999.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Reset", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.background) }
                    }
                    else -> {
                        Text("SUGGESTED COMMANDS", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("Silence all alerts", "Prepare Evening Brief", "Explain Current Stress", "Start Focus Mode").forEach { cmd ->
                                OverlayActionButton(cmd) { voiceAssistantManager.sendTextQuery(cmd) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    @Composable
    fun OverlayActionButton(text: String, onClick: () -> Unit) {
        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(9999.dp)).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(9999.dp)).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        }
    }

    @Composable private fun PulsatingDotsCompose(color: Color) {
        val t = rememberInfiniteTransition(label = "dots")
        val s1 by t.animateFloat(0.5f, 1.3f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "d1")
        val s2 by t.animateFloat(0.5f, 1.3f, infiniteRepeatable(tween(600), RepeatMode.Reverse, androidx.compose.animation.core.StartOffset(200)), label = "d2")
        val s3 by t.animateFloat(0.5f, 1.3f, infiniteRepeatable(tween(600), RepeatMode.Reverse, androidx.compose.animation.core.StartOffset(400)), label = "d3")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(s1, s2, s3).forEach { s -> Box(modifier = Modifier.size(8.dp).graphicsLayer(scaleX = s, scaleY = s).background(color, CircleShape)) }
        }
    }

    @Composable private fun RippleWaveLoaderCompose() {
        val t = rememberInfiniteTransition(label = "wave")
        val anim = (0 until 7).map { i -> t.animateFloat(0.4f, 1.6f, infiniteRepeatable(tween(500), RepeatMode.Reverse, androidx.compose.animation.core.StartOffset(i * 80)), label = "w$i") }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            anim.forEachIndexed { i, a -> Box(modifier = Modifier.width(3.dp).height(18.dp).graphicsLayer(scaleY = a.value).background(if (isDarkThemeGlobal && i % 2 == 0) Color(0xFFD6FF3D) else if (isDarkThemeGlobal) Color.White else Color.Black, RoundedCornerShape(99.dp)))
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
    }
}

// Small helper data class used for destructuring the anomaly badge state
private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
private operator fun <A, B, C, D> Quad<A, B, C, D>.component1() = first
private operator fun <A, B, C, D> Quad<A, B, C, D>.component2() = second
private operator fun <A, B, C, D> Quad<A, B, C, D>.component3() = third
private operator fun <A, B, C, D> Quad<A, B, C, D>.component4() = fourth