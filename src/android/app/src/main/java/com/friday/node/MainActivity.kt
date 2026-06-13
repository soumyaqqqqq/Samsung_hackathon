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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.foundation.lazy.LazyRow
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
import com.friday.node.utils.DiscoveryManager
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

    // Live UI State variables using Compose states
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

    // Dynamic user profile and metrics
    private var userName = mutableStateOf("")
    private var userRole = mutableStateOf("")
    private var productivityPercentage = mutableStateOf("Learning...")
    private var peakFocusTime = mutableStateOf("Learning...")
    private var stressSpikeTime = mutableStateOf("None")
    private var focusPercentage = mutableStateOf("Learning...")
    private var focusImprovementPercent = mutableStateOf("0%")
    private var confidencePercentage = mutableStateOf("Learning...")
    private var memoryMatchPercentage = mutableStateOf("Learning...")

    // Dynamic telemetry collections
    private var sessionStartTimeMs = System.currentTimeMillis()
    private var focusTimeDisplay = mutableStateOf("0s")
    private var locationState = mutableStateOf("home")
    private var isLaptopConnected = mutableStateOf(false)
    private var attentionTasks = mutableStateListOf<JSONObject>()
    private val timelineEvents = mutableStateListOf<TimelineEvent>()
    private val activityIntensities = mutableStateListOf<Float>().apply {
        repeat(16) { add(0.02f) }
    }
    private val recentLaptopSites = mutableStateListOf<Triple<String, String, String>>()
    private var lastDeviceSwitchSite = mutableStateOf<String?>(null)

    // Active task journey states
    private var activeTaskName = mutableStateOf("")
    private val activeTaskSteps = mutableStateListOf<Pair<String, String>>()

    // Manual Settings configs
    private var hubIp = mutableStateOf("")
    private var hubPort = mutableStateOf("8000")

    // Focus, Interrupt and Memory Settings States
    private var isFocusModeActive = mutableStateOf(false)
    private var isReduceInterruptActive = mutableStateOf(false)
    private val localMemories = mutableStateListOf(
        "User prefers working in dark mode on mobile and desktop.",
        "Typically starts evening wind-down around 9:30 PM.",
        "Likes to focus on coding during morning hours (9 AM - 12 PM).",
        "Prefers Minimal notifications during high-focus sessions.",
        "VS Code is the primary development workspace."
    )
    private val localDevices = mutableStateListOf(
        "Galaxy S26" to "ACTIVE",
        "Work Laptop" to "ACTIVE"
    )
    private var showMemoriesDialog = mutableStateOf(false)
    private var showDevicesDialog = mutableStateOf(false)
    private var showExplainStressDialog = mutableStateOf(false)
    private var showClearMemoriesConfirmDialog = mutableStateOf(false)
    private var showDisconnectDevicesConfirmDialog = mutableStateOf(false)

    // Permission states
    private var isAccessibilityGranted = mutableStateOf(false)
    private var isNotificationAccessGranted = mutableStateOf(false)
    private var isPostNotificationGranted = mutableStateOf(false)

    // BroadcastReceiver to update Compose states in real-time
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
                    val delay = intent.getLongExtra("average_delay_ms", 0L)
                    averageTypingCadenceMs.value = delay
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

                    val notifJson = JSONObject().apply {
                        put("type", "notification")
                        put("action_id", "notif_${timestamp}_${pkg.hashCode()}")
                        put("package", pkg)
                        put("title", title)
                        put("message", content)
                        put("agent", getAppNameFromPackage(pkg))
                        put("timestamp", timestamp)
                    }
                    attentionTasks.add(notifJson)
                }
                "com.friday.node.BATTERY_MODE_CHANGED" -> {
                    val mode = intent.getStringExtra("current_mode") ?: "ACTIVE"
                    isGhostMode.value = (mode == "GHOST")
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
                            val nowConnected = json.optBoolean("laptop_active", false)
                            isLaptopConnected.value = nowConnected

                            val recentTabsArr = json.optJSONArray("recent_tabs")
                            if (recentTabsArr != null && recentTabsArr.length() > 0) {
                                recentLaptopSites.clear()
                                for (i in 0 until minOf(recentTabsArr.length(), 3)) {
                                    val tabObj = recentTabsArr.optJSONObject(i) ?: continue
                                    val title = tabObj.optString("title", "Untitled")
                                    val displayUrl = tabObj.optString("url", "")
                                    val fullUrl = tabObj.optString("link", displayUrl)
                                    recentLaptopSites.add(Triple(title, displayUrl, fullUrl))
                                }
                                val topSite = recentLaptopSites.firstOrNull()
                                if (topSite != null && topSite.first != lastDeviceSwitchSite.value) {
                                    lastDeviceSwitchSite.value = topSite.first
                                    timelineEvents.add(0, TimelineEvent(
                                        "Device Switch",
                                        "Last on Laptop: ${topSite.first}",
                                        timeStr,
                                        "continuity"
                                    ))
                                }
                            }
                        }

                        if (type == "CLEAR_HANDOFF") {
                            val actionId = json.optString("action_id", "")
                            val reason = json.optString("reason", "dismissed")
                            if (actionId.isNotEmpty()) {
                                attentionTasks.removeAll { it.optString("action_id") == actionId }
                                if (activeActionCard.value?.optString("action_id") == actionId) {
                                    activeActionCard.value = null
                                }
                                val eventTitle = if (reason == "resumed") "Handoff Resumed" else "Handoff Dismissed"
                                val eventMsg = if (reason == "resumed") "Resumed laptop task" else "Dismissed laptop task"
                                timelineEvents.add(0, TimelineEvent(eventTitle, eventMsg, timeStr, "continuity"))
                            }
                        } else if (type == "FRIDAY_CARD") {
                            activeActionCard.value = json
                            val message = json.optString("message", "")
                            if (message.isNotEmpty()) {
                                wellbeingPrompt.value = message
                                val alreadyHas = attentionTasks.any { it.optString("action_id") == json.optString("action_id") }
                                if (!alreadyHas) attentionTasks.add(json)
                                val agent = json.optString("agent", "Orchestrator")
                                timelineEvents.add(0, TimelineEvent("FRIDAY Action", "[$agent Agent] $message", timeStr, "suggestion"))
                            }
                        } else if (type == "MEDIA_HANDOFF" || type == "PAGE_HANDOFF") {
                            activeActionCard.value = json
                            val message = json.optString("message", "")
                            if (message.isNotEmpty()) {
                                wellbeingPrompt.value = message
                                val alreadyHas = attentionTasks.any { it.optString("action_id") == json.optString("action_id") }
                                if (!alreadyHas) attentionTasks.add(json)
                                val agent = json.optString("agent", "Continuity")
                                timelineEvents.add(0, TimelineEvent("Continuity Shift", "[$agent Agent] $message", timeStr, "continuity"))
                            }
                        } else if (type == "LAPTOP_STATUS") {
                            // Handled above
                        } else {
                            val suggestion = json.optString("suggested_action")
                            if (!suggestion.isNullOrEmpty()) wellbeingPrompt.value = suggestion
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse Action payload: ${e.message}")
                    }
                }
            }
        }
    }

    private fun startFridayServices(fromOnboarding: Boolean = false) {
        try {
            val serviceIntent = Intent(this, FRIDAYForegroundService::class.java).apply {
                putExtra("from_onboarding", fromOnboarding)
            }
            startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch foreground service: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WebSocketManager.getInstance().init(this)
        voiceAssistantManager = VoiceAssistantManager(this).apply {
            onStateChanged = { newState -> assistantState.value = newState }
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

        val savedName = configManager.getUserName()
        userName.value = if (savedName.isNotEmpty()) savedName else getString(R.string.default_user_name)
        userRole.value = getString(R.string.default_user_role)

        if (isOnboardingComplete && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        if (isOnboardingComplete) {
            startFridayServices()
            checkPermissionsState()
            recalculateLocalStress()
        }

        CoroutineScope(Dispatchers.Main).launch {
            val db = RoomDatabase.getInstance(this@MainActivity)
            while (true) {
                bufferedEventsCount.value = db.getEventCount()
                delay(1000)
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                val durationMs = System.currentTimeMillis() - sessionStartTimeMs
                val mins = (durationMs / 60000).toInt()
                val secs = ((durationMs / 1000) % 60).toInt()
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
                    val onboardingViewModel = androidx.lifecycle.ViewModelProvider(this@MainActivity, factory)[OnboardingViewModel::class.java]
                    val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "android_node"

                    OnboardingScreen(
                        viewModel = onboardingViewModel,
                        deviceId = deviceId,
                        onOnboardingFinished = {
                            val newSavedName = configManager.getUserName()
                            if (newSavedName.isNotEmpty()) userName.value = newSavedName
                            showOnboarding.value = false
                            startFridayServices(fromOnboarding = true)
                            checkPermissionsState()
                            recalculateLocalStress()
                        }
                    )
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

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { voiceAssistantManager.cancel() } catch (e: Exception) {}
        try {
            unregisterReceiver(telemetryReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister receiver: ${e.message}")
        }
    }

    private fun getDynamicGreeting(name: String): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greetingRes = when (hour) {
            in 0..11 -> R.string.greeting_morning
            in 12..16 -> R.string.greeting_afternoon
            in 17..21 -> R.string.greeting_evening
            else -> R.string.greeting_night
        }
        return getString(greetingRes, name)
    }

    private fun getDynamicDate(): String {
        return try {
            val sdf = SimpleDateFormat("EEEE,\nMMMM d, yyyy", Locale.getDefault())
            sdf.format(Date())
        } catch (e: Exception) {
            "Wednesday,\nJune 4, 2026"
        }
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return when {
            packageName.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
            packageName.contains("slack", ignoreCase = true) -> "Slack"
            packageName.contains("gmail", ignoreCase = true) -> "Gmail"
            packageName.contains("telegram", ignoreCase = true) -> "Telegram"
            packageName.contains("discord", ignoreCase = true) -> "Discord"
            packageName.contains("teams", ignoreCase = true) -> "MS Teams"
            packageName.contains("instagram", ignoreCase = true) -> "Instagram"
            packageName.contains("facebook", ignoreCase = true) -> "Facebook"
            packageName.contains("twitter", ignoreCase = true) -> "Twitter"
            packageName.contains("android.gm", ignoreCase = true) -> "Gmail"
            else -> {
                val parts = packageName.split(".")
                if (parts.isNotEmpty()) parts.last().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                else "App"
            }
        }
    }

    private fun getSafeTimestamp(json: JSONObject): Long {
        val ts = json.optLong("timestamp", 0L)
        return if (ts == 0L) System.currentTimeMillis()
        else if (ts < 10000000000L) ts * 1000L
        else ts
    }

    private fun getProcessedAttentionTasks(tasks: List<JSONObject>): List<JSONObject> {
        val cards = tasks.filter { it.optString("type") != "notification" }
        val notifications = tasks.filter { it.optString("type") == "notification" }
        val groupedNotifications = notifications.groupBy { it.optString("package", it.optString("agent")) }
        val processedNotifications = mutableListOf<JSONObject>()

        for ((pkg, group) in groupedNotifications) {
            if (group.size == 1) {
                processedNotifications.add(group[0])
            } else {
                val sortedGroup = group.sortedByDescending { getSafeTimestamp(it) }
                val mostRecent = sortedGroup[0]
                val agentName = if (pkg.contains(".")) getAppNameFromPackage(pkg) else pkg
                val summaryText = "${group.size} notifications: " + sortedGroup.take(3).joinToString("; ") {
                    val title = it.optString("title", "")
                    val msg = it.optString("message", "")
                    if (title.isNotEmpty()) "$title: $msg" else msg
                } + (if (group.size > 3) "..." else "")

                val summaryJson = JSONObject().apply {
                    put("type", "notification")
                    put("action_id", mostRecent.optString("action_id"))
                    put("package", pkg)
                    put("agent", agentName)
                    put("message", summaryText)
                    put("timestamp", getSafeTimestamp(mostRecent))
                }
                processedNotifications.add(summaryJson)
            }
        }

        val combined = (cards + processedNotifications).sortedByDescending { getSafeTimestamp(it) }
        return combined.take(6)
    }

    private fun checkPermissionsState() {
        val configManager = OnboardingConfigManager(this)
        val accessibilityEnabled = isAccessibilityServiceEnabled(this, com.friday.node.service.FRIDAYAccessibilityService::class.java)
        isAccessibilityGranted.value = accessibilityEnabled
        configManager.setModuleEnabled("Accessibility & Touch", accessibilityEnabled)

        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val notificationEnabled = enabledListeners != null && enabledListeners.contains(packageName)
        isNotificationAccessGranted.value = notificationEnabled
        configManager.setModuleEnabled("Notification & Activity", notificationEnabled)

        val postNotificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        isPostNotificationGranted.value = postNotificationGranted

        val locationEnabled = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        configManager.setModuleEnabled("Location & Environment", locationEnabled)
    }

    private fun recalculateLocalStress() {
        val result = LocalFallbackEngine.evaluateOfflineContext(
            appSwitchesCount = appSwitchesCount.value,
            averageTypingCadenceMs = averageTypingCadenceMs.value,
            notificationsPerMin = notificationsCount.value
        )
        stressScore.value = result.stressScore
        val now = System.currentTimeMillis()
        if (now - lastPromptUpdateTimeMs >= 60000L || wellbeingPrompt.value.startsWith("FRIDAY ready")) {
            if (!isConnected.value || activeActionCard.value == null) {
                val newPrompt = result.empatheticRecommendation
                if (newPrompt != wellbeingPrompt.value) {
                    wellbeingPrompt.value = newPrompt
                    lastPromptUpdateTimeMs = now
                }
            }
        }

        val intensity = (result.stressScore.toFloat() / 100f).coerceIn(0.02f, 1.0f)
        if (activityIntensities.size >= 16) activityIntensities.removeAt(0)
        activityIntensities.add(intensity)

        val calculatedProductivity = (96 - appSwitchesCount.value * 2 - notificationsCount.value).coerceIn(10, 100)
        productivityPercentage.value = "$calculatedProductivity%"
        val calculatedFocus = (92 - appSwitchesCount.value * 3).coerceIn(10, 100)
        focusPercentage.value = "$calculatedFocus%"
        val calculatedConfidence = (88 - result.stressScore / 5).coerceIn(10, 100)
        confidencePercentage.value = "$calculatedConfidence%"
        val calculatedMemoryMatch = (82 + appSwitchesCount.value).coerceIn(50, 99)
        memoryMatchPercentage.value = "$calculatedMemoryMatch%"

        if (result.stressScore > 60) {
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            stressSpikeTime.value = sdf.format(Date())
        }
    }

    private fun triggerTelemetryRefresh(onComplete: () -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            val connected = WebSocketManager.getInstance().isConnected()
            isConnected.value = connected
            connectionStatus.value = if (connected) "Connected to Hub" else "Searching for Hub..."
            checkPermissionsState()
            recalculateLocalStress()
            val db = RoomDatabase.getInstance(this@MainActivity)
            bufferedEventsCount.value = db.getEventCount()
            if (connected) {
                try {
                    val syncMsg = JSONObject().apply {
                        put("type", "sync_request")
                        put("timestamp", System.currentTimeMillis())
                    }
                    WebSocketManager.getInstance().sendEvent(syncMsg.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send sync request to hub: ${e.message}")
                }
            }
            delay(800)
            onComplete()
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
        val expectedComponentName = ComponentName(context, service)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) return true
        }
        return false
    }

    // ─── Color Tokens ──────────────────────────────────────────────────────────
    var isDarkThemeGlobal by mutableStateOf(false)

    val ColorBlockLime get() = if (isDarkThemeGlobal) Color(0xFF2A3600) else Color(0xFFD6FF3D)
    val ColorBlockLilac get() = if (isDarkThemeGlobal) Color(0xFF1B1437) else Color(0xFFC6BFFF)
    val ColorBlockCream get() = if (isDarkThemeGlobal) Color(0xFF252216) else Color(0xFFFFF9E3)
    val ColorBlockMint get() = if (isDarkThemeGlobal) Color(0xFF092416) else Color(0xFFB2FFD6)
    val ColorBlockPink get() = if (isDarkThemeGlobal) Color(0xFF321028) else Color(0xFFFFB8EB)
    val ColorBlockCoral get() = if (isDarkThemeGlobal) Color(0xFF3E120A) else Color(0xFFFF8C70)
    val ColorBlockNavy get() = if (isDarkThemeGlobal) Color(0xFF0C1022) else Color(0xFF10162F)
    val ColorBackground get() = if (isDarkThemeGlobal) Color(0xFF121212) else Color(0xFFF9F9F9)

    // ─── Main Container ────────────────────────────────────────────────────────
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainContainer() {
        var activeTab by remember { mutableStateOf(0) }
        var isRefreshing by remember { mutableStateOf(false) }

        Scaffold(
            bottomBar = {
                CustomBottomNavBar(
                    activeTab = activeTab,
                    onTabSelected = { tab ->
                        activeTab = tab
                        showProactiveOverlay.value = false
                    },
                    onSparkClicked = { showProactiveOverlay.value = !showProactiveOverlay.value }
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    triggerTelemetryRefresh { isRefreshing = false }
                },
                modifier = Modifier.padding(innerPadding).fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (activeTab) {
                        0 -> DashboardTab()
                        1 -> MindTab()
                        2 -> ContinuityTab()
                        3 -> SettingsTab()
                    }
                    if (showProactiveOverlay.value) {
                        ProactiveOverlayScreen(onClose = { showProactiveOverlay.value = false })
                    }
                }
            }
        }
    }

    // ─── Bottom Nav Bar ────────────────────────────────────────────────────────
    @Composable
    fun CustomBottomNavBar(
        activeTab: Int,
        onTabSelected: (Int) -> Unit,
        onSparkClicked: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .border(width = 1.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                .padding(bottom = 8.dp, top = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavItem("HOME", Icons.Default.Home, activeTab == 0) { onTabSelected(0) }
                NavItem("INSIGHTS", Icons.Default.Favorite, activeTab == 1) { onTabSelected(1) }
                Spacer(modifier = Modifier.size(48.dp))
                NavItem("TIMELINE", Icons.Default.Refresh, activeTab == 2) { onTabSelected(2) }
                NavItem("SETTINGS", Icons.Default.Settings, activeTab == 3) { onTabSelected(3) }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-24).dp)
            ) {
                Button(
                    onClick = onSparkClicked,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface),
                    shape = CircleShape,
                    modifier = Modifier
                        .size(56.dp)
                        .border(2.dp, ColorBlockLime, CircleShape),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "AI Assistant",
                        tint = ColorBlockLime,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun NavItem(
        label: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable(onClick = onClick).padding(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // DASHBOARD TAB  (Minimised — only essential "right now" content)
    // Removed from here: Cognitive Load gauge, Summary cards row, Memory Moment,
    // Activity System Logs (navy), the duplicate cream System Logs card.
    // Those now live on Mind / Continuity tabs respectively.
    // ─────────────────────────────────────────────────────────────────────────────
    @Composable
    fun DashboardTab() {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "FRIDAY",
                        fontSize = 26.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (userName.value.isNotEmpty()) userName.value.take(1) else "U",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }
            }

            // ── Connection Banner ────────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.title_node_connection),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = connectionStatus.value,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isConnected.value) Color(0xFF10B981) else Color(0xFFEF4444))
                    )
                }
            }

            // ── Greeting ─────────────────────────────────────────────────────────
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = getDynamicGreeting(userName.value),
                        fontSize = 40.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.W300,
                        letterSpacing = (-0.96).sp,
                        lineHeight = 44.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = getMotivatingSubtitle(),
                        fontSize = 17.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.W300,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
            }

            // ── Notification Briefing Card ───────────────────────────────────────
            item {
                NotificationBriefingCard(tasks = attentionTasks, stressScore = stressScore.value)
            }

            // ── Active FRIDAY Recommendation Card (conditional) ──────────────────
            if (activeActionCard.value != null) {
                item {
                    ActiveRecommendationCard()
                }
            }

            // ── What Needs Attention (condensed — max 3 items, no simulate button)
            item {
                AttentionPanel()
            }

            item { Spacer(modifier = Modifier.height(40.dp)) }
        }
    }

    // Extracted so the home screen lambda stays readable
    @Composable
    private fun ActiveRecommendationCard() {
        val card = activeActionCard.value ?: return
        val actionId = card.optString("action_id", "")
        val type = card.optString("type", "")
        val message = card.optString("message", "")
        val score = card.optDouble("score", 0.0)
        val agentName = card.optString("agent", "Orchestrator")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        val isHandoff = type == "MEDIA_HANDOFF" || type == "PAGE_HANDOFF"
        val url = if (type == "MEDIA_HANDOFF") {
            "https://www.youtube.com/watch?v=${card.optString("video_id", "")}&t=${card.optInt("playback_timestamp_seconds", 0)}s"
        } else card.optString("url", "")

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ColorBlockLime.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(ColorBlockCoral))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$agentName Agent".uppercase(Locale.getDefault()),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(ColorBlockLime.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        val displayScore = if (score > 1.0) score.toInt() else (score * 100).toInt()
                        Text(
                            text = "SCORE: $displayScore%",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Text(
                    text = message,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 22.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
                        WebSocketManager.getInstance().sendFeedback(actionId, "dismissed")
                        attentionTasks.removeAll { it.optString("action_id") == actionId }
                        activeActionCard.value = null
                        wellbeingPrompt.value = "Ambient tracking active. System stable."
                        timelineEvents.add(0, TimelineEvent("Handoff Dismissed", "Dismissed laptop task", sdf.format(Date()), "continuity"))
                        showToast("Recommendation dismissed")
                    }) {
                        Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
                            WebSocketManager.getInstance().sendFeedback(actionId, "helpful")
                            attentionTasks.removeAll { it.optString("action_id") == actionId }
                            activeActionCard.value = null
                            if (isHandoff && url.isNotEmpty()) {
                                try {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                                } catch (e: Exception) { Log.e(TAG, "Failed to launch URL: ${e.message}") }
                            }
                            wellbeingPrompt.value = "Feedback logged. Optimizing model."
                            timelineEvents.add(0, TimelineEvent("Handoff Resumed", "Resumed laptop task", sdf.format(Date()), "continuity"))
                            showToast(if (isHandoff) "Task resumed!" else "Thank you for your feedback!")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isHandoff) Icons.Default.PlayArrow else Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isHandoff) "Resume" else "Helpful", color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Condensed attention panel for Home — max 3 items, no simulate/prioritize
    @Composable
    private fun AttentionPanel() {
        val processedTasks = getProcessedAttentionTasks(attentionTasks).take(3)

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = ColorBlockCream),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_what_needs_attention),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                if (processedTasks.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "FRIDAY is learning your flow...",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Move around and start your routines. I'll prioritize what needs attention here.",
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        processedTasks.forEachIndexed { index, task ->
                            val actionId = task.optString("action_id", "act_$index")
                            val agent = task.optString("agent", "Orchestrator")
                            val message = task.optString("message", "Attention required")

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = String.format("%02d", index + 1),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "[$agent] $message",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                val btnText = if (task.optString("type") == "notification") "Clear" else stringResource(R.string.btn_action)
                                Button(
                                    onClick = {
                                        if (task.optString("type") == "notification") {
                                            val pkg = task.optString("package")
                                            if (pkg.isNotEmpty()) attentionTasks.removeAll { it.optString("package") == pkg || it.optString("action_id") == actionId }
                                            else attentionTasks.removeAll { it.optString("action_id") == actionId }
                                            showToast("Alert cleared")
                                        } else {
                                            activeTaskName.value = "Optimize: $message"
                                            activeTaskSteps.clear()
                                            activeTaskSteps.add(Pair("Analyze VS Code metrics", "COMPLETED"))
                                            activeTaskSteps.add(Pair("Verify DB query efficiency", "IN_PROGRESS"))
                                            activeTaskSteps.add(Pair("Generate execution plan", "PENDING"))
                                            showToast("Continuous journey started: $message")
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface),
                                    shape = RoundedCornerShape(9999.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(btnText, fontSize = 11.sp, color = MaterialTheme.colorScheme.surface)
                                }
                            }
                            if (index < processedTasks.size - 1) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            }
                        }
                    }
                }

                // "See all" link if there are more tasks
                if (attentionTasks.size > 3) {
                    Text(
                        text = "+${attentionTasks.size - 3} more — see Timeline tab",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // MIND TAB  (Insights)
    // Added here: Cognitive Load gauge (moved from Home), Radar, Agent blocks, Signals
    // ─────────────────────────────────────────────────────────────────────────────
    @Composable
    fun MindTab() {
        val subtitleText = if (stressScore.value == 0) "Learning routine..."
        else if (stressScore.value >= 75) "Elevated Stress"
        else if (stressScore.value >= 50) "Focused & Engaged"
        else "Calm & Balanced"

        val insightText = if (stressScore.value == 0) {
            "Ambient tracking active. As we collect telemetry from your app switching and typing speeds, personalized cognitive insights will appear here."
        } else if (stressScore.value >= 75) {
            "Your stress score is high (${stressScore.value}%). We detect multiple context transitions. A short break is recommended."
        } else if (appSwitchesCount.value > 8) {
            "Frequent app switching detected. Try to isolate your environment to stay in deep focus."
        } else {
            "Focus metrics look solid today. Your typing cadence is consistent and app switching is minimal."
        }

        val burnoutScore = if (stressScore.value == 0) 10f else (stressScore.value * 0.7f).coerceIn(10f, 100f)
        val fatigueScore = if (stressScore.value == 0) 15f else (appSwitchesCount.value * 6f + averageTypingCadenceMs.value / 25f).coerceIn(10f, 100f)
        val socialScore = if (notificationsCount.value == 0) 12f else (notificationsCount.value * 12f).coerceIn(10f, 100f)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header block
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = ColorBlockLime),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = stringResource(R.string.title_current_status),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.title_mindspace),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = subtitleText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Empathetic Insight
            item {
                Text(
                    text = insightText,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.W300,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            // ── Cognitive Load Gauge (moved from Home) ───────────────────────────
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = ColorBlockMint),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.title_cognitive_pulse),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "High switching detected recently.",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = if (isGhostMode.value) "Ghost mode active. Telemetry simplified." else "High-fidelity telemetry streaming.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(100.dp)) {
                                CircularProgressIndicator(
                                    progress = { stressScore.value / 100f },
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    strokeWidth = 10.dp,
                                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                                )
                                Text(
                                    text = "${stressScore.value}%",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // Radar Chart
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = ColorBlockNavy),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.title_cognitive_risk_radar),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.Start)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        BiometricsRadarChart(
                            stress = stressScore.value.toFloat(),
                            burnout = burnoutScore,
                            fatigue = fatigueScore,
                            social = socialScore
                        )
                    }
                }
            }

            // Current State metrics grid
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.title_current_state),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val stressSubtitle = when {
                            stressScore.value >= 75 -> "Elevated"
                            stressScore.value >= 50 -> "Moderate"
                            else -> "Stable"
                        }
                        val focusSubtitle = when {
                            focusPercentage.value == "Learning..." -> "Learning..."
                            appSwitchesCount.value > 5 -> "Distracted"
                            else -> "High Perf"
                        }
                        val cadenceValue = if (averageTypingCadenceMs.value == 0L) "--" else "${averageTypingCadenceMs.value}ms"
                        val cadenceSubtitle = when {
                            averageTypingCadenceMs.value == 0L -> "No Input"
                            averageTypingCadenceMs.value in 10..180 -> "Fast"
                            averageTypingCadenceMs.value > 600 -> "Slow"
                            else -> "Consistent"
                        }
                        BiometricMetricItem(label = "Stress", value = "${stressScore.value}%", subtitle = stressSubtitle)
                        BiometricMetricItem(label = "Focus", value = focusPercentage.value, subtitle = focusSubtitle)
                        BiometricMetricItem(label = "Cadence", value = cadenceValue, subtitle = cadenceSubtitle)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val confidenceSubtitle = when {
                            confidencePercentage.value == "Learning..." -> "Learning..."
                            stressScore.value >= 75 -> "Fluctuating"
                            else -> "Stable"
                        }
                        val socialValue = if (notificationsCount.value == 0) "0%" else "${socialScore.toInt()}%"
                        val socialSubtitle = when {
                            notificationsCount.value == 0 -> "Quiet"
                            socialScore >= 60 -> "Demanding"
                            else -> "Introvert"
                        }
                        BiometricMetricItem(label = "Confidence", value = confidencePercentage.value, subtitle = confidenceSubtitle)
                        BiometricMetricItem(label = "Social Load", value = socialValue, subtitle = socialSubtitle)
                    }
                }
            }

            // Agent Blocks
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Emotion Agent
                    val emotionTitle = if (stressScore.value == 0) "Stable" else if (stressScore.value >= 70) "High Arousal" else "Focused & Confident"
                    val emotionDesc = if (stressScore.value == 0) "Ambient tracking active. Analyzing focus patterns." else if (stressScore.value >= 70) "Slight stress spike detected. Take deep breaths." else "Stable typing rhythms and low application switching detected."
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockMint), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = stringResource(R.string.title_emotion_agent), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            Text(text = emotionTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(text = emotionDesc, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(confidencePercentage.value, fontSize = 24.sp, fontWeight = FontWeight.Black)
                                Text(text = stringResource(R.string.title_confidence_score), fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    }

                    // Burnout Agent
                    val burnoutTitle = if (stressScore.value == 0) "Risk: Stable" else if (burnoutScore >= 60) "Risk: High" else if (burnoutScore >= 35) "Risk: Moderate" else "Risk: Low"
                    val burnoutDesc = if (stressScore.value == 0) "No active stress patterns detected. Tracking balance." else if (burnoutScore >= 60) "High continuous stress. Prioritize rest cycles immediately." else "Normal sleep cycles, consistent output, and language neutrality."
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockPink), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = stringResource(R.string.title_burnout_agent), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            Text(text = burnoutTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(text = burnoutDesc, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                        }
                    }

                    // Social Agent
                    val socialTitle = if (notificationsCount.value == 0) "Risk: Stable" else if (socialScore >= 60) "Risk: High" else if (socialScore >= 35) "Risk: Moderate" else "Risk: Low"
                    val socialDesc = if (notificationsCount.value == 0) "No recent communication spikes. Social load is light." else if (socialScore >= 60) "High messaging volume. Consider putting phone on Do Not Disturb." else "Impending tasks stable. Social load is well within limits."
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockCream), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = stringResource(R.string.title_social_agent), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            Text(text = socialTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(text = socialDesc, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                        }
                    }
                }
            }

            // Signals
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(text = stringResource(R.string.title_why_signals), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SignalRowItem(label = "App Switching", value = "${appSwitchesCount.value} events")
                        SignalRowItem(label = "Active Location", value = locationState.value.uppercase(Locale.getDefault()))
                        SignalRowItem(label = "Typing Delay", value = "${averageTypingCadenceMs.value} ms")
                        SignalRowItem(label = "Session Duration", value = focusTimeDisplay.value)
                    }
                }
            }

            // Behavioral Anomaly & Memory Correlation
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val anomalyPercentage = remember(appSwitchesCount.value, averageTypingCadenceMs.value, stressScore.value) {
                        (appSwitchesCount.value * 2 + (averageTypingCadenceMs.value / 200).toInt() + stressScore.value / 4).coerceIn(2, 98)
                    }
                    val dayOfWeek = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())

                    val anomalyLabel: String
                    val anomalyColor: Color
                    val anomalyBg: Color
                    val anomalyDesc: String

                    if (anomalyPercentage > 60) {
                        anomalyLabel = "ELEVATED DEVIATION"; anomalyColor = Color(0xFFE57373); anomalyBg = Color(0x1AE57373)
                        anomalyDesc = "Frequent app switching and altered cadence indicate context fatigue."
                    } else if (anomalyPercentage > 35) {
                        anomalyLabel = "MODERATE DEVIATION"; anomalyColor = Color(0xFFFFB74D); anomalyBg = Color(0x1AFFB74D)
                        anomalyDesc = "Mild pacing variation detected. Monitor focus cadence."
                    } else {
                        anomalyLabel = "STABLE SYSTEM PATTERN"; anomalyColor = Color(0xFF81C784); anomalyBg = Color(0x1A81C784)
                        anomalyDesc = "Current usage aligns cleanly with historical baseline."
                    }

                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(text = stringResource(R.string.title_behavioral_anomaly), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(anomalyBg).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                    Text(text = anomalyLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = anomalyColor)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(text = "Pattern Analysis: $anomalyPercentage% deviation from standard $dayOfWeek baseline.", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(text = anomalyDesc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    val cal = java.util.Calendar.getInstance()
                    cal.add(java.util.Calendar.MONTH, -3)
                    val pastMonth = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
                    val matchPctStr = memoryMatchPercentage.value
                    val matchPctInt = matchPctStr.replace("%", "").toIntOrNull() ?: 0

                    val matchLabel: String; val matchColor: Color; val matchBg: Color
                    if (matchPctInt > 75) { matchLabel = "HIGH HISTORICAL FIT"; matchColor = Color(0xFF64B5F6); matchBg = Color(0x1A64B5F6) }
                    else if (matchPctInt > 45) { matchLabel = "MODERATE FIT"; matchColor = Color(0xFFBA68C8); matchBg = Color(0x1ABA68C8) }
                    else { matchLabel = "NOVEL PATTERNFLOW"; matchColor = Color(0xFF90A4AE); matchBg = Color(0x1A90A4AE) }

                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(text = stringResource(R.string.title_memory_correlation), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(matchBg).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                    Text(text = matchLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = matchColor)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(text = "Similar Past Events: $matchPctStr Match to $pastMonth Baseline.", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(6.dp))
                            val memoryOutcome = when {
                                stressScore.value >= 75 -> "Outcome: High cognitive load detected. Recommend task transition."
                                stressScore.value >= 50 -> "Outcome: Moderate stress. High focus but stable output."
                                else -> "Outcome: Balanced flow state. Smooth task transitions."
                            }
                            Text(text = memoryOutcome, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Action Buttons
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            isFocusModeActive.value = !isFocusModeActive.value
                            if (isFocusModeActive.value) {
                                sessionStartTimeMs = System.currentTimeMillis()
                                focusTimeDisplay.value = "0s"
                                showToast("Focus mode activated!")
                            } else showToast("Focus mode deactivated!")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isFocusModeActive.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface),
                        shape = RoundedCornerShape(9999.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isFocusModeActive.value) "Stop Focus" else "Start Focus",
                            color = if (isFocusModeActive.value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.surface,
                            fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = {
                            isReduceInterruptActive.value = !isReduceInterruptActive.value
                            showToast(if (isReduceInterruptActive.value) "Interruption blocker active." else "Interruption blocker deactivated.")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isReduceInterruptActive.value) MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f) else Color.Transparent),
                        shape = RoundedCornerShape(9999.dp),
                        modifier = Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(9999.dp))
                    ) {
                        Text(text = if (isReduceInterruptActive.value) "Allow Interrupt" else "Reduce Interrupt", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
                    }
                    Button(
                        onClick = { showExplainStressDialog.value = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(9999.dp),
                        modifier = Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(9999.dp))
                    ) {
                        Text("Explain Stress", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(40.dp)) }
        }
    }

    @Composable
    fun BiometricMetricItem(label: String, value: String, subtitle: String) {
        Column(modifier = Modifier.width(90.dp)) {
            Text(text = label.uppercase(), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, fontSize = 24.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
            Text(text = subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground)
        }
    }

    @Composable
    fun SignalRowItem(label: String, value: String, isAlert: Boolean = false) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (isAlert) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurface)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
    }

    @Composable
    fun BiometricsRadarChart(stress: Float, burnout: Float, fatigue: Float, social: Float) {
        Box(modifier = Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(200.dp)) {
                val center = size.width / 2
                val maxRadius = size.width / 2 * 0.8f
                for (i in 1..4) {
                    val r = maxRadius * (i.toFloat() / 4)
                    drawRect(color = Color.White.copy(alpha = 0.15f), topLeft = androidx.compose.ui.geometry.Offset(center - r, center - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(width = 1.dp.toPx()))
                }
                drawLine(color = Color.White.copy(alpha = 0.2f), start = androidx.compose.ui.geometry.Offset(center, center - maxRadius), end = androidx.compose.ui.geometry.Offset(center, center + maxRadius), strokeWidth = 1.dp.toPx())
                drawLine(color = Color.White.copy(alpha = 0.2f), start = androidx.compose.ui.geometry.Offset(center - maxRadius, center), end = androidx.compose.ui.geometry.Offset(center + maxRadius, center), strokeWidth = 1.dp.toPx())
                val path = Path().apply {
                    moveTo(center, center - maxRadius * (stress / 100f))
                    lineTo(center + maxRadius * (social / 100f), center)
                    lineTo(center, center + maxRadius * (burnout / 100f))
                    lineTo(center - maxRadius * (fatigue / 100f), center)
                    close()
                }
                drawPath(path = path, color = ColorBlockLime.copy(alpha = 0.4f))
                drawPath(path = path, color = ColorBlockLime, style = Stroke(width = 2.dp.toPx()))
            }
            Box(modifier = Modifier.fillMaxSize()) {
                Text("STRESS (${stress.toInt()})", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.TopCenter))
                Text("BURNOUT (${burnout.toInt()})", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.BottomCenter))
                Text("FATIGUE (${fatigue.toInt()})", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
                Text("SOCIAL (${social.toInt()})", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // CONTINUITY TAB
    // Added here: Summary metric cards row (moved from Home), Memory Moment card
    // (moved from Home), System Logs (moved from Home), full Attention task list
    // with Simulate/Prioritize button, plus all original Continuity content.
    // ─────────────────────────────────────────────────────────────────────────────
    @Composable
    fun ContinuityTab() {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(text = stringResource(R.string.title_life_timeline), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 4.dp))
                Text(text = getDynamicDate(), fontSize = 40.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W300, letterSpacing = (-0.96).sp, lineHeight = 44.sp, color = MaterialTheme.colorScheme.onBackground)
            }

            // ── Summary Metric Cards (moved from Home) ───────────────────────────
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("PRODUCTIVITY", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        Text(productivityPercentage.value, fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Column {
                        Text("PEAK FOCUS", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        Text(peakFocusTime.value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Column {
                        Text("STRESS SPIKE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        Text(stressSpikeTime.value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            // ── Summary Cards Horizontal Scroll (moved from Home) ────────────────
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    item {
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockLilac), modifier = Modifier.width(180.dp).height(140.dp)) {
                            Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Text(text = stringResource(R.string.title_messages), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Column {
                                    Text(text = "${notificationsCount.value}", fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                                    Text(text = stringResource(R.string.title_total_intercepted), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                    item {
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.width(180.dp).height(140.dp)) {
                            Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Text(text = stringResource(R.string.title_focus_time), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Column {
                                    Text(text = focusTimeDisplay.value, fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                                    Text(text = "+${focusImprovementPercent.value} VS AVERAGE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                    item {
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockPink), modifier = Modifier.width(180.dp).height(140.dp)) {
                            Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Text(text = "CONTEXT", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Column {
                                    val currentLoc = locationState.value
                                    val contextTitle = if (currentLoc.isEmpty() || currentLoc == "unknown" || currentLoc == "home") "Learning Flow" else currentLoc.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } + " Mode"
                                    val contextSub = if (currentLoc.isEmpty() || currentLoc == "unknown" || currentLoc == "home") "Use more to map context" else "Match: Current Location"
                                    Text(text = contextTitle, fontSize = 18.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                                    Text(text = contextSub, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }
            }

            // Activity Intensity Visualizer
            item {
                ProductivityIntensityVisualizer()
            }

            // Today's Story
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = stringResource(R.string.title_today_story), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    val storyText = if (stressScore.value == 0) {
                        "Ambient tracking active. As we collect telemetry from your device switches and notification intercepts, your personalized daily story will build here."
                    } else {
                        "Today's journey began with tracking active in the ${locationState.value.lowercase(Locale.getDefault())} sanctuary. So far, we have logged ${appSwitchesCount.value} application switches and intercepted ${notificationsCount.value} distractions. Your cognitive stress is hovering around ${stressScore.value}%, indicating a steady level of focus."
                    }
                    Text(text = storyText, fontSize = 20.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W300, color = MaterialTheme.colorScheme.onSurface, lineHeight = 28.sp)
                }
            }

            // ── Memory Moment Card (moved from Home) ─────────────────────────────
            if (!isMemoryMomentDismissed.value) {
                item {
                    val currentLoc = locationState.value
                    val memoryTitle = if (currentLoc.isEmpty() || currentLoc == "unknown") "Learning Routines" else currentLoc.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    val memoryBody = if (currentLoc.isEmpty() || currentLoc == "unknown") {
                        "Move around, let me know if you forget. I will map your focus triggers and routines here."
                    } else {
                        "You typically activate 'Deep Focus' mode here. Repeat setup?"
                    }
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = "MEMORY MOMENT", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            Text(text = memoryTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(text = memoryBody, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                            if (currentLoc.isNotEmpty() && currentLoc != "unknown") {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = {
                                        isFocusModeActive.value = true
                                        sessionStartTimeMs = System.currentTimeMillis()
                                        focusTimeDisplay.value = "0s"
                                        showToast("Focus mode activated from routine memory!")
                                        isMemoryMomentDismissed.value = true
                                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(9999.dp)) {
                                        Text("Repeat Setup", color = MaterialTheme.colorScheme.surface)
                                    }
                                    Button(onClick = {
                                        isMemoryMomentDismissed.value = true
                                        showToast("Routine suggestion dismissed")
                                    }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), shape = RoundedCornerShape(9999.dp), modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(9999.dp))) {
                                        Text("Dismiss", color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Device Continuity
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(text = stringResource(R.string.title_device_continuity), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DeviceContinuityRow(icon = "phone", name = "Galaxy Phone", active = "Active: ${focusTimeDisplay.value}")
                        DeviceContinuityRow(icon = "laptop", name = "Work Laptop", active = if (isLaptopConnected.value) "Active (WebSocket Connected)" else "Offline")
                    }
                }
            }

            // Task Journey
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(text = stringResource(R.string.title_task_journey), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(24.dp))) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            if (activeTaskName.value.isEmpty()) {
                                Text(text = "Awaiting task journey...", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = "No active workspace journey selected. Start a task from the What Needs Attention section above.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            } else {
                                Text(text = activeTaskName.value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.height(16.dp))
                                activeTaskSteps.forEach { step ->
                                    TaskJourneyStep(title = step.first, status = step.second, isDone = step.second == "COMPLETED", isCurrent = step.second == "IN_PROGRESS")
                                }
                            }
                        }
                    }
                }
            }

            // ── Full Attention List with Simulate/Prioritize (moved from Home) ────
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = ColorBlockCream),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(text = "ALL PENDING ACTIONS", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

                        val allTasks = getProcessedAttentionTasks(attentionTasks)
                        if (allTasks.isEmpty()) {
                            Text(text = "No pending actions.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                allTasks.forEachIndexed { index, task ->
                                    val actionId = task.optString("action_id", "act_$index")
                                    val agent = task.optString("agent", "Orchestrator")
                                    val message = task.optString("message", "Attention required")
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = String.format("%02d", index + 1), fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(text = "[$agent] $message", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        val btnText = if (task.optString("type") == "notification") "Clear" else stringResource(R.string.btn_action)
                                        Button(
                                            onClick = {
                                                if (task.optString("type") == "notification") {
                                                    val pkg = task.optString("package")
                                                    if (pkg.isNotEmpty()) attentionTasks.removeAll { it.optString("package") == pkg || it.optString("action_id") == actionId }
                                                    else attentionTasks.removeAll { it.optString("action_id") == actionId }
                                                    showToast("Alert cleared")
                                                } else {
                                                    activeTaskName.value = "Optimize: $message"
                                                    activeTaskSteps.clear()
                                                    activeTaskSteps.add(Pair("Analyze VS Code metrics", "COMPLETED"))
                                                    activeTaskSteps.add(Pair("Verify DB query efficiency", "IN_PROGRESS"))
                                                    activeTaskSteps.add(Pair("Generate execution plan", "PENDING"))
                                                    showToast("Continuous journey started: $message")
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface),
                                            shape = RoundedCornerShape(9999.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) { Text(btnText, fontSize = 11.sp, color = MaterialTheme.colorScheme.surface) }
                                    }
                                    if (index < allTasks.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                }
                            }
                        }

                        Button(
                            onClick = {
                                if (attentionTasks.isEmpty()) {
                                    val mockCard = JSONObject().apply {
                                        put("type", "FRIDAY_CARD")
                                        put("action_id", "act_simulated_01")
                                        put("message", "We detected high CPU usage on VS Code. Run profile_schema.py to inspect queries.")
                                        put("agent", "Decision")
                                        put("score", 0.94)
                                        put("laptop_active", true)
                                    }
                                    attentionTasks.add(mockCard)
                                    activeActionCard.value = mockCard
                                    isLaptopConnected.value = true
                                    showToast("Workspace journey simulated!")
                                } else {
                                    val sortedTasks = attentionTasks.sortedByDescending { it.optDouble("score", 0.0) }
                                    attentionTasks.clear()
                                    attentionTasks.addAll(sortedTasks)
                                    showToast("Tasks prioritized by urgency score.")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            shape = RoundedCornerShape(9999.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(9999.dp))
                        ) {
                            Text(text = if (attentionTasks.isEmpty()) "Simulate Workspace Journey" else stringResource(R.string.btn_prioritize_automatically), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Friday Actions bento
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(text = stringResource(R.string.title_friday_actions), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.weight(1f).height(160.dp).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(24.dp))) {
                            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Icon(imageVector = Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                                Column {
                                    Text("Filtered", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Text("${notificationsCount.value} suppressed distractions.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.weight(1f).height(160.dp).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(24.dp))) {
                            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Icon(imageVector = Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                                Column {
                                    Text("Focus Rec", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Text(text = if (stressScore.value > 60) "Optimal window in 30m." else "Optimal window at 9:00 PM.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    if (stressScore.value > 65) {
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = if (isDarkThemeGlobal) Color(0xFF4C0505) else Color(0xFFFEE2E2)), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = if (isDarkThemeGlobal) Color(0xFFFCA5A5) else Color(0xFF991B1B), modifier = Modifier.size(20.dp))
                                Text("Fatigue Predicted", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (isDarkThemeGlobal) Color(0xFFFCA5A5) else Color(0xFF991B1B))
                                Text("Sleep cycle correction needed. Rest suggested before 11:30 PM.", fontSize = 13.sp, color = if (isDarkThemeGlobal) Color(0xFFFECACA) else Color(0xFF7F1D1D))
                            }
                        }
                    } else {
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = if (isDarkThemeGlobal) Color(0xFF064E3B) else Color(0xFFD1FAE5)), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = if (isDarkThemeGlobal) Color(0xFF34D399) else Color(0xFF065F46), modifier = Modifier.size(20.dp))
                                Text("Cognitive Balance Stable", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (isDarkThemeGlobal) Color(0xFF34D399) else Color(0xFF065F46))
                                Text("Great job keeping your focus window clear of context switching.", fontSize = 13.sp, color = if (isDarkThemeGlobal) Color(0xFFA7F3D0) else Color(0xFF065F46))
                            }
                        }
                    }
                }
            }

            // ── System Logs (moved from Home, navy card) ─────────────────────────
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockNavy), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(text = "SYSTEM LOGS & ACTIVITY", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f))
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(imageVector = Icons.Default.Star, contentDescription = null, tint = ColorBlockLime, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(text = "Active App Switch", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(text = "Focused application: ${recentApp.value}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                                }
                            }
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(imageVector = Icons.Default.Star, contentDescription = null, tint = ColorBlockLime, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(text = "Notification Intercepted", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(text = lastNotification.value, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(imageVector = Icons.Default.Star, contentDescription = null, tint = ColorBlockLime, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(text = "Local Offline Cache Buffer", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(text = "Buffered events count: ${bufferedEventsCount.value}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Recent Laptop Sites
            if (recentLaptopSites.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(text = "RECENT SITES · LAPTOP", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        recentLaptopSites.take(3).forEach { (title, displayUrl, fullUrl) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (fullUrl.isNotEmpty()) {
                                            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }) }
                                            catch (e: Exception) { Log.e(TAG, "Failed to open URL: ${e.message}") }
                                        }
                                    }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(40.dp).background(ColorBlockLilac, CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(text = displayUrl.ifEmpty { fullUrl }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            // Live Activity Log
            if (timelineEvents.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(text = "LIVE ACTIVITY LOG", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            timelineEvents.take(10).forEach { event -> TimelineRowItem(event = event) }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(40.dp)) }
        }
    }

    @Composable
    fun ProductivityIntensityVisualizer() {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockLime), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(text = "ACTIVITY INTENSITY", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(modifier = Modifier.height(24.dp))
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
            "app" -> Icons.Default.Build
            "notification" -> Icons.Default.Notifications
            "location" -> Icons.Default.Home
            "suggestion" -> Icons.Default.Star
            "continuity" -> Icons.Default.PlayArrow
            else -> Icons.Default.Info
        }
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.background, CircleShape), contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = event.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(text = event.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = event.time, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    fun DeviceContinuityRow(icon: String, name: String, active: String) {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.background, CircleShape), contentAlignment = Alignment.Center) {
                Icon(imageVector = if (icon == "phone") Icons.Default.Phone else Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(text = active, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    fun TaskJourneyStep(title: String, status: String, isDone: Boolean = false, isCurrent: Boolean = false) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(20.dp).clip(CircleShape).background(when { isDone -> MaterialTheme.colorScheme.onSurface; isCurrent -> ColorBlockLime; else -> Color.LightGray }),
                contentAlignment = Alignment.Center
            ) {
                if (isDone) Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(12.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = title, fontSize = 14.sp, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, color = MaterialTheme.colorScheme.onBackground)
                Text(text = status, fontSize = 11.sp, color = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SETTINGS TAB  (unchanged from original)
    // ─────────────────────────────────────────────────────────────────────────────
    @Composable
    fun SettingsTab() {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(text = stringResource(R.string.title_trust_center), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 4.dp))
                Text(text = "Settings", fontSize = 40.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, letterSpacing = (-0.96).sp, color = MaterialTheme.colorScheme.onBackground)
                Text(text = "Manage privacy, devices and personalization.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Profile
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(24.dp)).padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface), contentAlignment = Alignment.Center) {
                        Text(text = if (userName.value.isNotEmpty()) userName.value.take(1) else "U", color = MaterialTheme.colorScheme.surface, fontSize = 32.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(text = userName.value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = userRole.value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Privacy & Data
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockLime), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(text = stringResource(R.string.title_privacy_data), fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                        val sharedPrefs = remember { getSharedPreferences("friday_settings", MODE_PRIVATE) }
                        var dataProcessing by remember { mutableStateOf(sharedPrefs.getBoolean("data_processing", true)) }
                        var cloudBackup by remember { mutableStateOf(sharedPrefs.getBoolean("cloud_backup", true)) }
                        var dataEncryption by remember { mutableStateOf(sharedPrefs.getBoolean("data_encryption", true)) }
                        ToggleRow(label = stringResource(R.string.hint_data_processing), subtitle = stringResource(R.string.hint_mostly_on_device), checked = dataProcessing, onCheckedChange = { dataProcessing = it; sharedPrefs.edit().putBoolean("data_processing", it).apply(); showToast(if (it) "On-device processing enabled" else "On-device processing disabled") })
                        ToggleRow(label = stringResource(R.string.hint_cloud_backup), checked = cloudBackup, onCheckedChange = { cloudBackup = it; sharedPrefs.edit().putBoolean("cloud_backup", it).apply(); showToast(if (it) "Cloud backup enabled" else "Cloud backup disabled") })
                        ToggleRow(label = stringResource(R.string.hint_data_encryption), checked = dataEncryption, onCheckedChange = { dataEncryption = it; sharedPrefs.edit().putBoolean("data_encryption", it).apply(); showToast(if (it) "Data encryption enabled" else "Data encryption disabled") })
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) }) }
                                catch (e: Exception) { showToast(getString(R.string.msg_open_permission_manager)) }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        ) { Text(stringResource(R.string.btn_manage_permissions), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            // System Permissions
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(24.dp))) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(text = stringResource(R.string.title_system_permissions), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Accessibility Service", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text("For active application context switches", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, colors = ButtonDefaults.buttonColors(containerColor = if (isAccessibilityGranted.value) Color(0xFF10B981) else Color(0xFFEF4444)), shape = RoundedCornerShape(9999.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                                Text(if (isAccessibilityGranted.value) "Granted" else "Grant", fontSize = 11.sp, color = Color.White)
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Notification Listener", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text("For intercepting notifications", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(onClick = { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }, colors = ButtonDefaults.buttonColors(containerColor = if (isNotificationAccessGranted.value) Color(0xFF10B981) else Color(0xFFEF4444)), shape = RoundedCornerShape(9999.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                                Text(if (isNotificationAccessGranted.value) "Granted" else "Grant", fontSize = 11.sp, color = Color.White)
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Post Notifications", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Text("Foreground sensing alert channel", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Button(onClick = { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101) }, colors = ButtonDefaults.buttonColors(containerColor = if (isPostNotificationGranted.value) Color(0xFF10B981) else Color(0xFFEF4444)), shape = RoundedCornerShape(9999.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                                    Text(if (isPostNotificationGranted.value) "Granted" else "Grant", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            // Memory
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockLilac), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(text = stringResource(R.string.title_memory), fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("${localMemories.size}", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface); Text("STORED", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                            Column { Text("${bufferedEventsCount.value}", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface); Text("EVENTS", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                            Column {
                                val simulatedSize = String.format(Locale.US, "%.1f MB", 0.4f + localMemories.size * 0.15f)
                                Text(simulatedSize, fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface); Text("SIZE", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { showMemoriesDialog.value = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_view_memories), color = MaterialTheme.colorScheme.surface) }
                            Button(onClick = { showMemoriesDialog.value = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_forget_memory), color = MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                }
            }

            // Devices
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockNavy), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column {
                            Text(text = stringResource(R.string.title_devices), fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.White)
                            Text(text = "Synced 2m ago", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.White.copy(alpha = 0.4f), fontWeight = FontWeight.Bold)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (localDevices.isEmpty()) {
                                Text("No synced devices.", color = Color.White.copy(alpha = 0.6f), fontStyle = FontStyle.Italic, fontSize = 14.sp)
                            } else {
                                localDevices.forEachIndexed { idx, (deviceName, status) ->
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val icon = if (deviceName.lowercase().contains("laptop") || deviceName.lowercase().contains("mac") || deviceName.lowercase().contains("pc")) Icons.Default.Build else Icons.Default.Phone
                                            Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(deviceName, color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                        Box(modifier = Modifier.background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                            Text(status, fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    if (idx < localDevices.size - 1) HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                                }
                            }
                        }
                        Button(onClick = { showDevicesDialog.value = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.btn_manage_devices), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Hub IP Override
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(24.dp))) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(text = stringResource(R.string.title_hub_override_ip_config), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(value = hubIp.value, onValueChange = { hubIp.value = it }, label = { Text("Hub IP Address", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, focusedBorderColor = MaterialTheme.colorScheme.onSurface, unfocusedBorderColor = Color.LightGray), modifier = Modifier.weight(2f))
                            OutlinedTextField(value = hubPort.value, onValueChange = { hubPort.value = it }, label = { Text("Port", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, focusedBorderColor = MaterialTheme.colorScheme.onSurface, unfocusedBorderColor = Color.LightGray), modifier = Modifier.weight(1f))
                        }
                        Button(onClick = { if (hubIp.value.isNotEmpty()) WebSocketManager.getInstance().connect("ws://${hubIp.value}:${hubPort.value}/ws/android") }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.btn_connect_manually), color = MaterialTheme.colorScheme.surface)
                        }
                    }
                }
            }

            // Assistance Style
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(24.dp))) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(text = stringResource(R.string.title_assistance_style), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val sharedPrefs = remember { getSharedPreferences("friday_settings", MODE_PRIVATE) }
                        var assistanceStyle by remember { mutableStateOf(sharedPrefs.getString("assistance_style", "Balanced") ?: "Balanced") }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("Quiet", "Balanced", "Proactive").forEach { style ->
                                Row(modifier = Modifier.fillMaxWidth().clickable { assistanceStyle = style; sharedPrefs.edit().putString("assistance_style", style).apply(); showToast("Assistance style set to $style") }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = assistanceStyle == style, onClick = { assistanceStyle = style; sharedPrefs.edit().putString("assistance_style", style).apply(); showToast("Assistance style set to $style") }, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.onSurface))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(style, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
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
                        Text(text = stringResource(R.string.title_danger_zone), fontSize = 32.sp, fontWeight = FontWeight.Black, color = if (isDarkThemeGlobal) Color(0xFFFECACA) else Color(0xFF7F1D1D))
                        Button(onClick = { showDisconnectDevicesConfirmDialog.value = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_disconnect_devices), color = Color(0xFFEF4444), fontWeight = FontWeight.Bold) }
                        Button(onClick = { showClearMemoriesConfirmDialog.value = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_clear_memories), color = Color(0xFFEF4444), fontWeight = FontWeight.Bold) }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(40.dp)) }
        }
    }

    @Composable
    fun ToggleRow(label: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) Text(text = subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primaryContainer, uncheckedThumbColor = MaterialTheme.colorScheme.outline, uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // PROACTIVE OVERLAY  (Voice Assistant — unchanged)
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
                val hasMicPermission = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasMicPermission) voiceAssistantManager.startRecording()
            }
            onDispose { }
        }

        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.98f)).clickable { }) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onClose, modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f), CircleShape)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onBackground)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when (assistantState) {
                        VoiceState.RECORDING -> ColorBlockLime
                        VoiceState.TRANSCRIBING -> ColorBlockLilac
                        VoiceState.RESPONDING -> ColorBlockMint
                        VoiceState.ERROR -> ColorBlockCoral
                        else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    }
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "STATUS: ${assistantState.name}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = when (assistantState) {
                        VoiceState.IDLE -> "Speak to FRIDAY"
                        VoiceState.RECORDING -> "FRIDAY is listening..."
                        VoiceState.TRANSCRIBING -> "FRIDAY is thinking..."
                        VoiceState.RESPONDING -> "FRIDAY's Response"
                        VoiceState.ERROR -> "Voice Error"
                    },
                    fontSize = 44.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W300, letterSpacing = (-0.96).sp, lineHeight = 48.sp, color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(40.dp))

                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val scale by infiniteTransition.animateFloat(initialValue = 1f, targetValue = 1.35f, animationSpec = infiniteRepeatable(animation = tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing), repeatMode = RepeatMode.Restart), label = "scale")
                val alpha by infiniteTransition.animateFloat(initialValue = 0.6f, targetValue = 0f, animationSpec = infiniteRepeatable(animation = tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing), repeatMode = RepeatMode.Restart), label = "alpha")

                Box(modifier = Modifier.align(Alignment.CenterHorizontally).size(120.dp), contentAlignment = Alignment.Center) {
                    if (assistantState == VoiceState.RECORDING) {
                        Box(modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale).clip(CircleShape).background(ColorBlockLime.copy(alpha = alpha)))
                    }
                    Box(
                        modifier = Modifier.size(80.dp).clip(CircleShape).background(if (assistantState == VoiceState.RECORDING) ColorBlockLime else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).clickable {
                            if (assistantState == VoiceState.IDLE || assistantState == VoiceState.RESPONDING || assistantState == VoiceState.ERROR) {
                                val hasMicPermission = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (hasMicPermission) voiceAssistantManager.startRecording()
                                else requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 102)
                            } else if (assistantState == VoiceState.RECORDING) {
                                voiceAssistantManager.stopRecordingAndTranscribe()
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Crossfade(targetState = assistantState, label = "mic_state_transition") { state ->
                            when (state) {
                                VoiceState.RECORDING -> PulsatingDotsCompose(color = if (isDarkThemeGlobal) Color(0xFFD6FF3D) else Color.Black)
                                VoiceState.TRANSCRIBING -> CircularProgressIndicator(color = if (isDarkThemeGlobal) Color(0xFFD6FF3D) else Color.Black, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                                VoiceState.RESPONDING -> RippleWaveLoaderCompose()
                                else -> Icon(imageVector = Icons.Default.Star, contentDescription = "Mic", tint = ColorBlockLime, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = when (assistantState) {
                        VoiceState.IDLE -> "Tap button to start recording."
                        VoiceState.RECORDING -> "Tap button again when done speaking."
                        VoiceState.TRANSCRIBING -> "Analyzing speech buffers via whisper.cpp..."
                        VoiceState.RESPONDING -> "Ready for next query."
                        VoiceState.ERROR -> "Tap to try again."
                    },
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(40.dp))

                when (assistantState) {
                    VoiceState.RESPONDING -> {
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockLilac), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(text = "YOU", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = "\"$transcriptText\"", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockLime), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(text = "FRIDAY AI", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = assistantResponse, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { voiceAssistantManager.cancel() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground), shape = RoundedCornerShape(9999.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text("Clear & Ask Something Else", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.background)
                        }
                    }
                    VoiceState.ERROR -> {
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockCoral), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(text = "ENGINE ERROR", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.7f))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = errorMessage, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { voiceAssistantManager.cancel() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground), shape = RoundedCornerShape(9999.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text("Reset", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.background)
                        }
                    }
                    else -> {
                        Text(text = "SUGGESTED COMMANDS", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OverlayActionButton(text = "Silence all alerts") { voiceAssistantManager.sendTextQuery("Silence all alerts") }
                            OverlayActionButton(text = "Prepare Evening Brief") { voiceAssistantManager.sendTextQuery("Prepare Evening Brief") }
                            OverlayActionButton(text = "Explain Current Stress") { voiceAssistantManager.sendTextQuery("Explain Current Stress") }
                            OverlayActionButton(text = "Start Focus Mode") { voiceAssistantManager.sendTextQuery("Start Focus Mode") }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    @Composable
    fun OverlayActionButton(text: String, onClick: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(9999.dp)).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(9999.dp)).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        }
    }

    @Composable
    private fun PulsatingDotsCompose(color: Color) {
        val transition = rememberInfiniteTransition(label = "dots")
        val dot1Scale by transition.animateFloat(initialValue = 0.5f, targetValue = 1.3f, animationSpec = infiniteRepeatable(animation = tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label = "dot1")
        val dot2Scale by transition.animateFloat(initialValue = 0.5f, targetValue = 1.3f, animationSpec = infiniteRepeatable(animation = tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing), repeatMode = RepeatMode.Reverse, initialStartOffset = androidx.compose.animation.core.StartOffset(200)), label = "dot2")
        val dot3Scale by transition.animateFloat(initialValue = 0.5f, targetValue = 1.3f, animationSpec = infiniteRepeatable(animation = tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing), repeatMode = RepeatMode.Reverse, initialStartOffset = androidx.compose.animation.core.StartOffset(400)), label = "dot3")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).graphicsLayer(scaleX = dot1Scale, scaleY = dot1Scale).background(color, CircleShape))
            Box(modifier = Modifier.size(8.dp).graphicsLayer(scaleX = dot2Scale, scaleY = dot2Scale).background(color, CircleShape))
            Box(modifier = Modifier.size(8.dp).graphicsLayer(scaleX = dot3Scale, scaleY = dot3Scale).background(color, CircleShape))
        }
    }

    @Composable
    private fun RippleWaveLoaderCompose() {
        val transition = rememberInfiniteTransition(label = "wave")
        val animVals = (0 until 7).map { index ->
            transition.animateFloat(initialValue = 0.4f, targetValue = 1.6f, animationSpec = infiniteRepeatable(animation = tween(500, easing = androidx.compose.animation.core.FastOutSlowInEasing), repeatMode = RepeatMode.Reverse, initialStartOffset = androidx.compose.animation.core.StartOffset(index * 80)), label = "wave_$index")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            animVals.forEachIndexed { index, animVal ->
                val barColor = if (isDarkThemeGlobal) { if (index % 2 == 0) Color(0xFFD6FF3D) else Color.White } else Color.Black
                Box(modifier = Modifier.width(3.dp).height(18.dp).graphicsLayer(scaleY = animVal.value).background(barColor, RoundedCornerShape(99.dp)))
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // THEME
    // ─────────────────────────────────────────────────────────────────────────────
    @Composable
    fun FridayTheme(content: @Composable () -> Unit) {
        val darkTheme = isSystemInDarkTheme()
        isDarkThemeGlobal = darkTheme
        val colorScheme = if (darkTheme) {
            darkColorScheme(primary = Color.White, background = Color(0xFF121212), surface = Color(0xFF1E1E1E), onBackground = Color(0xFFF1F1F1), onSurface = Color(0xFFF1F1F1), error = Color(0xFFEF4444), surfaceVariant = Color(0xFF2A2A2A), onSurfaceVariant = Color(0xFFB0B0B0))
        } else {
            lightColorScheme(primary = Color.Black, background = ColorBackground, surface = Color.White, onBackground = Color.Black, onSurface = Color.Black, error = Color(0xFFEF4444))
        }
        MaterialTheme(colorScheme = colorScheme, content = content)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // DIALOGS  (unchanged)
    // ─────────────────────────────────────────────────────────────────────────────
    @Composable
    private fun RenderSettingsDialogs() {
        if (showMemoriesDialog.value) {
            var newMemoryText by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showMemoriesDialog.value = false },
                title = { Text("FRIDAY Stored Memories", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("These are user behavioral models and preferences stored in FRIDAY's long-term memory graph.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = newMemoryText, onValueChange = { newMemoryText = it }, label = { Text("Learn new habit/info", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                            Button(onClick = { if (newMemoryText.isNotBlank()) { localMemories.add(newMemoryText.trim()); newMemoryText = ""; showToast("Memory stored successfully!") } }, shape = RoundedCornerShape(8.dp)) { Text("Add", fontSize = 11.sp) }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (localMemories.isEmpty()) {
                            Text("No memories stored yet.", fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(localMemories.size) { index ->
                                    val item = localMemories[index]
                                    Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = item, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { localMemories.removeAt(index); showToast("Memory deleted!") }) {
                                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showMemoriesDialog.value = false }) { Text("Close", fontWeight = FontWeight.Bold) } }
            )
        }

        if (showDevicesDialog.value) {
            var newDeviceName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showDevicesDialog.value = false },
                title = { Text("Configure Synced Devices", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Link a new device or manage existing sessions.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = newDeviceName, onValueChange = { newDeviceName = it }, label = { Text("Device Name (e.g. iPad Pro)", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                            Button(onClick = { if (newDeviceName.isNotBlank()) { localDevices.add(newDeviceName.trim() to "ACTIVE"); newDeviceName = ""; showToast("Device linked!") } }, shape = RoundedCornerShape(8.dp)) { Text("Link", fontSize = 11.sp) }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (localDevices.isEmpty()) {
                            Text("No devices linked.", fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(localDevices.size) { index ->
                                    val (device, status) = localDevices[index]
                                    Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(device, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                            Text(status, fontSize = 9.sp, color = Color.Green, fontWeight = FontWeight.Bold)
                                        }
                                        TextButton(onClick = { localDevices.removeAt(index); showToast("$device disconnected!") }) { Text("Disconnect", color = Color.Red, fontSize = 11.sp) }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showDevicesDialog.value = false }) { Text("Close", fontWeight = FontWeight.Bold) } }
            )
        }

        if (showClearMemoriesConfirmDialog.value) {
            AlertDialog(
                onDismissRequest = { showClearMemoriesConfirmDialog.value = false },
                title = { Text("Clear All Memories?", fontWeight = FontWeight.Bold) },
                text = { Text("This action cannot be undone. FRIDAY will forget all learned behaviors.") },
                confirmButton = { TextButton(onClick = { localMemories.clear(); showClearMemoriesConfirmDialog.value = false; showToast("All memories cleared!") }) { Text("Clear All", color = Color.Red, fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton(onClick = { showClearMemoriesConfirmDialog.value = false }) { Text("Cancel") } }
            )
        }

        if (showDisconnectDevicesConfirmDialog.value) {
            AlertDialog(
                onDismissRequest = { showDisconnectDevicesConfirmDialog.value = false },
                title = { Text("Disconnect All Devices?", fontWeight = FontWeight.Bold) },
                text = { Text("This will terminate active sync sessions on all linked devices.") },
                confirmButton = { TextButton(onClick = { localDevices.clear(); showDisconnectDevicesConfirmDialog.value = false; showToast("All devices disconnected!") }) { Text("Disconnect All", color = Color.Red, fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton(onClick = { showDisconnectDevicesConfirmDialog.value = false }) { Text("Cancel") } }
            )
        }

        if (showExplainStressDialog.value) {
            AlertDialog(
                onDismissRequest = { showExplainStressDialog.value = false },
                title = { Text("Stress Telemetry Analysis", fontWeight = FontWeight.Black) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("FRIDAY evaluates stress based on a three-tier sensor model:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("1. App Switching cadence: currently ${appSwitchesCount.value} switches. High switching indicates divided focus.", fontSize = 12.sp)
                        Text("2. Typing rhythm speed: currently ${if (averageTypingCadenceMs.value == 0L) "No input" else "${averageTypingCadenceMs.value}ms"}. Speed outliers indicate fatigue or panic states.", fontSize = 12.sp)
                        Text("3. Notification density: currently ${notificationsCount.value} interrupts. Higher alerts cause chronic cognitive load.", fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Current stress index is ${stressScore.value}%. This is classified as a ${
                            when {
                                stressScore.value >= 75 -> "Elevated Stress state. We recommend enabling Focus Mode to suppress notifications."
                                stressScore.value >= 50 -> "Moderate Stress state. Try reducing multitasking."
                                else -> "Optimal / Calm state. Good job keeping a steady focus!"
                            }
                        }", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                },
                confirmButton = { TextButton(onClick = { showExplainStressDialog.value = false }) { Text("Close", fontWeight = FontWeight.Bold) } }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────────
    private fun getMotivatingSubtitle(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Rise and shine! A fresh start to achieve your goals today."
            in 12..16 -> "Keep going! You're making great progress. Stay focused."
            in 17..21 -> "Great job today. Take a moment to appreciate your effort."
            else -> "Rest well. Sleep is the fuel for tomorrow's success."
        }
    }

    data class BriefItem(val title: String, val message: String, val category: String)

    @Composable
    fun NotificationBriefingCard(tasks: List<JSONObject>, stressScore: Int) {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val briefTitle = when (hour) {
            in 5..11 -> "Morning Brief"
            in 12..16 -> "Afternoon Brief"
            in 17..21 -> "Evening Brief"
            else -> "Night Brief"
        }
        val unreadText = if (tasks.isEmpty()) "No Alerts" else "${tasks.size} Unread"

        val briefItems = remember(tasks, stressScore) {
            val notifsOnly = tasks.filter { it.optString("type") == "notification" }
            if (notifsOnly.isNotEmpty()) {
                notifsOnly.map { task ->
                    val agent = task.optString("agent", "Orchestrator")
                    val message = task.optString("message", "")
                    val isDeadline = message.lowercase().contains("deadline") || message.lowercase().contains("thesis") || message.lowercase().contains("due")
                    val isWeather = message.lowercase().contains("weather") || message.lowercase().contains("rain")
                    BriefItem(title = if (isDeadline) "Academic Alert" else if (isWeather) "Weather update" else "Alert: $agent", message = message, category = if (isDeadline) "academic" else if (isWeather) "weather" else "general")
                }.take(2)
            } else {
                val hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                when (hourOfDay) {
                    in 5..11 -> listOf(BriefItem("Morning Focus Window", "System telemetry indicates optimal recovery. High-focus activity recommended.", "academic"), BriefItem("System Status", "Local background processing telemetry clean. Energy profile optimal.", "general"))
                    in 12..16 -> listOf(BriefItem(if (stressScore > 50) "Cognitive Rest Recommended" else "Mid-Day Performance", if (stressScore > 50) "High cognitive load detected. Consider taking a 5-minute offline break." else "Cognitive load is currently stable. Maintain your current work cadence.", if (stressScore > 50) "academic" else "general"), BriefItem("Environmental State", "Ambient lighting and noise levels normal. Perfect for productive tasks.", "weather"))
                    in 17..21 -> listOf(BriefItem("Evening Reflection", "You completed your core focus targets today. Clear cognitive peaks recorded.", "academic"), BriefItem("Security & Encryption", "Local telemetry storage synchronized. All personal logs encrypted locally.", "general"))
                    else -> listOf(BriefItem("Sleep Cycle Correction", "Recommended sleep window approaching. Screen blue light filters activated.", "weather"), BriefItem("Scheduled Maintenance", "Local machine learning inference optimizations running under sleep cycles.", "general"))
                }
            }
        }

        val footerQuote = when {
            stressScore > 75 -> "High stress detected. Recommending a short break."
            stressScore > 45 -> "Elevated cognitive load. Keeping notifications quiet."
            else -> "You seem rested today. Prioritizing academic tasks."
        }

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                            Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                        }
                        Text(text = briefTitle, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Box(modifier = Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(9999.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(text = unreadText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    briefItems.forEach { item ->
                        val iconColor: Color
                        val iconBg: Color
                        val icon: androidx.compose.ui.graphics.vector.ImageVector
                        if (item.category == "academic") { iconColor = Color(0xFFEF4444); iconBg = Color(0xFFFEE2E2); icon = Icons.Default.Warning }
                        else if (item.category == "weather") { iconColor = MaterialTheme.colorScheme.onSurfaceVariant; iconBg = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f); icon = Icons.Default.Info }
                        else { iconColor = if (isDarkThemeGlobal) Color(0xFFD6FF3D) else Color(0xFF2A3600); iconBg = iconColor.copy(alpha = 0.15f); icon = Icons.Default.Notifications }

                        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f), RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
                                Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = item.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(text = item.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    }
                }

                Text(text = "\"$footerQuote\"", fontSize = 13.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}