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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.friday.node.data.local.RoomDatabase
import com.friday.node.data.remote.WebSocketManager
import com.friday.node.service.FRIDAYForegroundService
import com.friday.node.utils.DiscoveryManager
import com.friday.node.utils.LocalFallbackEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val TAG = "FRIDAY_MainActivity"
    
    // Live UI State variables using Compose states
    private var connectionStatus = mutableStateOf("Searching for Hub...")
    private var isConnected = mutableStateOf(false)
    private var stressScore = mutableStateOf(52)
    private var appSwitchesCount = mutableStateOf(0)
    private var averageTypingCadenceMs = mutableStateOf(0L)
    private var notificationsCount = mutableStateOf(0)
    private var bufferedEventsCount = mutableStateOf(0)
    private var recentApp = mutableStateOf("None")
    private var lastNotification = mutableStateOf("No notifications yet")
    private var wellbeingPrompt = mutableStateOf("Ambient tracking active. System stable.")
    private var isGhostMode = mutableStateOf(false)
    
    // Manual Settings configs
    private var hubIp = mutableStateOf("")
    private var hubPort = mutableStateOf("8000")
    
    // Permission states
    private var isAccessibilityGranted = mutableStateOf(false)
    private var isNotificationAccessGranted = mutableStateOf(false)
    private var isPostNotificationGranted = mutableStateOf(false)

    // BroadcastReceiver to update Compose states in real-time
    private val telemetryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.friday.node.APP_SWITCH_DETECTED" -> {
                    val pkg = intent.getStringExtra("package_name") ?: "Unknown"
                    recentApp.value = pkg
                    appSwitchesCount.value += 1
                    recalculateLocalStress()
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
                    lastNotification.value = "$title: $content ($pkg)"
                    notificationsCount.value += 1
                    recalculateLocalStress()
                }
                "com.friday.node.BATTERY_MODE_CHANGED" -> {
                    val mode = intent.getStringExtra("current_mode") ?: "ACTIVE"
                    isGhostMode.value = (mode == "GHOST")
                }
                "com.friday.node.CONNECTION_STATE_CHANGED" -> {
                    val connected = intent.getBooleanExtra("is_connected", false)
                    isConnected.value = connected
                    connectionStatus.value = if (connected) {
                        "Connected to Hub"
                    } else {
                        "Hub Offline (Caching)"
                    }
                }
                "com.friday.node.ACTION_RECEIVED" -> {
                    val payload = intent.getStringExtra("action_payload") ?: ""
                    try {
                        val json = JSONObject(payload)
                        // Extract decision action or recommendation
                        val suggestion = json.optString("suggested_action")
                        if (!suggestion.isNullOrEmpty()) {
                            wellbeingPrompt.value = suggestion
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse Action payload: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. Initialize WebSocket context binding
        WebSocketManager.getInstance().init(this)

        // 2. Request POST_NOTIFICATIONS runtime permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        // 3. Kick off services and discovery scans
        try {
            val serviceIntent = Intent(this, FRIDAYForegroundService::class.java)
            startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch foreground service: ${e.message}")
        }

        // 4. Poll database buffer count reactively
        CoroutineScope(Dispatchers.Main).launch {
            val db = RoomDatabase.getInstance(this@MainActivity)
            while (true) {
                bufferedEventsCount.value = db.getEventCount()
                delay(1000)
            }
        }

        // 5. Draw the Compose user interface
        setContent {
            FridayTheme {
                MainContainer()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Register receiver for real-time sensing updates
        val filter = IntentFilter().apply {
            addAction("com.friday.node.APP_SWITCH_DETECTED")
            addAction("com.friday.node.TYPING_CADENCE_DETECTED")
            addAction("com.friday.node.NOTIFICATION_INTERCEPTED")
            addAction("com.friday.node.BATTERY_MODE_CHANGED")
            addAction("com.friday.node.CONNECTION_STATE_CHANGED")
            addAction("com.friday.node.ACTION_RECEIVED")
        }
        registerReceiver(telemetryReceiver, filter, RECEIVER_EXPORTED)
        
        // Check permission statuses
        checkPermissionsState()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(telemetryReceiver)
    }

    private fun checkPermissionsState() {
        isAccessibilityGranted.value = isAccessibilityServiceEnabled(
            this,
            com.friday.node.service.FRIDAYAccessibilityService::class.java
        )
        
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        isNotificationAccessGranted.value = enabledListeners != null && enabledListeners.contains(packageName)
        
        isPostNotificationGranted.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun recalculateLocalStress() {
        // Evaluate dynamic stress offline via LocalFallbackEngine
        val result = LocalFallbackEngine.evaluateOfflineContext(
            appSwitchesCount = appSwitchesCount.value,
            averageTypingCadenceMs = averageTypingCadenceMs.value,
            notificationsPerMin = notificationsCount.value
        )
        stressScore.value = result.stressScore
        if (!isConnected.value) {
            wellbeingPrompt.value = result.empatheticRecommendation
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
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    // Compose Core UI Layout
    @Composable
    fun MainContainer() {
        var activeTab by remember { mutableStateOf(0) }

        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color.White
                ) {
                    NavigationBarItem(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                        label = { Text("Dashboard", color = Color.White) }
                    )
                    NavigationBarItem(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        icon = { Icon(Icons.Default.Favorite, contentDescription = "Mind") },
                        label = { Text("Mind", color = Color.White) }
                    )
                    NavigationBarItem(
                        selected = activeTab == 2,
                        onClick = { activeTab = 2 },
                        icon = { Icon(Icons.Default.Refresh, contentDescription = "Continuity") },
                        label = { Text("Continuity", color = Color.White) }
                    )
                    NavigationBarItem(
                        selected = activeTab == 3,
                        onClick = { activeTab = 3 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings", color = Color.White) }
                    )
                }
            },
            containerColor = Color(0xFF0F172A),
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                when (activeTab) {
                    0 -> DashboardTab()
                    1 -> MindTab()
                    2 -> ContinuityTab()
                    3 -> SettingsTab()
                }
            }
        }
    }

    @Composable
    fun DashboardTab() {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header connection bar
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "FRIDAY AI Sensing Node",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 16.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (isConnected.value) Color(0xFF10B981) else Color(0xFFEF4444),
                                    RoundedCornerShape(4.dp)
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = connectionStatus.value,
                            color = if (isConnected.value) Color(0xFF10B981) else Color(0xFFEF4444),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Bento Grid Row 1: Stress Score circular gauge
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Gauge Card
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(180.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Cognitive Stress",
                                fontSize = 14.sp,
                                color = Color.LightGray
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Box(contentAlignment = Alignment.Center) {
                                val stressColor = when {
                                    stressScore.value > 70 -> Color(0xFFEF4444)
                                    stressScore.value > 45 -> Color(0xFFF59E0B)
                                    else -> Color(0xFF10B981)
                                }
                                CircularProgressIndicator(
                                    progress = stressScore.value / 100f,
                                    modifier = Modifier.size(80.dp),
                                    color = stressColor,
                                    strokeWidth = 8.dp,
                                    trackColor = Color(0xFF334155)
                                )
                                Text(
                                    text = "${stressScore.value}",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    // Ghost Mode / Battery status card
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(180.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Battery Optimization",
                                fontSize = 14.sp,
                                color = Color.LightGray
                            )
                            Column {
                                Text(
                                    text = if (isGhostMode.value) "Ghost Mode" else "Active Mode",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isGhostMode.value) Color(0xFFF59E0B) else Color(0xFF10B981)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isGhostMode.value) "Sensing throttled. Stripping texts." else "High fidelity telemetry streaming.",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                            Text(
                                text = "Local cache size: ${bufferedEventsCount.value}",
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Bento Grid Row 2: Contextual Prompt recommendation
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (stressScore.value > 70) Color(0xFFEF4444) else Color(0xFF334155),
                            RoundedCornerShape(12.dp)
                        ),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "FRIDAY Ambient Prompt",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF818CF8)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF312E81), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "LIVE",
                                    fontSize = 9.sp,
                                    color = Color(0xFF818CF8),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(
                            text = wellbeingPrompt.value,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { wellbeingPrompt.value = "Silencing notification interruptions for 25m." },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Silence Alerts", fontSize = 12.sp, color = Color.White)
                            }
                            Button(
                                onClick = { wellbeingPrompt.value = "Restoring laptop workflow: Chrome tab focus restored." },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Resume Focus", fontSize = 12.sp, color = Color.White)
                            }
                        }
                    }
                }
            }

            // Bento Grid Row 3: Live Telemetry
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Live Telemetry Senses",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.LightGray
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Focused App", fontSize = 11.sp, color = Color.Gray)
                                Text(
                                    recentApp.value.substringAfterLast("."),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Typing Cadence", fontSize = 11.sp, color = Color.Gray)
                                Text(
                                    if (averageTypingCadenceMs.value > 0) "${averageTypingCadenceMs.value} ms" else "--",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }
                        Divider(color = Color(0xFF334155))
                        Column {
                            Text("Last Intercepted Notification", fontSize = 11.sp, color = Color.Gray)
                            Text(
                                lastNotification.value,
                                fontSize = 13.sp,
                                color = Color.LightGray,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun MindTab() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Emotional & Fatigue Mindspace",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = Color.White
            )
            
            // Physiology baselines card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Samsung Health Connect Integration",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF34D399)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Mean Heart Rate", fontSize = 12.sp, color = Color.Gray)
                            Text("72 BPM", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Sleep Session", fontSize = 12.sp, color = Color.Gray)
                            Text("420 minutes", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    Divider(color = Color(0xFF334155))
                    Text(
                        text = "Physiological indexes are currently within nominal zones. Baseline rest HR indicates low physical anxiety.",
                        fontSize = 13.sp,
                        color = Color.LightGray
                    )
                }
            }

            // Burnout risk indicator card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Burnout Risk Assessment",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF818CF8)
                    )
                    
                    val riskLevel = when {
                        stressScore.value > 70 -> "HIGH RISK"
                        stressScore.value > 45 -> "MODERATE RISK"
                        else -> "LOW RISK"
                    }
                    val riskColor = when (riskLevel) {
                        "HIGH RISK" -> Color(0xFFEF4444)
                        "MODERATE RISK" -> Color(0xFFF59E0B)
                        else -> Color(0xFF10B981)
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SWELL-KW Index Status:", fontSize = 13.sp, color = Color.LightGray)
                        Box(
                            modifier = Modifier
                                .background(riskColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .border(1.dp, riskColor, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = riskLevel,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = riskColor
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Calculated using typing pace delays and cognitive switching frequencies over active working intervals.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }
        }
    }

    @Composable
    fun ContinuityTab() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Cross-Device Continuity",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = Color.White
            )
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Synchronized Laptop Tasks",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF60A5FA)
                    )
                    Text(
                        text = "Connected device: Galaxy Book Pro",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Divider(color = Color(0xFF334155))
                    
                    // Task queue list items
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F172A), RoundedCornerShape(6.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Research Paper (Google Docs)", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                Text("Last edited 4 mins ago on Laptop", fontSize = 11.sp, color = Color.Gray)
                            }
                            Button(
                                onClick = { wellbeingPrompt.value = "Resuming Research Paper on Laptop..." },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Restore", fontSize = 10.sp, color = Color.White)
                            }
                        }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F172A), RoundedCornerShape(6.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Samsung Hackathon Guidelines", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                Text("Browser Tab (Chrome)", fontSize = 11.sp, color = Color.Gray)
                            }
                            Button(
                                onClick = { wellbeingPrompt.value = "Opening Hackathon Rules on Laptop..." },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Restore", fontSize = 10.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun SettingsTab() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Node System Settings",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = Color.White
            )

            // Manual Connection override card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Manual Hub Override IP Configuration",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = hubIp.value,
                            onValueChange = { hubIp.value = it },
                            label = { Text("Hub IP Address", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF6366F1),
                                unfocusedBorderColor = Color(0xFF475569)
                            ),
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = hubPort.value,
                            onValueChange = { hubPort.value = it },
                            label = { Text("Port", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF6366F1),
                                unfocusedBorderColor = Color(0xFF475569)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    Button(
                        onClick = {
                            if (hubIp.value.isNotEmpty()) {
                                val url = "ws://${hubIp.value}:${hubPort.value}/ws/android"
                                WebSocketManager.getInstance().connect(url)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Connect Manually", color = Color.White)
                    }
                }
            }

            // Android System Permissions setup toggles
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Android Senses System Access",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    // Permission row 1: Accessibility
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Accessibility Service", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                            Text("For App switched context", fontSize = 11.sp, color = Color.Gray)
                        }
                        Button(
                            onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAccessibilityGranted.value) Color(0xFF10B981) else Color(0xFFEF4444)
                            ),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(if (isAccessibilityGranted.value) "Granted" else "Grant", fontSize = 11.sp, color = Color.White)
                        }
                    }

                    // Permission row 2: Notification listener
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Notification Listener", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                            Text("Intercept notifications", fontSize = 11.sp, color = Color.Gray)
                        }
                        Button(
                            onClick = { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isNotificationAccessGranted.value) Color(0xFF10B981) else Color(0xFFEF4444)
                            ),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(if (isNotificationAccessGranted.value) "Granted" else "Grant", fontSize = 11.sp, color = Color.White)
                        }
                    }

                    // Permission row 3: Notification channel permissions
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Post Notifications", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                Text("Foreground alert channel", fontSize = 11.sp, color = Color.Gray)
                            }
                            Button(
                                onClick = {
                                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPostNotificationGranted.value) Color(0xFF10B981) else Color(0xFFEF4444)
                                ),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(if (isPostNotificationGranted.value) "Granted" else "Grant", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    // Custom Glassmorphic Dark Theme definition
    @Composable
    fun FridayTheme(content: @Composable () -> Unit) {
        val colorScheme = darkColorScheme(
            primary = Color(0xFF6366F1),
            background = Color(0xFF0F172A),
            surface = Color(0xFF1E293B),
            onBackground = Color(0xFFF8FAFC),
            onSurface = Color(0xFFF1F5F9),
            error = Color(0xFFEF4444)
        )
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
