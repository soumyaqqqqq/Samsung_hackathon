package com.friday.node

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.friday.node.data.remote.VoiceState
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// COLOR TOKENS & THEME
// ─────────────────────────────────────────────────────────────────────────────
@Immutable
data class FridayColorTokens(
    val lime: Color,
    val lilac: Color,
    val cream: Color,
    val mint: Color,
    val pink: Color,
    val coral: Color,
    val navy: Color,
    val background: Color,
    val isDark: Boolean
)

val LocalFridayColors = staticCompositionLocalOf {
    FridayColorTokens(
        lime = Color(0xFFD6FF3D),
        lilac = Color(0xFFC6BFFF),
        cream = Color(0xFFFFF9E3),
        mint = Color(0xFFB2FFD6),
        pink = Color(0xFFFFB8EB),
        coral = Color(0xFFFF8C70),
        navy = Color(0xFF10162F),
        background = Color(0xFFF9F9F9),
        isDark = false
    )
}

val ColorBlockLime: Color @Composable get() = LocalFridayColors.current.lime
val ColorBlockLilac: Color @Composable get() = LocalFridayColors.current.lilac
val ColorBlockCream: Color @Composable get() = LocalFridayColors.current.cream
val ColorBlockMint: Color @Composable get() = LocalFridayColors.current.mint
val ColorBlockPink: Color @Composable get() = LocalFridayColors.current.pink
val ColorBlockCoral: Color @Composable get() = LocalFridayColors.current.coral
val ColorBlockNavy: Color @Composable get() = LocalFridayColors.current.navy
val ColorBackground: Color @Composable get() = LocalFridayColors.current.background
val isDarkThemeGlobal: Boolean @Composable get() = LocalFridayColors.current.isDark

@Composable
fun FridayTheme(content: @Composable () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val tokens = remember(isDark) {
        if (isDark) {
            FridayColorTokens(
                lime = Color(0xFF2A3600),
                lilac = Color(0xFF1B1437),
                cream = Color(0xFF252216),
                mint = Color(0xFF092416),
                pink = Color(0xFF321028),
                coral = Color(0xFF3E120A),
                navy = Color(0xFF0C1022),
                background = Color(0xFF121212),
                isDark = true
            )
        } else {
            FridayColorTokens(
                lime = Color(0xFFD6FF3D),
                lilac = Color(0xFFC6BFFF),
                cream = Color(0xFFFFF9E3),
                mint = Color(0xFFB2FFD6),
                pink = Color(0xFFFFB8EB),
                coral = Color(0xFFFF8C70),
                navy = Color(0xFF10162F),
                background = Color(0xFFF9F9F9),
                isDark = false
            )
        }
    }
    val cs = remember(isDark) {
        if (isDark) darkColorScheme(
            primary = Color.White, background = Color(0xFF121212), surface = Color(0xFF1E1E1E),
            onBackground = Color(0xFFF1F1F1), onSurface = Color(0xFFF1F1F1), error = Color(0xFFEF4444),
            surfaceVariant = Color(0xFF2A2A2A), onSurfaceVariant = Color(0xFFB0B0B0)
        ) else lightColorScheme(
            primary = Color.Black, background = tokens.background, surface = Color.White,
            onBackground = Color.Black, onSurface = Color.Black, error = Color(0xFFEF4444)
        )
    }
    CompositionLocalProvider(LocalFridayColors provides tokens) {
        MaterialTheme(colorScheme = cs, content = content)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DATA HELPERS
// ─────────────────────────────────────────────────────────────────────────────
data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
operator fun <A, B, C, D> Quad<A, B, C, D>.component1() = first
operator fun <A, B, C, D> Quad<A, B, C, D>.component2() = second
operator fun <A, B, C, D> Quad<A, B, C, D>.component3() = third
operator fun <A, B, C, D> Quad<A, B, C, D>.component4() = fourth

fun getDynamicDate(): String = try {
    SimpleDateFormat("EEEE,\nMMMM d, yyyy", Locale.getDefault()).format(Date())
} catch (_: Exception) { "Sunday,\nJune 14, 2026" }

fun getMotivatingSubtitle(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Rise and shine! A fresh start to achieve your goals today."
        in 12..16 -> "Keep going! You're making great progress. Stay focused."
        in 17..21 -> "Great job today. Take a moment to appreciate your effort."
        else -> "Rest well. Sleep is the fuel for tomorrow's success."
    }
}

fun getAppNameFromPackage(pkg: String): String = when {
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

fun getSafeTimestamp(json: JSONObject): Long {
    val ts = json.optLong("timestamp", 0L)
    return when {
        ts == 0L -> System.currentTimeMillis()
        ts < 10_000_000_000L -> ts * 1000L
        else -> ts
    }
}

fun getProcessedAttentionTasks(tasks: List<JSONObject>): List<JSONObject> {
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

@Composable
fun getDynamicGreeting(name: String): String {
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    return stringResource(when (hour) {
        in 0..11 -> R.string.greeting_morning
        in 12..16 -> R.string.greeting_afternoon
        in 17..21 -> R.string.greeting_evening
        else -> R.string.greeting_night
    }, name)
}

// ─────────────────────────────────────────────────────────────────────────────
// BOTTOM NAVIGATION
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun CustomBottomNavBar(activeTab: Int, onTabSelected: (Int) -> Unit, onSparkClicked: () -> Unit) {
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
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(y = (-24).dp)) {
            Button(
                onClick = onSparkClicked,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface),
                shape = CircleShape,
                border = BorderStroke(2.dp, ColorBlockLime),
                modifier = Modifier.size(56.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.Mic, "AI Assistant", tint = ColorBlockLime, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(bounded = true),
                onClick = onClick
            )
            .padding(8.dp)
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = tint)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DASHBOARD TAB (HOME)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun DashboardTab(
    scrollState: ScrollState,
    userName: String,
    isConnected: Boolean,
    connectionStatus: String,
    stressScore: Int,
    attentionTasks: List<JSONObject>,
    activeActionCard: JSONObject?,
    onActionDismiss: (String) -> Unit,
    onActionAccept: (JSONObject) -> Unit,
    onAttentionClick: (JSONObject) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("FRIDAY", fontSize = 26.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(userName.take(1).ifEmpty { "U" }, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }

        // Connection banner
        val isOffline = !isConnected
        val bannerBg = if (isOffline) Color(0xFFD6FF3D) else MaterialTheme.colorScheme.surface
        val bannerBorder = if (isOffline) Color(0xFFD6FF3D) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        val textColor = if (isOffline) Color.Black else MaterialTheme.colorScheme.onSurface
        val labelColor = if (isOffline) Color.Black.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
        val statusText = if (isOffline) "STANDALONE LOCAL ENGINE ACTIVE" else connectionStatus

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bannerBg, RoundedCornerShape(12.dp))
                .border(1.dp, bannerBorder, RoundedCornerShape(12.dp))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(stringResource(R.string.title_node_connection), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = labelColor, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(statusText, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textColor)
            }
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if (isConnected) Color(0xFF10B981) else Color(0xFFD6FF3D)))
        }

        // Greeting
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(getDynamicGreeting(userName), fontSize = 40.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W300, letterSpacing = (-0.96).sp, lineHeight = 44.sp, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(10.dp))
            Text(getMotivatingSubtitle(), fontSize = 17.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W300, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }

        // Briefing card
        NotificationBriefingCard(tasks = attentionTasks, stressScore = stressScore)

        // Active recommendation (conditional)
        if (activeActionCard != null) {
            ActiveRecommendationCard(card = activeActionCard, onDismiss = onActionDismiss, onAccept = onActionAccept)
        }

        // Top-3 attention
        AttentionPanel(tasks = attentionTasks, onAttentionClick = onAttentionClick)

        Spacer(Modifier.height(40.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ACTIVE RECOMMENDATION CARD
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ActiveRecommendationCard(
    card: JSONObject,
    onDismiss: (String) -> Unit,
    onAccept: (JSONObject) -> Unit
) {
    val actionId = card.optString("action_id", "")
    val type = card.optString("type", "")
    val message = card.optString("message", "")
    val score = card.optDouble("score", 0.0)
    val agentName = card.optString("agent", "Orchestrator").replaceFirstChar { it.titlecase(Locale.getDefault()) }
    val isHandoff = type == "MEDIA_HANDOFF" || type == "PAGE_HANDOFF"

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, ColorBlockLime.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
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
                TextButton(onClick = { onDismiss(actionId) }) { Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onAccept(card) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface), shape = RoundedCornerShape(12.dp)) {
                    Icon(if (isHandoff) Icons.Default.PlayArrow else Icons.Default.Check, null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isHandoff) "Resume" else "Helpful", color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ATTENTION PANEL & ROW
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AttentionPanel(
    tasks: List<JSONObject>,
    onAttentionClick: (JSONObject) -> Unit
) {
    val displayTasks = remember(tasks.size, tasks.map { it.optString("action_id") }) {
        getProcessedAttentionTasks(tasks).take(3)
    }
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockCream), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.title_what_needs_attention), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (displayTasks.isEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("FRIDAY is learning your flow...", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(4.dp))
                    Text("Move around and start your routines. I'll prioritize what needs attention here.", fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    displayTasks.forEachIndexed { i, task ->
                        AttentionTaskRow(index = i, task = task, totalCount = displayTasks.size, onAttentionClick = onAttentionClick)
                    }
                }
            }
            if (tasks.size > 3) {
                Text("+${tasks.size - 3} more — see Timeline tab", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
fun AttentionTaskRow(index: Int, task: JSONObject, totalCount: Int, onAttentionClick: (JSONObject) -> Unit) {
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
            onClick = { onAttentionClick(task) },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface),
            shape = RoundedCornerShape(9999.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            modifier = Modifier.height(32.dp)
        ) { Text(if (isNotif) "Clear" else stringResource(R.string.btn_action), fontSize = 11.sp, color = MaterialTheme.colorScheme.surface) }
    }
    if (index < totalCount - 1) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
}

// ─────────────────────────────────────────────────────────────────────────────
// MIND TAB (INSIGHTS)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MindTab(
    scrollState: ScrollState,
    stress: Int,
    appSwitches: Int,
    typingCadenceMs: Long,
    notificationsCount: Int,
    focusPercentage: String,
    confidencePercentage: String,
    memoryMatchPercentage: String,
    locationState: String,
    focusTimeDisplay: String,
    isGhostMode: Boolean,
    isFocusModeActive: Boolean,
    isReduceInterruptActive: Boolean,
    onToggleFocusMode: () -> Unit,
    onToggleReduceInterrupt: () -> Unit,
    onExplainStress: () -> Unit
) {
    val subtitleText = when {
        stress == 0 -> "Learning routine..."
        stress >= 75 -> "Elevated Stress"
        stress >= 50 -> "Focused & Engaged"
        else -> "Calm & Balanced"
    }
    val insightText = when {
        stress == 0 -> "Ambient tracking active. As we collect telemetry from your app switching and typing speeds, personalized cognitive insights will appear here."
        stress >= 75 -> "Your stress score is high ($stress%). We detect multiple context transitions. A short break is recommended."
        appSwitches > 8 -> "Frequent app switching detected. Try to isolate your environment to stay in deep focus."
        else -> "Focus metrics look solid today. Your typing cadence is consistent and app switching is minimal."
    }
    val burnoutScore = remember(stress) { if (stress == 0) 10f else (stress * 0.7f).coerceIn(10f, 100f) }
    val fatigueScore = remember(stress, appSwitches, typingCadenceMs) {
        if (stress == 0) 15f else (appSwitches * 6f + typingCadenceMs / 25f).coerceIn(10f, 100f)
    }
    val socialScore = remember(notificationsCount) {
        if (notificationsCount == 0) 12f else (notificationsCount * 12f).coerceIn(10f, 100f)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        // Header
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockLime), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(stringResource(R.string.title_current_status), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.title_mindspace), fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Text(subtitleText, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        // Insight prose
        Text(insightText, fontSize = 18.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W300, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))

        // Cognitive Load gauge
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockMint), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.title_cognitive_pulse), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        val gaugeLabel = when {
                            stress == 0 -> "Awaiting telemetry data."
                            stress >= 75 -> "High cognitive load detected."
                            stress >= 50 -> "Moderate load. Stay focused."
                            else -> "Low load. You're in the zone."
                        }
                        Text(gaugeLabel, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (isGhostMode) "Ghost mode active. Telemetry simplified." else "High-fidelity telemetry streaming.",
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

        // Radar chart
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockNavy), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.title_cognitive_risk_radar), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.Start))
                Spacer(Modifier.height(16.dp))
                BiometricsRadarChart(stress = stress.toFloat(), burnout = burnoutScore, fatigue = fatigueScore, social = socialScore)
            }
        }

        // Metrics grid
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.title_current_state), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BiometricMetricItem("Stress", "$stress%", when { stress >= 75 -> "Elevated"; stress >= 50 -> "Moderate"; else -> "Stable" })
                BiometricMetricItem("Focus", focusPercentage, when { focusPercentage == "Learning..." -> "Learning..."; appSwitches > 5 -> "Distracted"; else -> "High Perf" })
                BiometricMetricItem("Cadence", if (typingCadenceMs == 0L) "--" else "${typingCadenceMs}ms", when {
                    typingCadenceMs == 0L -> "No Input"; typingCadenceMs in 10..180 -> "Fast"; typingCadenceMs > 600 -> "Slow"; else -> "Consistent"
                })
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BiometricMetricItem("Confidence", confidencePercentage, when { confidencePercentage == "Learning..." -> "Learning..."; stress >= 75 -> "Fluctuating"; else -> "Stable" })
                BiometricMetricItem("Social Load", if (notificationsCount == 0) "0%" else "${socialScore.toInt()}%", when { notificationsCount == 0 -> "Quiet"; socialScore >= 60 -> "Demanding"; else -> "Introvert" })
            }
        }

        // Emotion agent
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
                    Text(confidencePercentage, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text(stringResource(R.string.title_confidence_score), fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }

        // Burnout agent
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

        // Social agent
        val sTitle = when { notificationsCount == 0 -> "Risk: Stable"; socialScore >= 60 -> "Risk: High"; socialScore >= 35 -> "Risk: Moderate"; else -> "Risk: Low" }
        val sDesc = when { notificationsCount == 0 -> "No recent communication spikes. Social load is light."; socialScore >= 60 -> "High messaging volume. Consider putting phone on Do Not Disturb."; else -> "Impending tasks stable. Social load is well within limits." }
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

        // Signals
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.title_why_signals), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SignalRowItem("App Switching", "$appSwitches events")
                SignalRowItem("Active Location", locationState.uppercase(Locale.getDefault()))
                SignalRowItem("Typing Delay", "$typingCadenceMs ms")
                SignalRowItem("Session Duration", focusTimeDisplay)
            }
        }

        // Behavioral Anomaly
        val anomaly = remember(appSwitches, typingCadenceMs, stress) {
            (appSwitches * 2 + (typingCadenceMs / 200).toInt() + stress / 4).coerceIn(2, 98)
        }
        val dayOfWeek = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
        val (aLabel, aColor, aBg, aDesc) = when {
            anomaly > 60 -> Quad("ELEVATED DEVIATION", Color(0xFFE57373), Color(0x1AE57373), "Frequent app switching and altered cadence indicate context fatigue.")
            anomaly > 35 -> Quad("MODERATE DEVIATION", Color(0xFFFFB74D), Color(0x1AFFB74D), "Mild pacing variation detected. Monitor focus cadence.")
            else -> Quad("STABLE SYSTEM PATTERN", Color(0xFF81C784), Color(0x1A81C784), "Current usage aligns cleanly with historical baseline.")
        }
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            modifier = Modifier.fillMaxWidth()
        ) {
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

        // Memory Correlation
        val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MONTH, -3) }
        val pastMonth = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
        val matchPct = memoryMatchPercentage.replace("%", "").toIntOrNull() ?: 0
        val (mLabel, mColor, mBg) = when {
            matchPct > 75 -> Triple("HIGH HISTORICAL FIT", Color(0xFF64B5F6), Color(0x1A64B5F6))
            matchPct > 45 -> Triple("MODERATE FIT", Color(0xFFBA68C8), Color(0x1ABA68C8))
            else -> Triple("NOVEL PATTERNFLOW", Color(0xFF90A4AE), Color(0x1A90A4AE))
        }
        val outcome = when { stress >= 75 -> "High cognitive load detected. Recommend task transition."; stress >= 50 -> "Moderate stress. High focus but stable output."; else -> "Balanced flow state. Smooth task transitions." }
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.title_memory_correlation), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(mBg).padding(horizontal = 8.dp, vertical = 4.dp)) { Text(mLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = mColor) }
                }
                Spacer(Modifier.height(12.dp))
                Text("Similar Past Events: $memoryMatchPercentage Match to $pastMonth Baseline.", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text("Outcome: $outcome", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Action buttons
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onToggleFocusMode,
                colors = ButtonDefaults.buttonColors(containerColor = if (isFocusModeActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface),
                shape = RoundedCornerShape(9999.dp), modifier = Modifier.weight(1f)
            ) { Text(if (isFocusModeActive) "Stop Focus" else "Start Focus", color = if (isFocusModeActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.surface, fontSize = 11.sp) }

            Button(
                onClick = onToggleReduceInterrupt,
                colors = ButtonDefaults.buttonColors(containerColor = if (isReduceInterruptActive) MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f) else Color.Transparent),
                shape = RoundedCornerShape(9999.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f)
            ) { Text(if (isReduceInterruptActive) "Allow Interrupt" else "Reduce Interrupt", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp) }

            Button(
                onClick = onExplainStress,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(9999.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f)
            ) { Text("Explain Stress", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp) }
        }

        Spacer(Modifier.height(40.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CONTINUITY TAB (TIMELINE)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ContinuityTab(
    scrollState: ScrollState,
    stressScore: Int,
    productivityPercentage: String,
    peakFocusTime: String,
    focusTimeDisplay: String,
    stressSpikeTime: String,
    locationState: String,
    appSwitchesCount: Int,
    notificationsCount: Int,
    isLaptopConnected: Boolean,
    activeTaskName: String,
    activeTaskSteps: List<Pair<String, String>>,
    attentionTasks: List<JSONObject>,
    recentLaptopSites: List<Triple<String, String, String>>,
    timelineEvents: List<TimelineEvent>,
    activityIntensities: List<Float>,
    isMemoryMomentDismissed: Boolean,
    onDismissMemoryMoment: () -> Unit,
    onPrioritizeTasks: () -> Unit,
    onSimulateJourney: () -> Unit,
    onAttentionClick: (JSONObject) -> Unit,
    onOpenUrl: (String) -> Unit,
    recentApp: String,
    lastNotification: String,
    bufferedEventsCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        // Header
        Text(stringResource(R.string.title_life_timeline), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 4.dp))
        Text(getDynamicDate(), fontSize = 40.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W300, letterSpacing = (-0.96).sp, lineHeight = 44.sp, color = MaterialTheme.colorScheme.onBackground)

        // Key metrics row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("PRODUCTIVITY", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold); Text(productivityPercentage, fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface) }
            Column { Text("PEAK FOCUS", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold); Text(peakFocusTime, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
            Column { Text("SESSION", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold); Text(focusTimeDisplay, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
            Column { Text("STRESS SPIKE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold); Text(stressSpikeTime, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
        }

        // Activity intensity visualizer
        ProductivityIntensityVisualizer(activityIntensities)

        // Today's story
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.title_today_story), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            val story = if (stressScore == 0)
                "Ambient tracking active. As we collect telemetry from your device switches and notification intercepts, your personalized daily story will build here."
            else
                "Today's journey began in the ${locationState.lowercase(Locale.getDefault())} context. $appSwitchesCount application switches logged, $notificationsCount distractions intercepted. Cognitive stress at $stressScore%."
            Text(story, fontSize = 20.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.W300, color = MaterialTheme.colorScheme.onSurface, lineHeight = 28.sp)
        }

        // Memory Moment
        if (!isMemoryMomentDismissed) {
            val memTitle = if (locationState.isEmpty() || locationState == "unknown") "Learning Routines" else locationState.replaceFirstChar { it.titlecase(Locale.getDefault()) }
            val memBody = if (locationState.isEmpty() || locationState == "unknown")
                "Move around and let me learn your focus triggers. Your routines will surface here automatically."
            else
                "You typically activate 'Deep Focus' in this context. Head to the Insights tab to start a focus session."
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Text("MEMORY MOMENT", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        IconButton(onClick = onDismissMemoryMoment, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
                        }
                    }
                    Text(memTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(memBody, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                }
            }
        }

        // Devices
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.title_device_continuity), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            DeviceContinuityRow("phone", "Galaxy Phone", "Active: $focusTimeDisplay")
            DeviceContinuityRow("laptop", "Work Laptop", if (isLaptopConnected) "Active (WebSocket Connected)" else "Offline")
        }

        // Task Journey
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.title_task_journey), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    if (activeTaskName.isEmpty()) {
                        Text("Awaiting task journey...", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(4.dp))
                        Text("Start a task from the What Needs Attention section below.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    } else {
                        Text(activeTaskName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(16.dp))
                        activeTaskSteps.forEach { (title, status) ->
                            TaskJourneyStep(title, status, status == "COMPLETED", status == "IN_PROGRESS")
                        }
                    }
                }
            }
        }

        // Full attention list / simulate
        val allTasks = remember(attentionTasks.size, attentionTasks.map { it.optString("action_id") }) {
            getProcessedAttentionTasks(attentionTasks)
        }
        if (allTasks.isNotEmpty()) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockCream), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("ALL PENDING ACTIONS", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        allTasks.forEachIndexed { i, task -> AttentionTaskRow(i, task, allTasks.size, onAttentionClick) }
                    }
                    Button(
                        onClick = onPrioritizeTasks,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(9999.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.btn_prioritize_automatically), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
                }
            }
        } else {
            Button(
                onClick = onSimulateJourney,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(9999.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Simulate Workspace Journey", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
        }

        // Cognitive balance status card
        val highStress = stressScore > 65
        val cardBg = if (highStress) (if (isDarkThemeGlobal) Color(0xFF4C0505) else Color(0xFFFEE2E2)) else (if (isDarkThemeGlobal) Color(0xFF064E3B) else Color(0xFFD1FAE5))
        val iconTint = if (highStress) (if (isDarkThemeGlobal) Color(0xFFFCA5A5) else Color(0xFF991B1B)) else (if (isDarkThemeGlobal) Color(0xFF34D399) else Color(0xFF065F46))
        val bodyColor = if (highStress) (if (isDarkThemeGlobal) Color(0xFFFECACA) else Color(0xFF7F1D1D)) else (if (isDarkThemeGlobal) Color(0xFFA7F3D0) else Color(0xFF065F46))
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (highStress) Icons.Default.Warning else Icons.Default.Check, null, tint = iconTint, modifier = Modifier.size(20.dp))
                Text(if (highStress) "Fatigue Predicted" else "Cognitive Balance Stable", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = iconTint)
                Text(if (highStress) "Sleep cycle correction needed. Rest suggested before 11:30 PM." else "Great job keeping your focus window clear of context switching.", fontSize = 13.sp, color = bodyColor)
            }
        }

        // System logs
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockNavy), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("SYSTEM LOGS & ACTIVITY", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f))
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SystemLogRow("Active App", "Focused: $recentApp")
                    SystemLogRow("Last Notification", lastNotification)
                    SystemLogRow("Offline Cache", "Buffered events: $bufferedEventsCount")
                }
            }
        }

        // Recent laptop sites
        if (recentLaptopSites.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("RECENT SITES · LAPTOP", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                recentLaptopSites.take(3).forEach { (title, displayUrl, fullUrl) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .clickable { onOpenUrl(fullUrl) }
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

        // Live activity log
        if (timelineEvents.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("LIVE ACTIVITY LOG", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                val recentTimeline = remember(timelineEvents.size) { timelineEvents.take(10) }
                recentTimeline.forEach { TimelineRowItem(it) }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SETTINGS TAB
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SettingsTab(
    scrollState: ScrollState,
    userName: String,
    userRole: String,
    isAccessibilityGranted: Boolean,
    isNotificationAccessGranted: Boolean,
    isPostNotificationGranted: Boolean,
    localMemoriesCount: Int,
    bufferedEventsCount: Int,
    isModelDownloaded: Boolean,
    isDownloadingModel: Boolean,
    downloadProgress: Float,
    localDevices: List<Pair<String, String>>,
    hubIp: String,
    hubPort: String,
    onHubIpChanged: (String) -> Unit,
    onHubPortChanged: (String) -> Unit,
    onConnectHub: () -> Unit,
    onManageMemories: () -> Unit,
    onManageDevices: () -> Unit,
    onDownloadModel: () -> Unit,
    onDeleteModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onSimulateDrop: () -> Unit,
    onSimulateReconnect: () -> Unit,
    onDisconnectDevicesConfirm: () -> Unit,
    onClearMemoriesConfirm: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    onOpenPermissionManager: () -> Unit,
    onToast: (String) -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.title_trust_center), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 4.dp))
        Text("Settings", fontSize = 40.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, letterSpacing = (-0.96).sp, color = MaterialTheme.colorScheme.onBackground)
        Text("Manage privacy, devices and personalization.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Profile
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface), contentAlignment = Alignment.Center) {
                Text(userName.take(1).ifEmpty { "U" }, color = MaterialTheme.colorScheme.surface, fontSize = 32.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(userName, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(userRole, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Privacy & Data
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockLime), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.title_privacy_data), fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                val prefs = remember { context.getSharedPreferences("friday_settings", Context.MODE_PRIVATE) }
                var dataProcessing by remember { mutableStateOf(prefs.getBoolean("data_processing", true)) }
                var cloudBackup by remember { mutableStateOf(prefs.getBoolean("cloud_backup", true)) }
                var dataEncryption by remember { mutableStateOf(prefs.getBoolean("data_encryption", true)) }
                ToggleRow(stringResource(R.string.hint_data_processing), stringResource(R.string.hint_mostly_on_device), dataProcessing) { dataProcessing = it; prefs.edit().putBoolean("data_processing", it).apply(); onToast(if (it) "On-device processing enabled" else "Disabled") }
                ToggleRow(stringResource(R.string.hint_cloud_backup), checked = cloudBackup) { cloudBackup = it; prefs.edit().putBoolean("cloud_backup", it).apply(); onToast(if (it) "Cloud backup enabled" else "Disabled") }
                ToggleRow(stringResource(R.string.hint_data_encryption), checked = dataEncryption) { dataEncryption = it; prefs.edit().putBoolean("data_encryption", it).apply(); onToast(if (it) "Encryption enabled" else "Disabled") }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onOpenPermissionManager,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.btn_manage_permissions), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
            }
        }

        // System Permissions
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.title_system_permissions), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PermissionRow("Accessibility Service", "For active application context switches", isAccessibilityGranted, onRequestAccessibility)
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                PermissionRow("Notification Listener", "For intercepting notifications", isNotificationAccessGranted, onRequestNotificationAccess)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    PermissionRow("Post Notifications", "Foreground sensing alert channel", isPostNotificationGranted, onRequestPostNotifications)
                }
            }
        }

        // Memory
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockLilac), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.title_memory), fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("$localMemoriesCount", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface); Text("STORED", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                    Column { Text("$bufferedEventsCount", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface); Text("EVENTS", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                    Column { Text(String.format(Locale.US, "%.1f MB", 0.4f + localMemoriesCount * 0.15f), fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface); Text("SIZE", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                }
                Button(onClick = onManageMemories, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("View & Manage Memories", color = MaterialTheme.colorScheme.surface)
                }
            }
        }

        // On-Device Neural AI (Optional)
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("ON-DEVICE NEURAL AI (OPTIONAL)", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isModelDownloaded) {
                    Text("Phi-3 Mini INT4 Active", fontSize = 22.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                    Text("Local neural LLM (2.3 GB) installed in app storage for rich offline reasoning.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = onDeleteModel,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete Model & Free Storage (2.3 GB)", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                    }
                } else if (isDownloadingModel) {
                    Text("Downloading Neural Model...", fontSize = 22.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                    val pct = (downloadProgress * 100).toInt()
                    Text("Progress: $pct% (${String.format(Locale.US, "%.0f", downloadProgress * 2300f)} / 2300 MB)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    )
                    Button(
                        onClick = onCancelDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel Download", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text("Rule Heuristics Active", fontSize = 22.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                    Text("Using ultra-fast on-device heuristics (<10ms, 0 MB storage). You can optionally download the 2.3 GB Phi-3 Mini INT4 neural model over the internet for local LLM intelligence.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = onDownloadModel,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Download Neural Model (2.3 GB)", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Devices
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
                Button(onClick = onManageDevices, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_manage_devices), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
            }
        }

        // Hub IP
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.title_hub_override_ip_config), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = hubIp, onValueChange = onHubIpChanged, label = { Text("Hub IP", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, focusedBorderColor = MaterialTheme.colorScheme.onSurface, unfocusedBorderColor = Color.LightGray), modifier = Modifier.weight(2f))
                    OutlinedTextField(value = hubPort, onValueChange = onHubPortChanged, label = { Text("Port", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, focusedBorderColor = MaterialTheme.colorScheme.onSurface, unfocusedBorderColor = Color.LightGray), modifier = Modifier.weight(1f))
                }
                Button(onClick = onConnectHub, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_connect_manually), color = MaterialTheme.colorScheme.surface) }
            }
        }

        // Assistance style
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.title_assistance_style), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val prefs = remember { context.getSharedPreferences("friday_settings", Context.MODE_PRIVATE) }
                var style by remember { mutableStateOf(prefs.getString("assistance_style", "Balanced") ?: "Balanced") }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Quiet", "Balanced", "Proactive").forEach { s ->
                        Row(modifier = Modifier.fillMaxWidth().clickable { style = s; prefs.edit().putString("assistance_style", s).apply(); onToast("Style: $s") }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = style == s, onClick = { style = s; prefs.edit().putString("assistance_style", s).apply(); onToast("Style: $s") }, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.onSurface))
                            Spacer(Modifier.width(8.dp)); Text(s, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }

        // Sandbox Developer Tools Card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("SANDBOX / DEVELOPER TOOLS", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Live Demo Staging Tools", fontSize = 20.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                Button(
                    onClick = onSimulateDrop,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD6FF3D)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Trigger ONNX Fallback", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onSimulateReconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reconnect Hub", color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Danger Zone
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = if (isDarkThemeGlobal) Color(0xFF4C0505) else Color(0xFFFEE2E2)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.title_danger_zone), fontSize = 32.sp, fontWeight = FontWeight.Black, color = if (isDarkThemeGlobal) Color(0xFFFECACA) else Color(0xFF7F1D1D))
                Button(onClick = onDisconnectDevicesConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_disconnect_devices), color = Color(0xFFEF4444), fontWeight = FontWeight.Bold) }
                Button(onClick = onClearMemoriesConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_clear_memories), color = Color(0xFFEF4444), fontWeight = FontWeight.Bold) }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SHARED COMPONENTS
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
fun SystemLogRow(title: String, subtitle: String) {
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
fun PermissionRow(title: String, subtitle: String, granted: Boolean, onClick: () -> Unit) {
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
    val limeColor = ColorBlockLime
    Box(modifier = Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(200.dp)) {
            val c = size.width / 2; val maxR = size.width / 2 * 0.8f
            for (i in 1..4) {
                val r = maxR * (i / 4f)
                drawRect(Color.White.copy(alpha = 0.15f), Offset(c - r, c - r), Size(r * 2, r * 2), style = Stroke(1.dp.toPx()))
            }
            drawLine(Color.White.copy(alpha = 0.2f), Offset(c, c - maxR), Offset(c, c + maxR), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.2f), Offset(c - maxR, c), Offset(c + maxR, c), 1.dp.toPx())

            val radarPath = Path().apply {
                moveTo(c, c - maxR * (stress / 100f))
                lineTo(c + maxR * (social / 100f), c)
                lineTo(c, c + maxR * (burnout / 100f))
                lineTo(c - maxR * (fatigue / 100f), c)
                close()
            }
            drawPath(radarPath, limeColor.copy(alpha = 0.4f))
            drawPath(radarPath, limeColor, style = Stroke(2.dp.toPx()))
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
fun ProductivityIntensityVisualizer(activityIntensities: List<Float>) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockLime), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("ACTIVITY INTENSITY", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(24.dp))
            val barColor = MaterialTheme.colorScheme.onSurface
            Canvas(modifier = Modifier.fillMaxWidth().height(100.dp)) {
                val count = activityIntensities.size
                if (count == 0) return@Canvas
                val spacingPx = 4.dp.toPx()
                val totalSpacing = spacingPx * (count - 1)
                val barWidth = (size.width - totalSpacing) / count
                val cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                activityIntensities.forEachIndexed { i, h ->
                    val barHeight = size.height * h.coerceIn(0.02f, 1f)
                    val left = i * (barWidth + spacingPx)
                    val top = size.height - barHeight
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(left, top),
                        size = Size(barWidth, barHeight),
                        cornerRadius = cornerRadius
                    )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val briefTitle = when (hour) { in 5..11 -> "Morning Brief"; in 12..16 -> "Afternoon Brief"; in 17..21 -> "Evening Brief"; else -> "Night Brief" }
    val unreadText = if (tasks.isEmpty()) "No Alerts" else "${tasks.size} Unread"

    val briefItems = remember(tasks.size, stressScore) {
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

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
        modifier = Modifier.fillMaxWidth()
    ) {
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f), RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
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
// PROACTIVE OVERLAY SCREEN (VOICE ASSISTANT)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ProactiveOverlayScreen(
    assistantState: VoiceState,
    transcriptText: String,
    assistantResponse: String,
    errorMessage: String,
    onMicClick: () -> Unit,
    onReset: () -> Unit,
    onSendTextQuery: (String) -> Unit,
    onClose: () -> Unit
) {
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
                    modifier = Modifier.size(80.dp).clip(CircleShape).background(if (assistantState == VoiceState.RECORDING) ColorBlockLime else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).clickable(onClick = onMicClick),
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
                    Button(onClick = onReset, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground), shape = RoundedCornerShape(9999.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Clear & Ask Something Else", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.background) }
                }
                VoiceState.ERROR -> {
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ColorBlockCoral), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(20.dp)) { Text("ENGINE ERROR", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.7f)); Spacer(Modifier.height(8.dp)); Text(errorMessage, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onReset, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground), shape = RoundedCornerShape(9999.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Reset", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.background) }
                }
                else -> {
                    Text("SUGGESTED COMMANDS", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("Silence all alerts", "Prepare Evening Brief", "Explain Current Stress", "Start Focus Mode").forEach { cmd ->
                            OverlayActionButton(cmd) { onSendTextQuery(cmd) }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(9999.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(9999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PulsatingDotsCompose(color: Color) {
    val t = rememberInfiniteTransition(label = "dots")
    val s1 by t.animateFloat(0.5f, 1.3f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "d1")
    val s2 by t.animateFloat(0.5f, 1.3f, infiniteRepeatable(tween(600), RepeatMode.Reverse, androidx.compose.animation.core.StartOffset(200)), label = "d2")
    val s3 by t.animateFloat(0.5f, 1.3f, infiniteRepeatable(tween(600), RepeatMode.Reverse, androidx.compose.animation.core.StartOffset(400)), label = "d3")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(s1, s2, s3).forEach { s -> Box(modifier = Modifier.size(8.dp).graphicsLayer(scaleX = s, scaleY = s).background(color, CircleShape)) }
    }
}

@Composable
private fun RippleWaveLoaderCompose() {
    val t = rememberInfiniteTransition(label = "wave")
    val anim = (0 until 7).map { i -> t.animateFloat(0.4f, 1.6f, infiniteRepeatable(tween(500), RepeatMode.Reverse, androidx.compose.animation.core.StartOffset(i * 80)), label = "w$i") }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        anim.forEachIndexed { i, a ->
            Box(modifier = Modifier.width(3.dp).height(18.dp).graphicsLayer(scaleY = a.value).background(if (isDarkThemeGlobal && i % 2 == 0) Color(0xFFD6FF3D) else if (isDarkThemeGlobal) Color.White else Color.Black, RoundedCornerShape(99.dp)))
        }
    }
}
