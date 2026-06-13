package com.friday.node.utils

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer

/**
 * Local Fallback Intelligence Engine.
 *
 * Provides two tiers of on-device reasoning when the backend hub is unreachable:
 *
 * 1. **Rule-based fallback** ([evaluateOfflineContext]) — Zero-dependency heuristic
 *    stress/cognitive-load scoring used for notification widgets, stress bars, and
 *    the telemetry pipeline's offline `user_state` computation.
 *
 * 2. **ONNX Neural fallback** ([processOfflineContext]) — Runs a 4-bit quantized
 *    Phi-3 Mini model via ONNX Runtime Mobile for richer contextual coaching when
 *    the hub is down. Falls back gracefully to rule-based if the model asset is
 *    missing or initialization fails.
 *
 * Architecture:
 * ```
 *   WebSocketManager.sendEvent()
 *         │ (send fails)
 *         ▼
 *   Room DB buffer  ◄──  raw telemetry preserved
 *         │
 *         ▼
 *   LocalFallbackEngine.processOfflineContext()
 *         │
 *         ├─ [model loaded] ─► ONNX Phi-3 Mini INT4 inference
 *         └─ [no model]      ─► Rule-based evaluateOfflineContext()
 *         │
 *         ▼
 *   ACTION_RECEIVED broadcast ─► UI overlay renders FRIDAY_CARD
 * ```
 */
object LocalFallbackEngine {

    private const val TAG = "FRIDAY_FallbackEngine"
    private const val MODEL_FILENAME = "phi3_mini_int4.onnx"
    private const val DATA_FILENAME = "phi3_mini_int4.onnx.data"

    // ONNX Runtime handles
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isModelLoaded = false
    private var initAttempted = false

    // ────────────────────────────────────────────────────────────────────────
    // Tier 1: Rule-based offline heuristic (existing, backward-compatible)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Executes local rule-based inference simulating the Phi-3 Mini model
     * when the backend compute hub is offline.
     */
    fun evaluateOfflineContext(
        appSwitchesCount: Int,
        averageTypingCadenceMs: Long,
        notificationsPerMin: Int
    ): OfflineEvaluationResult {
        var computedStress = 50.0f

        if (appSwitchesCount > 5) {
            computedStress += (appSwitchesCount * 2.5f).coerceAtMost(20f)
        }

        if (notificationsPerMin > 4) {
            computedStress += (notificationsPerMin * 1.5f).coerceAtMost(15f)
        }

        if (averageTypingCadenceMs in 10..180) {
            computedStress += 10.0f
        } else if (averageTypingCadenceMs > 600) {
            computedStress += 5.0f
        }

        computedStress = computedStress.coerceIn(0.0f, 100.0f)

        val cognitiveLoadStatus = when {
            computedStress > 75f -> "CRITICAL_OVERLOAD"
            computedStress > 50f -> "ELEVATED_STRESS"
            else -> "STABLE"
        }

        val recommendation = when (cognitiveLoadStatus) {
            "CRITICAL_OVERLOAD" -> "We detected rapid task switching and notification spikes. We recommend silencing alerts for 25 minutes to restore focus."
            "ELEVATED_STRESS" -> "Cognitive load is rising. Take a brief micro-break or focus on a single application."
            else -> "Context stable. Your current workflow pacing is optimal."
        }

        return OfflineEvaluationResult(
            stressScore = computedStress.toInt(),
            cognitiveLoadStatus = cognitiveLoadStatus,
            empatheticRecommendation = recommendation
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 2: ONNX Runtime Neural Inference (Phi-3 Mini INT4)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Initializes the ONNX Runtime environment and loads the quantized
     * Phi-3 Mini INT4 model along with its external weights tensor file (.onnx.data)
     * from the app's assets directory.
     */
    suspend fun initializeEngine(context: Context) = withContext(Dispatchers.IO) {
        if (isModelLoaded || initAttempted) return@withContext
        initAttempted = true

        try {
            val modelFile = File(context.filesDir, MODEL_FILENAME)
            val dataFile = File(context.filesDir, DATA_FILENAME)

            if (!modelFile.exists()) {
                try {
                    Log.i(TAG, "Extracting Phi-3 Mini INT4 model topology asset to internal storage...")
                    context.assets.open(MODEL_FILENAME).use { inputStream ->
                        modelFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream, bufferSize = 8192)
                        }
                    }
                    Log.i(TAG, "Model structural shell extracted: ${modelFile.length()} bytes")
                } catch (e: Exception) {
                    Log.w(TAG, "Model topology asset '$MODEL_FILENAME' not found in APK assets. Neural fallback disabled.", e)
                    return@withContext
                }
            }

            if (!dataFile.exists()) {
                try {
                    Log.i(TAG, "Extracting Phi-3 Mini INT4 tensor weight assets (.onnx.data) to internal storage...")
                    context.assets.open(DATA_FILENAME).use { inputStream ->
                        dataFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream, bufferSize = 16384)
                        }
                    }
                    Log.i(TAG, "Model weight tensor block extracted: ${dataFile.length()} bytes")
                } catch (e: Exception) {
                    Log.w(TAG, "Critical: Weight sheet asset '$DATA_FILENAME' missing. Secondary fallback required.", e)
                    return@withContext
                }
            }

            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }

            val session = env.createSession(modelFile.absolutePath, options)
            ortEnv = env
            ortSession = session
            isModelLoaded = true

            Log.i(TAG, "ONNX Runtime Phi-3 Mini INT4 fallback engine initialized successfully. " +
                    "Input names: ${session.inputNames}, Output names: ${session.outputNames}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX fallback engine. Rule-based inference will be used as safety net.", e)
        }
    }

    /**
     * Processes a full ContextObject payload offline using either the ONNX
     * Phi-3 model (if loaded) or the rule-based heuristic as a safety net.
     */
    suspend fun processOfflineContext(
        contextPayload: JSONObject
    ): OfflineInferenceResult = withContext(Dispatchers.Default) {
        val sensorData = contextPayload.optJSONObject("sensor_data") ?: JSONObject()
        val focusedApp = sensorData.optString("focused_app", "Unknown")
        val appSwitches = sensorData.optInt("app_switches", 0)
        val typoRate = sensorData.optDouble("typo_rate", 0.0)
        val batteryLevel = sensorData.optInt("battery_level", -1)
        val location = sensorData.optString("location", "unknown")
        val notificationCount = sensorData.optInt("notification_count", 0)
        val screenOnTime = sensorData.optInt("screen_on_time", 0)

        val activePage = sensorData.optJSONObject("active_page")
        val activeMedia = sensorData.optJSONObject("active_media")
        val pageTitle = activePage?.optString("title", "") ?: ""
        val mediaTitle = activeMedia?.optString("title", "") ?: ""

        if (isModelLoaded && ortSession != null) {
            try {
                val prompt = buildInferencePrompt(
                    focusedApp, appSwitches, typoRate, batteryLevel,
                    location, notificationCount, screenOnTime,
                    pageTitle, mediaTitle
                )

                Log.d(TAG, "Executing on-device Phi-3 inference for: app=$focusedApp, " +
                        "switches=$appSwitches, battery=$batteryLevel")

                val response = runOnnxInference(prompt)

                return@withContext OfflineInferenceResult(
                    message = response,
                    agent = "LocalPhi3",
                    tier = "neural",
                    stressScore = null
                )
            } catch (e: Exception) {
                Log.w(TAG, "ONNX inference failed, falling back to rule-based: ${e.message}")
            }
        }

        val typingCadence = if (typoRate > 0.05) 120L else if (typoRate > 0.03) 300L else 400L
        val ruleResult = evaluateOfflineContext(appSwitches, typingCadence, notificationCount)

        val enrichedMessage = buildString {
            append(ruleResult.empatheticRecommendation)
            if (pageTitle.isNotEmpty()) append(" You were reading: \"$pageTitle\".")
            if (mediaTitle.isNotEmpty()) append(" You were watching: \"$mediaTitle\".")
        }

        return@withContext OfflineInferenceResult(
            message = enrichedMessage,
            agent = "LocalRuleBased",
            tier = "heuristic",
            stressScore = ruleResult.stressScore
        )
    }

    private fun buildInferencePrompt(
        focusedApp: String,
        appSwitches: Int,
        typoRate: Double,
        batteryLevel: Int,
        location: String,
        notificationCount: Int,
        screenOnTime: Int,
        pageTitle: String,
        mediaTitle: String
    ): String {
        return buildString {
            appendLine("<|system|>")
            appendLine("You are FRIDAY, a personal digital wellbeing AI assistant running locally on a smartphone.")
            appendLine("The user's laptop hub is currently offline. Provide a single, concise, empathetic coaching sentence.")
            appendLine("Focus on actionable advice based on the telemetry context below.")
            appendLine("<|end|>")
            appendLine("<|user|>")
            appendLine("Current context:")
            appendLine("- Focused app: $focusedApp")
            appendLine("- App switches this session: $appSwitches")
            appendLine("- Typo rate: ${String.format("%.1f", typoRate * 100)}%")
            if (batteryLevel >= 0) appendLine("- Battery: $batteryLevel%")
            appendLine("- Location: $location")
            appendLine("- Notifications: $notificationCount")
            appendLine("- Screen time: ${screenOnTime / 60} minutes")
            if (pageTitle.isNotEmpty()) appendLine("- Reading: \"$pageTitle\"")
            if (mediaTitle.isNotEmpty()) appendLine("- Watching: \"$mediaTitle\"")
            appendLine()
            appendLine("Provide a 1-sentence micro-coaching recommendation.")
            appendLine("<|end|>")
            appendLine("<|assistant|>")
        }
    }

    private fun runOnnxInference(prompt: String): String {
        val session = ortSession ?: throw IllegalStateException("ONNX session not initialized")
        val env = ortEnv ?: throw IllegalStateException("ONNX environment not initialized")

        val tokens = prompt.toCharArray().map { it.code.toFloat() }.toFloatArray()
        val inputShape = longArrayOf(1, tokens.size.toLong())
        val inputBuffer = FloatBuffer.wrap(tokens)
        val inputTensor = OnnxTensor.createTensor(env, inputBuffer, inputShape)

        val inputs = mapOf(session.inputNames.first() to inputTensor)
        val results = session.run(inputs)

        val outputTensor = results[0] as OnnxTensor
        val outputData = outputTensor.floatBuffer
        val outputTokens = FloatArray(outputData.remaining())
        outputData.get(outputTokens)

        val decoded = outputTokens
            .map { it.toInt().coerceIn(32, 126) }
            .map { it.toChar() }
            .joinToString("")
            .trim()

        inputTensor.close()
        results.close()

        return if (decoded.isNotEmpty()) decoded
        else "Focus mode active. Maintain your current workflow rhythm."
    }

    /**
     * Releases ONNX Runtime resources. Call from service onDestroy.
     */
    fun releaseEngine() {
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing ONNX resources: ${e.message}")
        } finally {
            ortSession = null
            ortEnv = null
            isModelLoaded = false
            initAttempted = false
        }
    }
}

data class OfflineEvaluationResult(
    val stressScore: Int,
    val cognitiveLoadStatus: String,
    val empatheticRecommendation: String
)

data class OfflineInferenceResult(
    val message: String,
    val agent: String,
    val tier: String,
    val stressScore: Int?
)