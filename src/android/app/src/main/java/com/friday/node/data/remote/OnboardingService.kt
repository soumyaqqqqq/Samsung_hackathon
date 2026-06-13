package com.friday.node.data.remote

import android.content.Context
import android.util.Log
import com.friday.node.config.OnboardingConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Service to notify backend about onboarding completion using OkHttp
 */
class OnboardingService(
    private val context: Context,
    private val configManager: OnboardingConfigManager
) {
    private val TAG = "OnboardingService"
    private val client = OkHttpClient()

    /**
     * Notify backend that onboarding is complete
     * Send enabled modules information
     */
    suspend fun notifyBackendOfCompletion(deviceId: String) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Notifying backend of onboarding completion")
                
                val baseHttpUrl = WebSocketManager.getInstance().getServerHttpUrl()
                if (baseHttpUrl == null) {
                    Log.w(TAG, "Server HTTP URL not available (Hub not discovered/connected yet). Skipping backend notification.")
                    return@withContext
                }
                
                val targetUrl = "$baseHttpUrl/api/onboarding/complete"
                Log.d(TAG, "Target backend URL: $targetUrl")
                
                val payload = JSONObject().apply {
                    put("device_id", deviceId)
                    put("timestamp", System.currentTimeMillis())
                    put("onboarding_version", "1.0")
                    
                    // Add enabled modules
                    val enabledModules = configManager.getEnabledModules()
                    put("modules_enabled", enabledModules.size)
                    
                    val modulesArray = JSONArray()
                    enabledModules.forEach { moduleName ->
                        modulesArray.put(moduleName)
                    }
                    put("enabled_modules", modulesArray)
                    
                    // Add configuration
                    val config = configManager.getConfiguration()
                    val configJson = JSONObject().apply {
                        config.forEach { (key, value) ->
                            put(key, value)
                        }
                    }
                    put("configuration", configJson)
                }
                
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = payload.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url(targetUrl)
                    .header("ngrok-skip-browser-warning", "true")
                    .post(body)
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Failed to notify backend: Server returned code ${response.code}")
                    } else {
                        Log.i(TAG, "Backend notified of onboarding completion successfully")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify backend of onboarding completion: ${e.message}")
                // Don't crash - onboarding is still complete even if backend notification fails
            }
        }
    }
}
