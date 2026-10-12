package com.friday.node.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.friday.node.data.remote.ContextObjectBuilder
import com.friday.node.data.remote.WebSocketManager
import com.friday.node.utils.BatteryOptimizer
import org.json.JSONObject

class FRIDAYAccessibilityService : AccessibilityService() {

    private val TAG = "FRIDAY_Accessibility"

    // Tracking parameters for typing cadence metrics
    private var lastTypeTimestamp = 0L
    private var keyCount = 0
    private var totalDeltaTime = 0L

    private var lastTrackTime = 0L
    private var lastRecordedUrl: String? = null
    private var lastRecordedVideoId: String? = null
    private var lastRecordedMediaTitle: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val configManager = com.friday.node.config.OnboardingConfigManager(this)
        if (!configManager.isModuleEnabled("Accessibility & Touch")) {
            return
        }

        // Check our localized optimizer before running operations
        if (BatteryOptimizer.getCurrentMode() == BatteryOptimizer.RuntimeMode.GHOST) {
            // Ghost Mode Rule: Accessibility processing is disabled to protect battery life
            return
        }

        val currentPackage = event.packageName?.toString() ?: ""

        when (event.eventType) {
            // 1. Capture App Switching and Window Focus shifts
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (currentPackage.isNotEmpty()) {
                    val contextPacket = JSONObject().apply {
                        put("type", "app_switch")
                        put("package_name", currentPackage)
                        put("timestamp", System.currentTimeMillis())
                    }

                    Log.d(TAG, "Window switched to focus app: $currentPackage")
                    WebSocketManager.getInstance().sendEvent(contextPacket.toString())

                    // Emit lightweight AMBIENT_CONTEXT_UPDATE for cross-device handoff continuity
                    val ambientPacket = JSONObject().apply {
                        put("type", "AMBIENT_CONTEXT_UPDATE")
                        put("payload", JSONObject().apply {
                            put("active_app", currentPackage)
                            put("timestamp", System.currentTimeMillis())
                            put("device_state", "ACTIVE")
                        })
                    }
                    WebSocketManager.getInstance().sendEvent(ambientPacket.toString())

                    // Local broadcast for UI update
                    val localIntent = android.content.Intent("com.friday.node.APP_SWITCH_DETECTED").apply {
                        putExtra("package_name", currentPackage)
                        putExtra("timestamp", System.currentTimeMillis())
                    }
                    sendBroadcast(localIntent)

                    trackBrowserOrYoutubeActivity(currentPackage)
                }
            }

            // 2. Capture Content Changes to calculate Typing Cadence (without collecting raw keystrokes)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val currentTimestamp = System.currentTimeMillis()

                if (lastTypeTimestamp != 0L) {
                    val gap = currentTimestamp - lastTypeTimestamp
                    // Only process consecutive character interactions within a normal threshold (2 seconds)
                    if (gap in 10..2000) {
                        keyCount++
                        totalDeltaTime += gap
                    }
                }
                lastTypeTimestamp = currentTimestamp

                // Every 10 keystrokes, calculate typing speed indicators and stream to Compute Hub
                if (keyCount >= 10) {
                    val averageCadenceMs = if (keyCount > 0) totalDeltaTime / keyCount else 0

                    val cadencePacket = JSONObject().apply {
                        put("type", "typing_metrics")
                        put("average_delay_ms", averageCadenceMs)
                        put("timestamp", currentTimestamp)
                    }

                    Log.d(TAG, "Streaming evaluated typing speed cadence delta: $averageCadenceMs ms")
                    WebSocketManager.getInstance().sendEvent(cadencePacket.toString())

                    // Local broadcast for UI update
                    val localIntent = android.content.Intent("com.friday.node.TYPING_CADENCE_DETECTED").apply {
                        putExtra("average_delay_ms", averageCadenceMs)
                        putExtra("timestamp", currentTimestamp)
                    }
                    sendBroadcast(localIntent)

                    // Reset interval variables
                    keyCount = 0
                    totalDeltaTime = 0L
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // In Aware or Ghost mode, skip heavy view-tree inspection to preserve battery
                if (BatteryOptimizer.getCurrentMode() != BatteryOptimizer.RuntimeMode.ACTIVE) {
                    return
                }
                if (currentPackage.isNotEmpty() && isTargetBrowserOrMediaApp(currentPackage)) {
                    trackBrowserOrYoutubeActivity(currentPackage)
                }
            }
        }
    }

    private fun isTargetBrowserOrMediaApp(packageName: String): Boolean {
        return packageName == "com.android.chrome" ||
                packageName == "com.sec.android.app.sbrowser" ||
                packageName == "org.mozilla.firefox" ||
                packageName == "com.google.android.youtube"
    }

    private fun trackBrowserOrYoutubeActivity(packageName: String) {
        val now = System.currentTimeMillis()
        if (now - lastTrackTime < 5000L) return
        lastTrackTime = now

        try {
            if (!isTargetBrowserOrMediaApp(packageName)) {
                val wasActive = (ContextObjectBuilder.activePage != null || ContextObjectBuilder.activeMedia != null)
                ContextObjectBuilder.activePage = null
                ContextObjectBuilder.activeMedia = null
                if (wasActive) {
                    lastRecordedUrl = null
                    lastRecordedVideoId = null
                    lastRecordedMediaTitle = null
                    Log.d(TAG, "Left active page/media. Triggering immediate push.")
                    sendBroadcast(android.content.Intent("com.friday.node.TRIGGER_CONTEXT_PUSH"))
                }
                return
            }

            val rootNode = rootInActiveWindow ?: return
            
            if (packageName == "com.android.chrome" || packageName == "com.sec.android.app.sbrowser" || packageName == "org.mozilla.firefox") {
                val extracted = findBrowserUrl(rootNode)
                if (extracted != null) {
                    val sanitized = sanitizeWebContext(extracted)
                    
                    val activePageObj = JSONObject().apply {
                        put("domain", sanitized.domain)
                        put("category", sanitized.category)
                        put("url", sanitized.sanitizedUrl)
                        put("title", sanitized.displayTitle)
                        put("timestamp", now)
                    }
                    val changed = (sanitized.sanitizedUrl != lastRecordedUrl)
                    ContextObjectBuilder.activePage = activePageObj
                    if (changed) {
                        lastRecordedUrl = sanitized.sanitizedUrl
                        Log.d(TAG, "Privacy Sanitizer: browser domain changed to ${sanitized.domain} [${sanitized.category}]")
                        sendBroadcast(android.content.Intent("com.friday.node.TRIGGER_CONTEXT_PUSH"))
                    }
                    
                    // Parse YouTube details if url is a YouTube link
                    if (extracted.contains("youtube.com/watch") || extracted.contains("youtu.be/")) {
                        val uri = try {
                            val fullUrl = if (extracted.startsWith("http")) extracted else "https://$extracted"
                            android.net.Uri.parse(fullUrl)
                        } catch (_: Exception) { null }
                        val videoId = uri?.getQueryParameter("v") ?: extracted.substringAfter("youtu.be/").substringBefore("?").substringBefore("&")
                        val timeParam = uri?.getQueryParameter("t")
                        var seconds = 0
                        if (timeParam != null) {
                            seconds = try {
                                timeParam.replace("s", "").toInt()
                            } catch (e: java.lang.Exception) {
                                0
                            }
                        }
                        if (!videoId.isNullOrBlank()) {
                            val activeMediaObj = JSONObject().apply {
                                put("provider", "youtube")
                                put("video_id", videoId)
                                put("playback_timestamp_seconds", seconds)
                                put("title", sanitized.displayTitle)
                                put("timestamp", now)
                            }
                            val mediaChanged = (videoId != lastRecordedVideoId)
                            ContextObjectBuilder.activeMedia = activeMediaObj
                            if (mediaChanged) {
                                lastRecordedVideoId = videoId
                                Log.d(TAG, "Extracted YouTube media changed: videoId=$videoId")
                                sendBroadcast(android.content.Intent("com.friday.node.TRIGGER_CONTEXT_PUSH"))
                            }
                        }
                    } else {
                        val mediaWasActive = (ContextObjectBuilder.activeMedia != null)
                        ContextObjectBuilder.activeMedia = null
                        if (mediaWasActive) {
                            lastRecordedVideoId = null
                            sendBroadcast(android.content.Intent("com.friday.node.TRIGGER_CONTEXT_PUSH"))
                        }
                    }
                }
            } else if (packageName == "com.google.android.youtube") {
                // Try to extract video title from YouTube app
                val title = findYoutubeVideoTitle(rootNode)
                if (!title.isNullOrBlank()) {
                    val sanitizedTitle = title.take(80)
                    val activePageObj = JSONObject().apply {
                        put("domain", "youtube.com")
                        put("category", "MEDIA_STREAMING")
                        put("url", "https://youtube.com")
                        put("title", "YouTube: $sanitizedTitle")
                        put("timestamp", now)
                    }
                    val pageChanged = (lastRecordedUrl != "https://youtube.com")
                    ContextObjectBuilder.activePage = activePageObj
                    if (pageChanged) {
                        lastRecordedUrl = "https://youtube.com"
                    }
                    
                    val activeMediaObj = JSONObject().apply {
                        put("provider", "youtube")
                        put("video_id", "")
                        put("title", sanitizedTitle)
                        put("playback_timestamp_seconds", 0)
                        put("timestamp", now)
                    }
                    val mediaChanged = (sanitizedTitle != lastRecordedMediaTitle)
                    ContextObjectBuilder.activeMedia = activeMediaObj
                    if (mediaChanged || pageChanged) {
                        lastRecordedMediaTitle = sanitizedTitle
                        Log.d(TAG, "Extracted YouTube App title changed: $sanitizedTitle")
                        sendBroadcast(android.content.Intent("com.friday.node.TRIGGER_CONTEXT_PUSH"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error tracking browser/youtube activity: ${e.message}")
        }
    }

    private fun findBrowserUrl(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null
        
        // 1. Try Chrome URL bar ID
        val chromeUrlBar = rootNode.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
        if (!chromeUrlBar.isNullOrEmpty()) {
            val text = chromeUrlBar[0].text?.toString()
            if (!text.isNullOrBlank()) {
                return text
            }
        }
        
        // 2. Try Samsung Internet URL bar ID
        val samsungUrlBar = rootNode.findAccessibilityNodeInfosByViewId("com.sec.android.app.sbrowser:id/location_bar_edit_text")
        if (!samsungUrlBar.isNullOrEmpty()) {
            val text = samsungUrlBar[0].text?.toString()
            if (!text.isNullOrBlank()) {
                return text
            }
        }

        // 3. Try Firefox URL bar ID
        val firefoxUrlBar = rootNode.findAccessibilityNodeInfosByViewId("org.mozilla.firefox:id/url_bar_title")
        if (!firefoxUrlBar.isNullOrEmpty()) {
            val text = firefoxUrlBar[0].text?.toString()
            if (!text.isNullOrBlank()) {
                return text
            }
        }

        return null
    }

    private fun findYoutubeVideoTitle(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null
        val titleNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/title")
        if (!titleNodes.isNullOrEmpty()) {
            val text = titleNodes[0].text?.toString()
            if (!text.isNullOrBlank()) {
                return text
            }
        }
        return null
    }

    data class SanitizedWebContext(
        val domain: String,
        val category: String,
        val sanitizedUrl: String,
        val displayTitle: String
    )

    /**
     * Privacy Sanitizer: Strips private paths, session tokens, and raw query parameters.
     * Retains only top-level domains and high-level productivity categories.
     */
    private fun sanitizeWebContext(rawText: String): SanitizedWebContext {
        val trimmed = rawText.trim()
        val isExplicitUrl = trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
                (trimmed.contains(".") && !trimmed.contains(" "))

        val host: String = if (isExplicitUrl) {
            val urlWithScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
            try {
                val uri = android.net.Uri.parse(urlWithScheme)
                uri.host?.lowercase() ?: "web"
            } catch (e: Exception) {
                "web"
            }
        } else {
            // Address bar raw search query -> Suppress raw text to protect user privacy
            "google.com"
        }

        val category = classifyDomainCategory(host)
        val displayTitle = when {
            !isExplicitUrl || host.contains("google.") || host.contains("bing.") || host.contains("duckduckgo.") -> {
                "Web Research ($category)"
            }
            category == "DEVELOPMENT" -> "Development & Engineering ($host)"
            category == "PRODUCTIVITY" -> "Productivity & Workspace ($host)"
            category == "COMMUNICATION" -> "Communication ($host)"
            category == "MEDIA_STREAMING" -> "Media & Entertainment ($host)"
            else -> "Browsing $host"
        }

        return SanitizedWebContext(
            domain = host,
            category = category,
            sanitizedUrl = "https://$host",
            displayTitle = displayTitle
        )
    }

    private fun classifyDomainCategory(host: String): String {
        return when {
            host.contains("github.com") || host.contains("gitlab.com") || host.contains("stackoverflow.com") ||
            host.contains("developer.android.com") || host.contains("localhost") || host.contains("leetcode.com") ||
            host.contains("npmjs.com") || host.contains("pypi.org") -> "DEVELOPMENT"

            host.contains("docs.google.com") || host.contains("notion.so") || host.contains("trello.com") ||
            host.contains("asana.com") || host.contains("jira") || host.contains("atlassian.net") ||
            host.contains("figma.com") || host.contains("overleaf.com") -> "PRODUCTIVITY"

            host.contains("slack.com") || host.contains("teams.microsoft.com") || host.contains("discord.com") ||
            host.contains("mail.google.com") || host.contains("outlook.") -> "COMMUNICATION"

            host.contains("youtube.com") || host.contains("netflix.com") || host.contains("spotify.com") ||
            host.contains("twitch.tv") || host.contains("soundcloud.com") -> "MEDIA_STREAMING"

            host.contains("wikipedia.org") || host.contains("medium.com") || host.contains("arxiv.org") ||
            host.contains("coursera.org") || host.contains("edx.org") || host.contains("scholar.google.") -> "RESEARCH_EDUCATION"

            host.contains("google.") || host.contains("bing.") || host.contains("duckduckgo.") -> "SEARCH"

            else -> "GENERAL_BROWSING"
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility sensing stream interrupted by system resource constraints.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility monitor successfully connected and active.")
    }
}

