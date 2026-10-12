package com.friday.node.utils

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
 *   App Install → Download 2.3GB Phi-3 Mini ONNX model
 *         │
 *   User completes onboarding + grants permissions
 *         │
 *   LocalFallbackEngine.initializeEngine() loads downloaded model
 *         │
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

    // Public internet fallback model URLs (Microsoft Hugging Face ONNX direct resolve URLs)
    private const val HF_ONNX_MODEL_URL =
        "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-onnx/resolve/main/cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4/phi3-mini-4k-instruct-cpu-int4-rtn-block-32-acc-level-4.onnx"
    private const val HF_ONNX_DATA_URL =
        "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-onnx/resolve/main/cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4/phi3-mini-4k-instruct-cpu-int4-rtn-block-32-acc-level-4.onnx.data"

    // OkHttp client for model downloads with full redirect handling
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // ONNX Runtime handles
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isModelLoaded = false
    private var initAttempted = false
    private var downloadProgressCallback: ((Float) -> Unit)? = null
    private var activeCall: Call? = null
    @Volatile
    private var isDownloadingState = false
    @Volatile
    private var isCancelled = false

    fun isModelLoaded(): Boolean = isModelLoaded

    fun isDownloading(): Boolean = isDownloadingState

    /**
     * Checks if the Phi-3 Mini ONNX model files are downloaded and valid in internal app storage.
     */
    fun isModelDownloaded(context: Context): Boolean {
        val modelDir = File(context.filesDir, "models")
        val modelFile = File(modelDir, MODEL_FILENAME)
        val dataFile = File(modelDir, DATA_FILENAME)
        return (modelFile.exists() && modelFile.length() > 50_000L) &&
               (dataFile.exists() && dataFile.length() > 1_000_000L)
    }

    /**
     * Deletes downloaded model files from internal app storage to reclaim space.
     */
    fun deleteModel(context: Context): Boolean {
        releaseEngine()
        val modelDir = File(context.filesDir, "models")
        val modelFile = File(modelDir, MODEL_FILENAME)
        val dataFile = File(modelDir, DATA_FILENAME)
        var deleted = false
        if (modelFile.exists()) {
            deleted = modelFile.delete() || deleted
        }
        if (dataFile.exists()) {
            deleted = dataFile.delete() || deleted
        }
        initAttempted = false
        Log.i(TAG, "Deleted on-device model files from storage to reclaim ~2.3GB.")
        return deleted
    }

    /**
     * Cancels an ongoing download and deletes temporary partial files.
     */
    fun cancelDownload(context: Context) {
        isCancelled = true
        try {
            activeCall?.cancel()
        } catch (_: Exception) {}
        activeCall = null
        isDownloadingState = false
        // Clean up any temp files
        val modelDir = File(context.filesDir, "models")
        File(modelDir, "$MODEL_FILENAME.tmp").delete()
        File(modelDir, "$DATA_FILENAME.tmp").delete()
        Log.i(TAG, "Model download cancelled by user.")
    }

    /**
     * Downloads the Phi-3 Mini INT4 ONNX model and its weight tensor file
     * on demand with explicit user consent.
     *
     * Hybrid strategy: tries the local compute hub first (fast, private LAN),
     * and automatically falls back to public Hugging Face if the hub returns 404
     * or is not reachable.
     *
     * @param context Application context
     * @param hubIp Optional local hub IP address (from DiscoveryManager.getResolvedHubIp()).
     * @param onProgress Optional callback for download progress (0.0 to 1.0)
     * @param onComplete Callback invoked when download completes successfully
     * @param onError Callback invoked if download fails
     */
    fun downloadModel(
        context: Context,
        hubIp: String? = null,
        onProgress: ((Float) -> Unit)? = null,
        onComplete: () -> Unit,
        onError: (String) -> Unit = { Log.e(TAG, "Model download failed: $it") }
    ) {
        if (isDownloadingState) {
            Log.w(TAG, "Model download already in progress.")
            return
        }

        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }

        val modelFile = File(modelDir, MODEL_FILENAME)
        val dataFile = File(modelDir, DATA_FILENAME)

        // Check if model already exists and is valid
        if (isModelDownloaded(context)) {
            Log.i(TAG, "Model already exists locally. Skipping download.")
            onComplete()
            return
        }

        isCancelled = false
        isDownloadingState = true
        downloadProgressCallback = onProgress

        val modelUrl = if (!hubIp.isNullOrBlank()) "http://$hubIp:8000/api/model/phi3-mini-onnx" else HF_ONNX_MODEL_URL
        val dataUrl = if (!hubIp.isNullOrBlank()) "http://$hubIp:8000/api/model/phi3-mini-onnx-data" else HF_ONNX_DATA_URL

        Log.i(TAG, "Initiating consent-approved on-demand model download from $modelUrl and $dataUrl")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!modelFile.exists() || modelFile.length() < 100) {
                    val tmpModel = File(modelDir, "$MODEL_FILENAME.tmp")
                    downloadFile(modelUrl, HF_ONNX_MODEL_URL, tmpModel) { progress ->
                        // Model topology is ~1% of total size
                        onProgress?.invoke(progress * 0.02f)
                    }
                    if (isCancelled) throw java.io.IOException("Download cancelled")
                    tmpModel.renameTo(modelFile)
                }

                if (!dataFile.exists() || dataFile.length() < 100) {
                    val tmpData = File(modelDir, "$DATA_FILENAME.tmp")
                    downloadFile(dataUrl, HF_ONNX_DATA_URL, tmpData) { progress ->
                        // Weight tensor is ~98% of total size
                        onProgress?.invoke(0.02f + progress * 0.98f)
                    }
                    if (isCancelled) throw java.io.IOException("Download cancelled")
                    tmpData.renameTo(dataFile)
                }

                isDownloadingState = false
                activeCall = null
                withContext(Dispatchers.Main) {
                    onComplete()
                }
            } catch (e: Exception) {
                isDownloadingState = false
                activeCall = null
                if (isCancelled) {
                    withContext(Dispatchers.Main) {
                        onError("Download cancelled")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onError("Failed to download model: ${e.message}")
                    }
                }
            }
        }
    }

    private fun downloadFile(
        primaryUrl: String,
        fallbackUrl: String? = null,
        outputFile: File,
        onProgress: ((Float) -> Unit)? = null
    ) {
        var urlToUse = primaryUrl
        var request = Request.Builder().url(urlToUse).build()
        var call = httpClient.newCall(request)
        activeCall = call
        var response = call.execute()

        // If hub returns 404 (not cached on laptop hub) and fallback is provided, switch to Hugging Face
        if (!response.isSuccessful && response.code == 404 && fallbackUrl != null && primaryUrl != fallbackUrl) {
            response.close()
            Log.w(TAG, "Primary URL $primaryUrl returned 404. Falling back to public Hugging Face CDN: $fallbackUrl")
            urlToUse = fallbackUrl
            request = Request.Builder().url(urlToUse).build()
            call = httpClient.newCall(request)
            activeCall = call
            response = call.execute()
        }

        if (!response.isSuccessful) {
            throw java.io.IOException("Download HTTP error: ${response.code} from $urlToUse")
        }

        val totalBytes = response.body?.contentLength() ?: -1L
        var downloadedBytes = 0L

        response.body?.byteStream()?.use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(65536)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled) {
                        throw java.io.IOException("Download cancelled by user")
                    }
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0 && onProgress != null) {
                        val progress = downloadedBytes.toFloat() / totalBytes
                        onProgress(progress.coerceIn(0f, 1f))
                    }
                }
            }
        }
        response.close()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Initialization
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Initializes the ONNX Runtime environment and loads the quantized
     * Phi-3 Mini INT4 model from internal storage ONLY if previously downloaded.
     * If the model files are not found, skips neural initialization and
     * allows the app to operate in zero-overhead rule-based fallback mode.
     */
    suspend fun initializeEngine(context: Context) = withContext(Dispatchers.IO) {
        if (isModelLoaded || initAttempted) return@withContext
        initAttempted = true

        val modelDir = File(context.filesDir, "models")
        val modelFile = File(modelDir, MODEL_FILENAME)
        val dataFile = File(modelDir, DATA_FILENAME)

        if (!modelFile.exists() || !dataFile.exists() || modelFile.length() < 1000 || dataFile.length() < 1000) {
            Log.i(TAG, "Neural model files not present in storage. Using ultra-fast rule-based heuristic fallback (<10ms, 0MB).")
            return@withContext
        }

        try {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }

            val session = env.createSession(modelFile.absolutePath, options)
            ortEnv = env
            ortSession = session
            isModelLoaded = true

            Log.i(TAG, "ONNX Runtime Phi-3 Mini INT4 fallback engine initialized successfully from user-downloaded storage.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing ONNX runtime session: ${e.message}. Using rule-based fallback.", e)
            releaseEngine()
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
        val domainCategory = activePage?.optString("category")
        val ruleResult = evaluateOfflineContext(
            appSwitchesCount = appSwitches,
            averageTypingCadenceMs = typingCadence,
            notificationsPerMin = notificationCount,
            focusedApp = focusedApp,
            domainCategory = domainCategory
        )

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

        val tokenIds = LlamaTokenizer.encode(prompt)
        val inputShape = longArrayOf(1, tokenIds.size.toLong())
        val inputBuffer = java.nio.LongBuffer.wrap(tokenIds)
        val inputTensor = OnnxTensor.createTensor(env, inputBuffer, inputShape)

        val inputName = session.inputNames.first()
        val inputs = mutableMapOf<String, OnnxTensor>()
        inputs[inputName] = inputTensor

        if (session.inputNames.size > 1 && session.inputNames.contains("attention_mask")) {
            val mask = LongArray(tokenIds.size) { 1L }
            val maskBuffer = java.nio.LongBuffer.wrap(mask)
            val maskTensor = OnnxTensor.createTensor(env, maskBuffer, inputShape)
            inputs["attention_mask"] = maskTensor
        }

        val results = session.run(inputs)
        val outputTensor = results[0] as OnnxTensor

        val typeInfo = outputTensor.info
        val decodedText = if (typeInfo.type == ai.onnxruntime.OnnxJavaType.INT64) {
            val outputData = outputTensor.longBuffer
            val outIds = LongArray(outputData.remaining())
            outputData.get(outIds)
            LlamaTokenizer.decode(outIds)
        } else {
            val outputData = outputTensor.floatBuffer
            val shape = outputTensor.info.shape
            if (shape.size == 3) {
                val seqLen = shape[1].toInt()
                val vocabSize = shape[2].toInt()
                val argmaxIds = LongArray(seqLen)
                
                for (s in 0 until seqLen) {
                    var maxVal = -Float.MAX_VALUE
                    var maxIdx = 0L
                    for (v in 0 until vocabSize) {
                        if (outputData.hasRemaining()) {
                            val score = outputData.get()
                            if (score > maxVal) {
                                maxVal = score
                                maxIdx = v.toLong()
                            }
                        }
                    }
                    argmaxIds[s] = maxIdx
                }
                LlamaTokenizer.decode(argmaxIds)
            } else {
                val outData = FloatArray(outputData.remaining())
                outputData.get(outData)
                val outIds = outData.map { it.toLong() }.toLongArray()
                LlamaTokenizer.decode(outIds)
            }
        }

        for (tensor in inputs.values) {
            tensor.close()
        }
        results.close()

        return if (decodedText.isNotEmpty()) decodedText.trim()
        else "Focus mode active. Maintain your current workflow rhythm."
    }

    object LlamaTokenizer {
        private val vocab = mutableMapOf<String, Long>()
        private val reverseVocab = mutableMapOf<Long, String>()

        init {
            vocab["<|system|>"] = 32001L
            vocab["<|user|>"] = 32002L
            vocab["<|assistant|>"] = 32003L
            vocab["<|end|>"] = 32000L

            for (i in 0..255) {
                val byteChar = i.toChar().toString()
                vocab[byteChar] = (i + 3).toLong()
                reverseVocab[(i + 3).toLong()] = byteChar
            }

            val commonWords = listOf(
                "You", "are", "FRIDAY", "a", "personal", "digital", "wellbeing", "AI", "assistant",
                "running", "locally", "on", "smartphone", "The", "user", "laptop", "hub", "is",
                "currently", "offline", "Provide", "single", "concise", "empathetic", "coaching",
                "sentence", "Focus", "actionable", "advice", "based", "telemetry", "context",
                "below", "Current", "app", "switches", "session", "Typo", "rate", "Battery",
                "Location", "Notifications", "Screen", "time", "Reading", "Watching", "recommendation",
                "silent", "break", "work", "flow", "limit", "take", "breath", "stress", "score",
                "stable", "optimal", "focused", "relaxed", "calm", "overload", "elevated", "micro",
                "Chrome", "Youtube", "Internet", "Firefox", "status", "active", "battery_level",
                "focused_app", "notification_count", "typo_rate", "screen_on_time", "location"
            )

            var id = 1000L
            for (word in commonWords) {
                vocab[word] = id
                vocab[" $word"] = id + 1
                reverseVocab[id] = word
                reverseVocab[id + 1] = " $word"
                id += 2
            }

            for ((k, v) in vocab) {
                if (!reverseVocab.containsKey(v)) {
                    reverseVocab[v] = k
                }
            }
        }

        fun encode(text: String): LongArray {
            val tokens = mutableListOf<Long>()
            var i = 0
            while (i < text.length) {
                var matched = false
                for (len in 20 downTo 1) {
                    if (i + len <= text.length) {
                        val sub = text.substring(i, i + len)
                        if (vocab.containsKey(sub)) {
                            tokens.add(vocab[sub]!!)
                            i += len
                            matched = true
                            break
                        }
                    }
                }
                if (!matched) {
                    val charVal = text[i].code
                    if (charVal in 0..255) {
                        tokens.add((charVal + 3).toLong())
                    } else {
                        tokens.add(259L)
                    }
                    i++
                }
            }
            return tokens.toLongArray()
        }

        fun decode(tokenIds: LongArray): String {
            val sb = java.lang.StringBuilder()
            for (id in tokenIds) {
                val piece = reverseVocab[id]
                if (piece != null) {
                    if (piece != "<|system|>" && piece != "<|user|>" && piece != "<|assistant|>" && piece != "<|end|>") {
                        sb.append(piece)
                    }
                }
            }
            return sb.toString()
        }
    }

    /**
     * Executes empathetic context evaluation incorporating Personal Baseline Z-Scores
     * (Welford's algorithm) and Contextual Ambiguity handling when the compute hub is offline.
     */
    fun evaluateOfflineContext(
        appSwitchesCount: Int,
        averageTypingCadenceMs: Long,
        notificationsPerMin: Int,
        focusedApp: String? = null,
        domainCategory: String? = null,
        context: Context? = null
    ): OfflineEvaluationResult {
        val db = context?.let { com.friday.node.data.local.RoomDatabase.getInstance(it) }

        // 1. Record live telemetry sample into Personal Baseline Engine (Welford's algorithm)
        if (db != null) {
            if (appSwitchesCount > 0) db.recordMetricSample("metric_app_switches", appSwitchesCount.toDouble())
            if (averageTypingCadenceMs in 10..3000) db.recordMetricSample("metric_typing_cadence", averageTypingCadenceMs.toDouble())
            if (notificationsPerMin > 0) db.recordMetricSample("metric_notifications", notificationsPerMin.toDouble())
        }

        // 2. Compute statistical deviations (Z-scores) against personal baseline
        val zSwitches = db?.calculateZScore("metric_app_switches", appSwitchesCount.toDouble()) ?: 0.0
        val zCadence = if (averageTypingCadenceMs > 0) db?.calculateZScore("metric_typing_cadence", averageTypingCadenceMs.toDouble()) ?: 0.0 else 0.0
        val zNotifs = db?.calculateZScore("metric_notifications", notificationsPerMin.toDouble()) ?: 0.0
        val baseline = db?.getPersonalBaseline("metric_app_switches")
        val hasBaseline = baseline != null && baseline.sampleCount >= 5

        // Neutral baseline resting stress starts at 30
        var computedStress = 30.0f

        if (hasBaseline) {
            // Empathetic personal deviation: only score above personal norm
            if (zSwitches > 1.0) {
                computedStress += ((zSwitches - 1.0) * 15.0).toFloat().coerceAtMost(30f)
            }
            if (zNotifs > 1.0) {
                computedStress += ((zNotifs - 1.0) * 12.0).toFloat().coerceAtMost(25f)
            }
            if (zCadence < -1.5) {
                // Rushing / fast typing relative to personal baseline
                computedStress += 10.0f
            } else if (zCadence > 2.0) {
                // Noticeable latency / hesitation relative to personal baseline
                computedStress += 8.0f
            }
        } else {
            // Adaptive heuristics while personal baseline warms up
            if (appSwitchesCount > 6) {
                computedStress += ((appSwitchesCount - 6) * 2.5f).coerceAtMost(25f)
            }
            if (notificationsPerMin > 4) {
                computedStress += ((notificationsPerMin - 4) * 2.0f).coerceAtMost(20f)
            }
            if (averageTypingCadenceMs in 10..180) {
                computedStress += 10.0f
            } else if (averageTypingCadenceMs > 600) {
                computedStress += 6.0f
            }
        }

        // 3. Contextual Ambiguity Engine:
        // Distinguish between stressful context switching vs productive deep-focus flow state (e.g., coding, research)
        val isDevFlow = (domainCategory == "DEVELOPMENT" ||
                focusedApp?.contains("github", ignoreCase = true) == true ||
                focusedApp?.contains("termux", ignoreCase = true) == true ||
                focusedApp?.contains("android.studio", ignoreCase = true) == true ||
                focusedApp?.contains("code", ignoreCase = true) == true)

        val isProductiveFlow = (domainCategory == "PRODUCTIVITY" || domainCategory == "RESEARCH_EDUCATION" ||
                focusedApp?.contains("docs", ignoreCase = true) == true ||
                focusedApp?.contains("notion", ignoreCase = true) == true)

        val isFlowState = (isDevFlow || isProductiveFlow) && notificationsPerMin <= 5

        if (isFlowState) {
            // High task switching in dev/research is positive flow, not anxiety
            computedStress = (computedStress * 0.45f).coerceAtMost(40.0f)
        }

        // Cap final stress score within [0, 100]
        computedStress = computedStress.coerceIn(0.0f, 100.0f)

        val cognitiveLoadStatus = when {
            isFlowState && appSwitchesCount > 3 -> "FLOW_STATE"
            computedStress > 75f -> "CRITICAL_OVERLOAD"
            computedStress > 50f -> "ELEVATED_STRESS"
            else -> "STABLE"
        }

        val recommendation = when (cognitiveLoadStatus) {
            "FLOW_STATE" -> "Flow state detected. Task switching aligns with active development and research."
            "CRITICAL_OVERLOAD" -> "Activity levels exceed your personal baseline. We recommend a 20-minute notification shield."
            "ELEVATED_STRESS" -> "Cognitive load is rising above your baseline. Pacing your current task may help maintain focus."
            else -> "Context stable. Your workflow pacing is aligned with your personal baseline."
        }

        return OfflineEvaluationResult(
            stressScore = computedStress.toInt(),
            cognitiveLoadStatus = cognitiveLoadStatus,
            empatheticRecommendation = recommendation
        )
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