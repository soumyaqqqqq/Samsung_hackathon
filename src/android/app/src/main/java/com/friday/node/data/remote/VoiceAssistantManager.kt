package com.friday.node.data.remote

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

enum class VoiceState {
    IDLE,
    RECORDING,
    TRANSCRIBING,
    RESPONDING,
    ERROR
}

class VoiceAssistantManager(private val context: Context) {
    private val TAG = "FRIDAY_VoiceAssistant"
    
    var state = VoiceState.IDLE
        private set
        
    var lastTranscript = ""
        private set
        
    var lastResponse = ""
        private set
        
    var lastError = ""
        private set

    // Callbacks for UI updates
    var onStateChanged: ((VoiceState) -> Unit)? = null
    var onResultReceived: ((transcript: String, response: String) -> Unit)? = null
    var onErrorOccurred: ((String) -> Unit)? = null

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var webSocket: WebSocket? = null
    
    private val client = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()


    fun startRecording() {
        if (state != VoiceState.IDLE) return
        
        lastError = ""
        lastTranscript = ""
        lastResponse = ""
        
        // 1. Prepare file
        audioFile = File(context.cacheDir, "friday_voice_temp.aac")
        if (audioFile?.exists() == true) {
            audioFile?.delete()
        }

        // 2. Initialize MediaRecorder
        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setOutputFile(audioFile!!.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            updateState(VoiceState.RECORDING)
            Log.i(TAG, "Audio recording started: ${audioFile!!.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder: ${e.message}", e)
            handleFailure("Microphone initialization failed: ${e.localizedMessage}")
        }
    }

    fun stopRecordingAndTranscribe() {
        if (state != VoiceState.RECORDING) return
        
        // Stop recording
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            Log.i(TAG, "Audio recording stopped successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop MediaRecorder: ${e.message}", e)
            mediaRecorder = null
        }

        val file = audioFile
        if (file == null || !file.exists() || file.length() == 0L) {
            handleFailure("Audio recording was empty.")
            return
        }

        // Connect and send
        updateState(VoiceState.TRANSCRIBING)
        connectAndStreamAudio(file)
    }

    fun sendTextQuery(queryText: String) {
        if (state != VoiceState.IDLE) return
        
        lastError = ""
        lastTranscript = ""
        lastResponse = ""
        
        updateState(VoiceState.TRANSCRIBING)
        
        val voiceUrl = WebSocketManager.getInstance().getVoiceWebSocketUrl()
        if (voiceUrl == null) {
            handleFailure("Not connected to FRIDAY Hub. Check network.")
            return
        }

        Log.i(TAG, "Connecting to voice socket for text query: $voiceUrl")
        val request = Request.Builder().url(voiceUrl).build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Voice WebSocket connection opened for text query.")
                
                // 1. Send start control message specifying format text
                val startMsg = JSONObject().apply {
                    put("type", "voice_start")
                    put("format", "text")
                }
                webSocket.send(startMsg.toString())
                
                // 2. Send end control message carrying the text query
                val endMsg = JSONObject().apply {
                    put("type", "voice_end")
                    put("text", queryText)
                }
                webSocket.send(endMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.i(TAG, "Received text query response: $text")
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")
                    if (type == "VOICE_TRANSCRIPTION") {
                        val error = json.optString("error", "")
                        if (error.isNotEmpty()) {
                            handleFailure("Error: $error")
                        } else {
                            lastTranscript = json.optString("text", "")
                            lastResponse = json.optString("response", "")
                            
                            updateState(VoiceState.RESPONDING)
                            onResultReceived?.invoke(lastTranscript, lastResponse)
                        }
                    } else if (type == "VOICE_ERROR") {
                        val error = json.optString("error", "")
                        handleFailure("Hub error: $error")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message: ${e.message}")
                    handleFailure("Invalid backend format.")
                } finally {
                    webSocket?.close(1000, "Normal closure")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Voice WebSocket failure: ${t.message}", t)
                handleFailure("Failed to connect to Voice Engine: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Voice WebSocket closed: $code / $reason")
            }
        })
    }

    private fun connectAndStreamAudio(file: File) {
        val voiceUrl = WebSocketManager.getInstance().getVoiceWebSocketUrl()
        if (voiceUrl == null) {
            handleFailure("Not connected to FRIDAY Hub. Check network.")
            return
        }

        Log.i(TAG, "Connecting to voice socket: $voiceUrl")
        val request = Request.Builder().url(voiceUrl).build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Voice WebSocket connection opened.")
                
                // 1. Send start control message
                val startMsg = JSONObject().apply {
                    put("type", "voice_start")
                    put("format", "aac")
                }
                webSocket.send(startMsg.toString())
                
                // 2. Read file bytes and send binary
                try {
                    val bytes = file.readBytes()
                    Log.i(TAG, "Streaming audio payload size: ${bytes.size} bytes")
                    webSocket.send(bytes.toByteString())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read/send audio bytes: ${e.message}")
                    webSocket.close(1011, "Failed to read audio file")
                    handleFailure("Failed to send audio data.")
                    return
                }

                // 3. Send end control message
                val endMsg = JSONObject().apply {
                    put("type", "voice_end")
                }
                webSocket.send(endMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.i(TAG, "Received voice response: $text")
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")
                    if (type == "VOICE_TRANSCRIPTION") {
                        val error = json.optString("error", "")
                        if (error.isNotEmpty()) {
                            handleFailure("Transcription error: $error")
                        } else {
                            lastTranscript = json.optString("text", "")
                            lastResponse = json.optString("response", "")
                            
                            updateState(VoiceState.RESPONDING)
                            onResultReceived?.invoke(lastTranscript, lastResponse)
                        }
                    } else if (type == "VOICE_ERROR") {
                        val error = json.optString("error", "")
                        handleFailure("Hub error: $error")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message: ${e.message}")
                    handleFailure("Invalid backend format.")
                } finally {
                    webSocket?.close(1000, "Normal closure")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Voice WebSocket failure: ${t.message}", t)
                handleFailure("Failed to connect to Voice Engine: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Voice WebSocket closed: $code / $reason")
            }
        })
    }

    fun cancel() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {}
        mediaRecorder = null
        
        webSocket?.close(1000, "Canceled by user")
        webSocket = null
        
        updateState(VoiceState.IDLE)
    }

    private fun updateState(newState: VoiceState) {
        state = newState
        onStateChanged?.invoke(newState)
    }

    private fun handleFailure(msg: String) {
        lastError = msg
        updateState(VoiceState.ERROR)
        onErrorOccurred?.invoke(msg)
    }
}
