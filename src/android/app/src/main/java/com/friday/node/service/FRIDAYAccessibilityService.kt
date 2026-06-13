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
                if (currentPackage.isNotEmpty()) {
                    trackBrowserOrYoutubeActivity(currentPackage)
                }
            }
        }
    }

    private fun trackBrowserOrYoutubeActivity(packageName: String) {
        val now = System.currentTimeMillis()
        if (now - lastTrackTime < 1000L) return
        lastTrackTime = now

        try {
            if (packageName != "com.android.chrome" && packageName != "com.sec.android.app.sbrowser" && 
                packageName != "org.mozilla.firefox" && packageName != "com.google.android.youtube") {
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
                    val finalUrl = cleanAndFormatUrlOrQuery(extracted)
                    val query = parseSearchQuery(finalUrl)
                    val title = if (query != null) "Search: $query" else "Active Webpage"
                    
                    val activePageObj = JSONObject().apply {
                        put("url", finalUrl)
                        put("title", title)
                        put("timestamp", now)
                    }
                    val changed = (finalUrl != lastRecordedUrl)
                    ContextObjectBuilder.activePage = activePageObj
                    if (changed) {
                        lastRecordedUrl = finalUrl
                        Log.d(TAG, "Extracted browser URL changed: $finalUrl ($title)")
                        sendBroadcast(android.content.Intent("com.friday.node.TRIGGER_CONTEXT_PUSH"))
                    }
                    
                    // Parse YouTube details if url is a YouTube link
                    if (finalUrl.contains("youtube.com/watch") || finalUrl.contains("youtu.be/")) {
                        val uri = android.net.Uri.parse(finalUrl)
                        val videoId = uri.getQueryParameter("v") ?: finalUrl.substringAfter("youtu.be/").substringBefore("?")
                        val timeParam = uri.getQueryParameter("t")
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
                                put("title", title)
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
                    val searchUrl = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(title, "UTF-8")
                    val activePageObj = JSONObject().apply {
                        put("url", searchUrl)
                        put("title", "YouTube: $title")
                        put("timestamp", now)
                    }
                    val pageChanged = (searchUrl != lastRecordedUrl)
                    ContextObjectBuilder.activePage = activePageObj
                    if (pageChanged) {
                        lastRecordedUrl = searchUrl
                    }
                    
                    val activeMediaObj = JSONObject().apply {
                        put("provider", "youtube")
                        put("video_id", "")
                        put("title", title)
                        put("playback_timestamp_seconds", 0)
                        put("timestamp", now)
                    }
                    val mediaChanged = (title != lastRecordedMediaTitle)
                    ContextObjectBuilder.activeMedia = activeMediaObj
                    if (mediaChanged || pageChanged) {
                        lastRecordedMediaTitle = title
                        Log.d(TAG, "Extracted YouTube App title changed: $title")
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

        // 3. Fallback: traverse tree
        return searchTreeForUrl(rootNode)
    }

    private fun searchTreeForUrl(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) {
            val trimmed = text.trim()
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || 
                trimmed.contains("www.") || trimmed.contains(".com") || trimmed.contains(".org") || trimmed.contains(".net")) {
                return trimmed
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchTreeForUrl(child)
            if (found != null) {
                return found
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
        return searchTreeForYoutubeTitle(rootNode)
    }

    private fun searchTreeForYoutubeTitle(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && node.className == "android.widget.TextView") {
            if (text.length in 10..100 && !text.contains("Subscriber") && !text.contains("views") && !text.contains("likes")) {
                return text
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchTreeForYoutubeTitle(child)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun cleanAndFormatUrlOrQuery(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        if (trimmed.contains(".") && !trimmed.contains(" ")) {
            return "https://$trimmed"
        }
        return try {
            val encodedQuery = java.net.URLEncoder.encode(trimmed, "UTF-8")
            "https://www.google.com/search?q=$encodedQuery"
        } catch (e: Exception) {
            "https://www.google.com"
        }
    }

    private fun parseSearchQuery(url: String): String? {
        return try {
            val uri = android.net.Uri.parse(url)
            uri.getQueryParameter("q")
        } catch (e: Exception) {
            null
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

