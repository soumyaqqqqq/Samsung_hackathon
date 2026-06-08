package com.friday.node.utils

import android.content.Context
import org.json.JSONObject

object LocalFallbackEngine {
    
    /**
     * Executes local rule-based inference simulating the Phi-3 Mini model
     * when the backend compute hub is offline.
     */
    fun evaluateOfflineContext(
        appSwitchesCount: Int,
        averageTypingCadenceMs: Long,
        notificationsPerMin: Int
    ): OfflineEvaluationResult {
        // Base stress is 50
        var computedStress = 50.0f
        
        // App switching indicates distraction/multitasking load
        if (appSwitchesCount > 5) {
            computedStress += (appSwitchesCount * 2.5f).coerceAtMost(20f)
        }
        
        // Notification frequency increases cognitive friction
        if (notificationsPerMin > 4) {
            computedStress += (notificationsPerMin * 1.5f).coerceAtMost(15f)
        }
        
        // Fast typing (< 150ms delay) combined with high switches indicates pressure
        if (averageTypingCadenceMs in 10..180) {
            computedStress += 10.0f
        } else if (averageTypingCadenceMs > 600) {
            // Very slow typing may indicate fatigue/exhaustion
            computedStress += 5.0f
        }

        // Cap stress score between 0 and 100
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
}

data class OfflineEvaluationResult(
    val stressScore: Int,
    val cognitiveLoadStatus: String,
    val empatheticRecommendation: String
)
