package com.friday.node.utils

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

class WakeWordEngine(private val context: Context) {
    private val TAG = "FRIDAY_WakeWordEngine"
    private val isListening = AtomicBoolean(false)
    private var audioThread: Thread? = null
    private var onDetectedCallback: (() -> Unit)? = null

    // Broadcast receiver to allow simulation of wake-word detection via ADB
    private val simulateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == "com.friday.node.SIMULATE_WAKE_WORD") {
                Log.i(TAG, "Simulated wake-word received via broadcast.")
                onDetectedCallback?.invoke()
            }
        }
    }

    fun startListening(onDetected: () -> Unit) {
        if (isListening.get()) return
        isListening.set(true)
        onDetectedCallback = onDetected

        // Register broadcast receiver for simulation/testing
        val filter = IntentFilter("com.friday.node.SIMULATE_WAKE_WORD")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(simulateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(simulateReceiver, filter)
        }

        // Start low-power Voice Activity Detection thread
        audioThread = Thread {
            runAudioRecordLoop()
        }.apply {
            name = "friday-wakeword-listening"
            priority = Thread.NORM_PRIORITY
            start()
        }
        Log.i(TAG, "WakeWordEngine started listening.")
    }

    fun stopListening() {
        if (!isListening.get()) return
        isListening.set(false)
        try {
            context.unregisterReceiver(simulateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister receiver: ${e.message}")
        }
        audioThread?.interrupt()
        audioThread = null
        Log.i(TAG, "WakeWordEngine stopped listening.")
    }

    private fun runAudioRecordLoop() {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size for AudioRecord")
            return
        }

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Record audio permission not granted. WakeWordEngine microphone loop will not start. Use broadcast simulation for testing.")
            return
        }

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception initializing AudioRecord: ${e.message}")
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord state was not initialized.")
            audioRecord.release()
            return
        }

        try {
            audioRecord.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord recording: ${e.message}")
            audioRecord.release()
            return
        }

        val buffer = ShortArray(bufferSize / 2)
        var voiceFramesCount = 0
        val thresholdDb = 50.0 // Responsive threshold for detecting speech presence (VAD)

        while (isListening.get() && !Thread.currentThread().isInterrupted) {
            val readSize = audioRecord.read(buffer, 0, buffer.size)
            if (readSize > 0) {
                // Compute Root Mean Square (RMS) of amplitude
                var sum = 0.0
                for (i in 0 until readSize) {
                    sum += buffer[i] * buffer[i]
                }
                val rms = sqrt(sum / readSize)
                
                // Convert RMS to decibels
                val db = if (rms > 0.0) 20.0 * log10(rms) else 0.0

                // When speech volume exceeds threshold (e.g. user speaking)
                if (db > thresholdDb) {
                    voiceFramesCount++
                    // Trigger if we detect speech activity across 2 frames (~160ms) to avoid single-pop false triggers
                    if (voiceFramesCount >= 2) { 
                        Log.i(TAG, "Voice Activity Detected! (calculated volume: ${db.toInt()} dB)")
                        voiceFramesCount = 0
                        onDetectedCallback?.invoke()
                        
                        // Briefly sleep to avoid double triggers
                        try {
                            Thread.sleep(2000)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                } else {
                    // Decay voice counter slowly to handle brief pauses between syllables
                    if (voiceFramesCount > 0) {
                        voiceFramesCount--
                    }
                }
            }
            // Yield CPU to maintain battery efficiency
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                break
            }
        }

        try {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
            audioRecord.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord: ${e.message}")
        }
    }
}
