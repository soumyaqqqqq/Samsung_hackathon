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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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

    // Color tokens matching the Figma Marketing Design System
    val ColorBlockLime = Color(0xFFD6FF3D)
    val ColorBlockLilac = Color(0xFFC6BFFF)
    val ColorBlockCream = Color(0xFFFFF9E3)
    val ColorBlockMint = Color(0xFFB2FFD6)
    val ColorBlockPink = Color(0xFFFFB8EB)
    val ColorBlockCoral = Color(0xFFFF8C70)
    val ColorBlockNavy = Color(0xFF10162F)
    val ColorBackground = Color(0xFFF9F9F9)

    // Compose Core UI Layout
    @Composable
    fun MainContainer() {
        var activeTab by remember { mutableStateOf(0) }
        var showProactiveOverlay by remember { mutableStateOf(false) }

        Scaffold(
            bottomBar = {
                CustomBottomNavBar(
                    activeTab = activeTab,
                    onTabSelected = { tab ->
                        activeTab = tab
                        showProactiveOverlay = false
                    },
                    onSparkClicked = {
                        showProactiveOverlay = !showProactiveOverlay
                    }
                )
            },
            containerColor = ColorBackground,
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

                if (showProactiveOverlay) {
                    ProactiveOverlayScreen(onClose = { showProactiveOverlay = false })
                }
            }
        }
    }

    @Composable
    fun CustomBottomNavBar(
        activeTab: Int,
        onTabSelected: (Int) -> Unit,
        onSparkClicked: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .border(width = 1.dp, color = Color(0x0D000000))
                .padding(bottom = 8.dp, top = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Home
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onTabSelected(0) }
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "Home",
                        tint = if (activeTab == 0) Color.Black else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "HOME",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (activeTab == 0) Color.Black else Color.Gray
                    )
                }

                // Insights
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onTabSelected(1) }
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Insights",
                        tint = if (activeTab == 1) Color.Black else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "INSIGHTS",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (activeTab == 1) Color.Black else Color.Gray
                    )
                }

                // Spacer for the center spark button
                Spacer(modifier = Modifier.size(48.dp))

                // Timeline
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onTabSelected(2) }
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Timeline",
                        tint = if (activeTab == 2) Color.Black else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "TIMELINE",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (activeTab == 2) Color.Black else Color.Gray
                    )
                }

                // Settings
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onTabSelected(3) }
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = if (activeTab == 3) Color.Black else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "SETTINGS",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (activeTab == 3) Color.Black else Color.Gray
                    )
                }
            }

            // Central Spark FAB Button (raised offset)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-24).dp)
            ) {
                Button(
                    onClick = onSparkClicked,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .size(56.dp)
                        .border(2.dp, ColorBlockLime, RoundedCornerShape(16.dp)),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = "✦",
                        fontSize = 24.sp,
                        color = ColorBlockLime,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    @Composable
    fun DashboardTab() {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(ColorBackground)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header Top Bar
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "FRIDAY",
                        fontSize = 26.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Black,
                        color = Color.Black
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE2E2E2)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "N",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }
            }

            // Connection banner
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0x0D000000), RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "NODE CONNECTION",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.Gray,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = connectionStatus.value,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
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

            // Greeting Section
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Good Evening Nirvik",
                        fontSize = 40.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.W300,
                        letterSpacing = (-0.96).sp,
                        lineHeight = 44.sp,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = wellbeingPrompt.value,
                        fontSize = 18.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.W300,
                        color = Color(0xFF4C4546)
                    )
                }
            }

            // Cognitive Load Circular Progress Gauge Card
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
                            text = "COGNITIVE LOAD",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black.copy(alpha = 0.6f)
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
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Normal,
                                    color = Color.Black
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = if (isGhostMode.value) "Ghost mode active. Telemetry simplified." else "High-fidelity telemetry streaming.",
                                    fontSize = 12.sp,
                                    color = Color.Black.copy(alpha = 0.6f)
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(100.dp)
                            ) {
                                CircularProgressIndicator(
                                    progress = { stressScore.value / 100f },
                                    modifier = Modifier.fillMaxSize(),
                                    color = Color.Black,
                                    strokeWidth = 10.dp,
                                    trackColor = Color.Black.copy(alpha = 0.1f)
                                )
                                Text(
                                    text = "${stressScore.value}%",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                }
            }

            // Priority Panel: Editorial List
            item {
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
                            text = "WHAT NEEDS ATTENTION",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black.copy(alpha = 0.6f)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "01",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black.copy(alpha = 0.4f)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Database Schema Final",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                }
                                Button(
                                    onClick = {},
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                                    shape = RoundedCornerShape(9999.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Action", fontSize = 11.sp, color = Color.White)
                                }
                            }

                            HorizontalDivider(color = Color.Black.copy(alpha = 0.05f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "02",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black.copy(alpha = 0.4f)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Professor Smith Meeting",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                }
                                Button(
                                    onClick = {},
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                                    shape = RoundedCornerShape(9999.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Action", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = {},
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            shape = RoundedCornerShape(9999.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color.Black, RoundedCornerShape(9999.dp))
                        ) {
                            Text("Prioritize Automatically", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Summary Cards Horizontal Scroll
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = ColorBlockLilac),
                            modifier = Modifier
                                .width(180.dp)
                                .height(140.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "MESSAGES",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black.copy(alpha = 0.6f)
                                )
                                Column {
                                    Text(
                                        text = "${notificationsCount.value}",
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.Black
                                    )
                                    Text(
                                        text = "TOTAL INTERCEPTED",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.Black.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFEEEEEE)),
                            modifier = Modifier
                                .width(180.dp)
                                .height(140.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "FOCUS TIME",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black.copy(alpha = 0.6f)
                                )
                                Column {
                                    Text(
                                        text = "4h 32m",
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.Black
                                    )
                                    Text(
                                        text = "+12% VS AVERAGE",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.Black.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = ColorBlockPink),
                            modifier = Modifier
                                .width(180.dp)
                                .height(140.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "CONTEXT",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black.copy(alpha = 0.6f)
                                )
                                Column {
                                    Text(
                                        text = "Exam Week",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.Black
                                    )
                                    Text(
                                        text = "Match: May 2023",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.Black.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Memory Moment Card
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFEEEEEE)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "MEMORY MOMENT",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "VIT Library",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Text(
                            text = "You typically activate 'Deep Focus' mode here. Repeat setup?",
                            fontSize = 14.sp,
                            color = Color.Black.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {},
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                                shape = RoundedCornerShape(9999.dp)
                            ) {
                                Text("Repeat Setup", color = Color.White)
                            }
                            Button(
                                onClick = {},
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                shape = RoundedCornerShape(9999.dp),
                                modifier = Modifier.border(1.dp, Color.Black, RoundedCornerShape(9999.dp))
                            ) {
                                Text("Dismiss", color = Color.Black)
                            }
                        }
                    }
                }
            }

            // Activity Feed (Bento style / Block Navy)
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = ColorBlockNavy),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "SYSTEM LOGS & ACTIVITY",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.6f)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            // Row 1: Focused App
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    text = "✦",
                                    color = ColorBlockLime,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Active App Switch",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Focused application: ${recentApp.value}",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            // Row 2: Last Notification
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    text = "✦",
                                    color = ColorBlockLime,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Notification Intercepted",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = lastNotification.value,
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Row 3: Event Count
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    text = "✦",
                                    color = ColorBlockLime,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Local Offline Cache Buffer",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Buffered events count: ${bufferedEventsCount.value}",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom breathing room
            item {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    @Composable
    fun BiometricsRadarChart(
        stress: Float, // 0 to 100
        burnout: Float, // 0 to 100
        fatigue: Float, // 0 to 100
        social: Float // 0 to 100
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(200.dp)) {
                val center = size.width / 2
                val maxRadius = size.width / 2 * 0.8f

                // Draw background grids (concentric squares)
                val gridSteps = 4
                for (i in 1..gridSteps) {
                    val r = maxRadius * (i.toFloat() / gridSteps)
                    drawRect(
                        color = Color.White.copy(alpha = 0.15f),
                        topLeft = androidx.compose.ui.geometry.Offset(center - r, center - r),
                        size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                // Draw axis lines
                drawLine(
                    color = Color.White.copy(alpha = 0.2f),
                    start = androidx.compose.ui.geometry.Offset(center, center - maxRadius),
                    end = androidx.compose.ui.geometry.Offset(center, center + maxRadius),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.2f),
                    start = androidx.compose.ui.geometry.Offset(center - maxRadius, center),
                    end = androidx.compose.ui.geometry.Offset(center + maxRadius, center),
                    strokeWidth = 1.dp.toPx()
                )

                // Calculate polygon vertices
                val pStressX = center
                val pStressY = center - maxRadius * (stress / 100f)

                val pSocialX = center + maxRadius * (social / 100f)
                val pSocialY = center

                val pBurnoutX = center
                val pBurnoutY = center + maxRadius * (burnout / 100f)

                val pFatigueX = center - maxRadius * (fatigue / 100f)
                val pFatigueY = center

                val path = Path().apply {
                    moveTo(pStressX, pStressY)
                    lineTo(pSocialX, pSocialY)
                    lineTo(pBurnoutX, pBurnoutY)
                    lineTo(pFatigueX, pFatigueY)
                    close()
                }

                drawPath(
                    path = path,
                    color = ColorBlockLime.copy(alpha = 0.4f)
                )

                drawPath(
                    path = path,
                    color = ColorBlockLime,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "STRESS (${stress.toInt()})",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                Text(
                    text = "BURNOUT (${burnout.toInt()})",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
                Text(
                    text = "FATIGUE (${fatigue.toInt()})",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp)
                )
                Text(
                    text = "SOCIAL (${social.toInt()})",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp)
                )
            }
        }
    }

    @Composable
    fun MindTab() {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(ColorBackground)
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
                            text = "CURRENT STATUS",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "MINDSPACE",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Calm but busy",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color.Black
                        )
                    }
                }
            }

            // Empathetic Insight
            item {
                Text(
                    text = "You're handling more work than usual today, but your focus levels remain strong. The biggest risk is mental fatigue later this evening.",
                    fontSize = 18.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.W300,
                    color = Color(0xFF4C4546)
                )
            }

            // Radar Chart Section (navy background)
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
                            text = "COGNITIVE RISK RADAR",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.Start)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        BiometricsRadarChart(
                            stress = stressScore.value.toFloat(),
                            burnout = 28f,
                            fatigue = 47f,
                            social = 53f
                        )
                    }
                }
            }

            // Current State grid (5 columns)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "CURRENT STATE",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    HorizontalDivider(color = Color.Black.copy(alpha = 0.05f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        BiometricMetricItem(label = "Stress", value = "${stressScore.value}%", subtitle = "Elevated")
                        BiometricMetricItem(label = "Focus", value = "84%", subtitle = "High Perf")
                        BiometricMetricItem(label = "Cadence", value = "${averageTypingCadenceMs.value}ms", subtitle = "Typing Speed")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        BiometricMetricItem(label = "Confidence", value = "73%", subtitle = "Stable")
                        BiometricMetricItem(label = "Social Load", value = "31%", subtitle = "Introvert")
                    }
                }
            }

            // Agent Grid (Pastel Color Blocks)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Emotion Agent (Mint)
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = ColorBlockMint),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🧠", fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "EMOTION AGENT",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black.copy(alpha = 0.6f)
                                )
                            }
                            Text(
                                text = "Focused & Confident",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Text(
                                text = "Stable typing rhythms and low application switching detected.",
                                fontSize = 14.sp,
                                color = Color.Black.copy(alpha = 0.8f)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("87%", fontSize = 24.sp, fontWeight = FontWeight.Black)
                                Text(
                                    text = "CONFIDENCE SCORE",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.Black.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }

                    // Burnout Agent (Pink)
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = ColorBlockPink),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🔋", fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "BURNOUT AGENT",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black.copy(alpha = 0.6f)
                                )
                            }
                            Text(
                                text = "Risk: Low",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Text(
                                text = "Normal sleep cycles, consistent output, and language neutrality.",
                                fontSize = 14.sp,
                                color = Color.Black.copy(alpha = 0.8f)
                            )
                        }
                    }

                    // Social Agent (Cream)
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = ColorBlockCream),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("💬", fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "SOCIAL AGENT",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black.copy(alpha = 0.6f)
                                )
                            }
                            Text(
                                text = "Risk: Moderate",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Text(
                                text = "High messaging volume and impending team project deadlines.",
                                fontSize = 14.sp,
                                color = Color.Black.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            // Why? (Signals) section
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "WHY? (SIGNALS)",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SignalRowItem(label = "App Switching", value = "${appSwitchesCount.value} events")
                        SignalRowItem(label = "Assignment Due", value = "Tomorrow")
                        SignalRowItem(label = "Sleep Duration", value = "-14% Avg", isAlert = true)
                        SignalRowItem(label = "Focus Work", value = "4.2 hours")
                    }
                }
            }

            // Behavioral Anomaly & Memory Correlation
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.Black, RoundedCornerShape(24.dp))
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "BEHAVIORAL ANOMALY",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Pattern Analysis: 12% deviation from standard Thursday baseline.",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEEEEEE)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "MEMORY CORRELATION",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Similar Past Events: 87% Match to Mid-Semester Week (March 2026).",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Outcome: High stress, but assignment successfully completed.",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }

            // Actions Buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {},
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                        shape = RoundedCornerShape(9999.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Start Focus", color = Color.White, fontSize = 11.sp)
                    }
                    Button(
                        onClick = {},
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(9999.dp),
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, Color.Black, RoundedCornerShape(9999.dp))
                    ) {
                        Text("Reduce Interrupt", color = Color.Black, fontSize = 11.sp)
                    }
                    Button(
                        onClick = {},
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(9999.dp),
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, Color.Black, RoundedCornerShape(9999.dp))
                    ) {
                        Text("Explain Stress", color = Color.Black, fontSize = 11.sp)
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    @Composable
    fun BiometricMetricItem(label: String, value: String, subtitle: String) {
        Column(modifier = Modifier.width(90.dp)) {
            Text(
                text = label.uppercase(),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.Gray,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = Color.Black
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = Color.Black
            )
        }
    }

    @Composable
    fun SignalRowItem(label: String, value: String, isAlert: Boolean = false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, fontSize = 14.sp, color = Color.Black)
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (isAlert) Color(0xFFEF4444) else Color.Black
            )
        }
        HorizontalDivider(color = Color.Black.copy(alpha = 0.05f))
    }

    @Composable
    fun ProductivityIntensityVisualizer() {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = ColorBlockLime),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "ACTIVITY INTENSITY",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    val heights = listOf(0.2f, 0.4f, 0.7f, 0.9f, 0.6f, 0.3f, 0.1f, 0.55f, 0.85f, 0.45f, 0.25f, 0.75f, 0.35f, 0.65f, 0.95f, 0.5f)
                    heights.forEach { h ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(h)
                                .background(Color.Black, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun ContinuityTab() {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(ColorBackground)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "LIFE TIMELINE",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.Gray,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider(color = Color.Black.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "Wednesday,\nJune 4, 2026",
                    fontSize = 40.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.W300,
                    letterSpacing = (-0.96).sp,
                    lineHeight = 44.sp,
                    color = Color.Black
                )
            }

            // Key Metrics
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("PRODUCTIVITY", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Text("87%", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.Black)
                    }
                    Column {
                        Text("PEAK FOCUS", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Text("9:15 PM", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                    Column {
                        Text("STRESS SPIKE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Text("7:10 PM", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }

            // Visualizer
            item {
                ProductivityIntensityVisualizer()
            }

            // Today's Story (Editorial layout)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "TODAY'S STORY",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "The day began with a sharp trajectory toward deep cognitive work. Between 9:00 AM and 12:30 PM, you maintained an unbroken flow state on the Database Assignment.",
                        fontSize = 20.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.W300,
                        color = Color.Black,
                        lineHeight = 28.sp
                    )
                    Text(
                        text = "The afternoon dipped during the 3:00 PM sync, where minor stress levels were detected. Evening brought a resurgence of energy, peaking at 9:15 PM, where your problem-solving efficiency was 24% higher than the monthly average.",
                        fontSize = 16.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.W300,
                        color = Color.Gray,
                        lineHeight = 24.sp
                    )
                }
            }

            // Device Continuity
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "DEVICE CONTINUITY",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DeviceContinuityRow(icon = "📱", name = "Galaxy Phone", active = "Active: 4h 12m")
                        DeviceContinuityRow(icon = "💻", name = "Work Laptop", active = "Active: 8h 45m")
                    }
                }
            }

            // Task Journey
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "TASK JOURNEY",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold
                    )

                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0x0D000000), RoundedCornerShape(24.dp))
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(
                                text = "Database Assignment",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            TaskJourneyStep(title = "Research & Scoping", status = "Completed", isDone = true)
                            TaskJourneyStep(title = "Schema Design", status = "Completed", isDone = true)
                            TaskJourneyStep(title = "API Integration", status = "IN PROGRESS", isCurrent = true)
                        }
                    }
                }
            }

            // Friday Actions (Bento list)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "FRIDAY ACTIONS",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            modifier = Modifier
                                .weight(1f)
                                .height(160.dp)
                                .border(1.dp, Color(0x0D000000), RoundedCornerShape(24.dp))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("🔕", fontSize = 20.sp)
                                Column {
                                    Text("Filtered", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                    Text("34 suppressed distractions.", fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                        }

                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            modifier = Modifier
                                .weight(1f)
                                .height(160.dp)
                                .border(1.dp, Color(0x0D000000), RoundedCornerShape(24.dp))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("✦", fontSize = 20.sp, color = Color.Black)
                                Column {
                                    Text("Focus Rec", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                    Text("Optimal window at 9:00 PM.", fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("⚠️", fontSize = 20.sp)
                            Text("Fatigue Predicted", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF991B1B))
                            Text("Sleep cycle correction needed. Rest suggested before 11:30 PM.", fontSize = 13.sp, color = Color(0xFF7F1D1D))
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    @Composable
    fun DeviceContinuityRow(icon: String, name: String, active: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(12.dp))
                .border(1.dp, Color(0x0D000000), RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(ColorBackground, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = icon, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Text(text = active, fontSize = 12.sp, color = Color.Gray)
            }
        }
    }

    @Composable
    fun TaskJourneyStep(title: String, status: String, isDone: Boolean = false, isCurrent: Boolean = false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isDone -> Color.Black
                            isCurrent -> ColorBlockLime
                            else -> Color.LightGray
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isDone) {
                    Text("✓", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = Color.Black
                )
                Text(
                    text = status,
                    fontSize = 11.sp,
                    color = if (isCurrent) Color.Black else Color.Gray,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }

    @Composable
    fun SettingsTab() {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(ColorBackground)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Editorial Header
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "TRUST CENTER",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.Gray,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider(color = Color.Black.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "Settings",
                    fontSize = 40.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.96).sp,
                    color = Color.Black
                )
                Text(
                    text = "Manage privacy, devices and personalization.",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }

            // Profile
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(24.dp))
                        .border(1.dp, Color(0x0D000000), RoundedCornerShape(24.dp))
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "N",
                            color = Color.White,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Nirvik Goswami",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Text(
                            text = "Student Mode • Galaxy Ecosystem",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            }

            // Privacy & Data Block (Lime)
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = ColorBlockLime),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "PRIVACY & DATA",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.Black
                        )

                        var dataProcessing by remember { mutableStateOf(true) }
                        var cloudBackup by remember { mutableStateOf(true) }
                        var dataEncryption by remember { mutableStateOf(true) }

                        ToggleRow(label = "Data Processing", subtitle = "Mostly On Device", checked = dataProcessing, onCheckedChange = { dataProcessing = it })
                        ToggleRow(label = "Cloud Backup", checked = cloudBackup, onCheckedChange = { cloudBackup = it })
                        ToggleRow(label = "Data Encryption", checked = dataEncryption, onCheckedChange = { dataEncryption = it })

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {},
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Manage Permissions", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Android Senses System Access & Permissions
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0x0D000000), RoundedCornerShape(24.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "SYSTEM PERMISSIONS",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )

                        // Accessibility
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Accessibility Service", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                Text("For active application context switches", fontSize = 11.sp, color = Color.Gray)
                            }
                            Button(
                                onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isAccessibilityGranted.value) Color(0xFF10B981) else Color(0xFFEF4444)
                                ),
                                shape = RoundedCornerShape(9999.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(if (isAccessibilityGranted.value) "Granted" else "Grant", fontSize = 11.sp, color = Color.White)
                            }
                        }

                        HorizontalDivider(color = Color.Black.copy(alpha = 0.05f))

                        // Notification Listener
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Notification Listener", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                Text("For intercepting notifications", fontSize = 11.sp, color = Color.Gray)
                            }
                            Button(
                                onClick = { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isNotificationAccessGranted.value) Color(0xFF10B981) else Color(0xFFEF4444)
                                ),
                                shape = RoundedCornerShape(9999.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(if (isNotificationAccessGranted.value) "Granted" else "Grant", fontSize = 11.sp, color = Color.White)
                            }
                        }

                        // Post Notifications (Android 13+)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            HorizontalDivider(color = Color.Black.copy(alpha = 0.05f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Post Notifications", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                    Text("Foreground sensing alert channel", fontSize = 11.sp, color = Color.Gray)
                                }
                                Button(
                                    onClick = {
                                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isPostNotificationGranted.value) Color(0xFF10B981) else Color(0xFFEF4444)
                                    ),
                                    shape = RoundedCornerShape(9999.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                ) {
                                    Text(if (isPostNotificationGranted.value) "Granted" else "Grant", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            // Memory Block (Lilac)
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = ColorBlockLilac),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "MEMORY",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.Black
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("146", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.Black)
                                Text("STORED", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color.Black.copy(alpha = 0.6f))
                            }
                            Column {
                                Text("24", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.Black)
                                Text("EVENTS", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color.Black.copy(alpha = 0.6f))
                            }
                            Column {
                                Text("43 MB", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.Black)
                                Text("SIZE", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color.Black.copy(alpha = 0.6f))
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {},
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("View Memories", color = Color.White)
                            }
                            Button(
                                onClick = {},
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Forget Specific Memory", color = Color.Black)
                            }
                        }
                    }
                }
            }

            // Synced Devices Block (Navy)
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = ColorBlockNavy),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column {
                            Text(
                                text = "DEVICES",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                            Text(
                                text = "Synced 2m ago",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White.copy(alpha = 0.4f),
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("📱", fontSize = 18.sp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Galaxy S26", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Box(
                                    modifier = Modifier
                                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("ACTIVE", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }

                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("💻", fontSize = 18.sp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Work Laptop", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Box(
                                    modifier = Modifier
                                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("ACTIVE", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Button(
                            onClick = {},
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Manage Devices", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Manual Connection Override Configuration
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0x0D000000), RoundedCornerShape(24.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "HUB OVERRIDE IP CONFIG",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = hubIp.value,
                                onValueChange = { hubIp.value = it },
                                label = { Text("Hub IP Address", color = Color.Gray, fontSize = 12.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.Black,
                                    unfocusedTextColor = Color.Black,
                                    focusedBorderColor = Color.Black,
                                    unfocusedBorderColor = Color.LightGray
                                ),
                                modifier = Modifier.weight(2f)
                            )
                            OutlinedTextField(
                                value = hubPort.value,
                                onValueChange = { hubPort.value = it },
                                label = { Text("Port", color = Color.Gray, fontSize = 12.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.Black,
                                    unfocusedTextColor = Color.Black,
                                    focusedBorderColor = Color.Black,
                                    unfocusedBorderColor = Color.LightGray
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
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Connect Manually", color = Color.White)
                        }
                    }
                }
            }

            // Assistance (Quiet, Balanced, Proactive selection)
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0x0D000000), RoundedCornerShape(24.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "ASSISTANCE STYLE",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )

                        var assistanceStyle by remember { mutableStateOf("Balanced") }

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { assistanceStyle = "Quiet" },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = assistanceStyle == "Quiet",
                                    onClick = { assistanceStyle = "Quiet" },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color.Black)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Quiet", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { assistanceStyle = "Balanced" },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = assistanceStyle == "Balanced",
                                    onClick = { assistanceStyle = "Balanced" },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color.Black)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Balanced", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { assistanceStyle = "Proactive" },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = assistanceStyle == "Proactive",
                                    onClick = { assistanceStyle = "Proactive" },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color.Black)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Proactive", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                        }
                    }
                }
            }

            // Danger Zone Block
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Danger Zone",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF7F1D1D)
                        )

                        Button(
                            onClick = {},
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Disconnect Devices", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {},
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Clear Memories", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    @Composable
    fun ToggleRow(
        label: String,
        subtitle: String? = null,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                if (subtitle != null) {
                    Text(text = subtitle, fontSize = 11.sp, color = Color.Black.copy(alpha = 0.6f))
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color.Black,
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color.LightGray
                )
            )
        }
    }

    @Composable
    fun ProactiveOverlayScreen(onClose: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF9F9F9).copy(alpha = 0.96f))
                .clickable { /* Block clicks */ }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                // Top close button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color.Black.copy(alpha = 0.05f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.Black
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Listening header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(ColorBlockLime)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "STATUS: ACTIVE LISTENING",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "FRIDAY is listening...",
                    fontSize = 44.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.W300,
                    letterSpacing = (-0.96).sp,
                    lineHeight = 48.sp,
                    color = Color.Black
                )

                Spacer(modifier = Modifier.height(40.dp))

                // Bento Grid
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = ColorBlockLilac),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = "COGNITIVE PULSE",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "84%",
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Focus",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black.copy(alpha = 0.6f),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Your flow state is peak right now. I've silenced all non-critical nodes and optimized your workspace luminance for deep focus.",
                            fontSize = 15.sp,
                            color = Color.Black
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = ColorBlockNavy),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .border(2.dp, ColorBlockLime, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🧠", fontSize = 24.sp)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "BRAIN SYNC",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = ColorBlockLime
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Delta waves detected. Deep integration active.",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Action suggestions
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OverlayActionButton(text = "Silence Everything", icon = "🔕")
                    OverlayActionButton(text = "Prepare Evening Brief", icon = "✦")
                    OverlayActionButton(text = "Check Node Connectivity", icon = "🔗")
                    OverlayActionButton(text = "Explain Current Stress", icon = "📊")
                }

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    @Composable
    fun OverlayActionButton(text: String, icon: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(9999.dp))
                .border(1.dp, Color(0x0D000000), RoundedCornerShape(9999.dp))
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Text(text = icon, fontSize = 18.sp)
        }
    }

    // Custom Editorial Light/Navy theme definition
    @Composable
    fun FridayTheme(content: @Composable () -> Unit) {
        val colorScheme = lightColorScheme(
            primary = Color.Black,
            background = ColorBackground,
            surface = Color.White,
            onBackground = Color.Black,
            onSurface = Color.Black,
            error = Color(0xFFEF4444)
        )
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}

