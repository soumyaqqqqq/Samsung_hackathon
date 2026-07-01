"""
FRIDAY - agents/decision.py
Generates response candidates via LLM and scores each one.
Enforces the RESPONSE_SCORE_THRESHOLD via the orchestrator.

Each candidate now carries dynamically computed action_quality and
intrusiveness scores instead of static dictionary look-ups.
"""

from __future__ import annotations

import logging
import time
from typing import Any

import httpx

from config import settings

logger = logging.getLogger("friday.agent.decision")


# ──────────────────────────────────────────────────────────────────────────────
# Dynamic scoring helpers
# ──────────────────────────────────────────────────────────────────────────────

def calculate_action_score(
    emotional_relevance: float,
    timing_context: float,
    memory_alignment: float,
    action_quality: float,
    intrusiveness_penalty: float,
    event_type: str
) -> float:
    """
    Dynamic evaluation score combining live telemetry and context signals.
    Enforces Empathetic Silence by prioritizing relevance for check-ins,
    and penalizing high timing/urgency context for non-check-in events.
    """
    if event_type == "STRESS_CHECK_IN":
        # Empathetic Silence: increase relevance weight, reduce timing and intrusive weights
        w_emotion = 0.40
        w_timing = 0.15
        w_memory = 0.15
        w_action = 0.20
        w_intrusive = 0.10
    else:
        # Standard weights
        w_emotion = 0.25
        w_timing = 0.20
        w_memory = 0.15
        w_action = 0.20
        w_intrusive = 0.20

    base_score = (
        emotional_relevance * w_emotion
        + timing_context * w_timing
        + memory_alignment * w_memory
        + action_quality * w_action
        + intrusiveness_penalty * w_intrusive
    )

    # Non-check-in events are penalized if timing_context is high (e.g. user is busy/fatigued)
    if event_type != "STRESS_CHECK_IN" and timing_context > 70.0:
        penalty_multiplier = 0.85
        base_score *= penalty_multiplier

    return round(base_score, 2)


def _compute_action_quality(
    response_type: str,
    stress_score: float,
    memory_alignment: float,
    has_active_task: bool,
) -> float:
    """
    Dynamically compute Action Quality (Q) for a candidate response.

    Q is a [0–100] score derived from:
      - How well the response type matches the user's current stress band
      - Whether memory context reinforces the suggestion
      - Whether there's an active task that the response supports
    """
    # Base quality by how specific the response type is
    type_specificity = {
        "SUGGEST_BREAK":        0.65,
        "SUGGEST_FOCUS":        0.70,
        "BATCH_NOTIFICATIONS":  0.60,
        "DEADLINE_APPROACHING": 0.75,
        "DEADLINE_URGENT":      0.85,
        "STRESS_CHECK_IN":      0.72,
        "MEMORY_PROMPT":        0.68,
        "LLM_GENERATED":        0.80,
        "GENERIC":              0.40,
        "DEADLINE_MISSED":      0.78,
    }
    base = type_specificity.get(response_type, 0.50) * 100

    # Stress-relevance bonus: high-stress responses score higher when stress is high
    stress_types = {"STRESS_CHECK_IN", "SUGGEST_BREAK", "LLM_GENERATED", "MEMORY_PROMPT"}
    if response_type in stress_types and stress_score >= 60:
        stress_bonus = (stress_score - 60) * 0.3  # up to +12 at stress=100
    elif response_type in {"SUGGEST_FOCUS", "DEADLINE_APPROACHING", "DEADLINE_URGENT"} and has_active_task:
        stress_bonus = 8.0  # task-relevant bonus
    else:
        stress_bonus = 0.0

    # Memory reinforcement: if memory aligns well, the action is more relevant
    memory_bonus = (memory_alignment / 100) * 10  # up to +10

    quality = min(base + stress_bonus + memory_bonus, 100.0)
    return round(quality, 1)


def _compute_intrusiveness(
    response_type: str,
    stress_score: float,
    notification_count: int,
    screen_on_seconds: int,
) -> float:
    """
    Dynamically compute Intrusiveness Penalty (P) for a candidate response.

    P is a [0–100] score where higher = more intrusive. Factors:
      - Inherent intrusiveness of the response type
      - User's current stress (interrupting stressed users is more intrusive)
      - Notification saturation (adding to an already overloaded queue is worse)
      - Session length (fatigue amplifies perceived intrusiveness)
    """
    # Base intrusiveness by response type
    type_base = {
        "SUGGEST_BREAK":        25.0,
        "SUGGEST_FOCUS":        40.0,
        "BATCH_NOTIFICATIONS":  35.0,
        "DEADLINE_APPROACHING": 45.0,
        "DEADLINE_URGENT":      60.0,
        "STRESS_CHECK_IN":      30.0,
        "MEMORY_PROMPT":        20.0,
        "LLM_GENERATED":        25.0,
        "GENERIC":              50.0,
        "DEADLINE_MISSED":      65.0,
    }
    base = type_base.get(response_type, 50.0)

    # Stress amplifier: interrupting a stressed user feels 10-20% more intrusive
    stress_factor = (stress_score / 100) * 15  # up to +15

    # Notification saturation: if the user already has many notifications,
    # adding another feels more intrusive
    notif_factor = min(notification_count * 1.5, 15.0)  # up to +15

    # Fatigue amplifier: after 2+ hours of screen time, everything feels louder
    screen_minutes = screen_on_seconds / 60
    fatigue_factor = min(max(screen_minutes - 120, 0) * 0.1, 10.0)  # up to +10

    intrusiveness = min(base + stress_factor + notif_factor + fatigue_factor, 100.0)
    return round(intrusiveness, 1)


# ──────────────────────────────────────────────────────────────────────────────
# Decision Agent
# ──────────────────────────────────────────────────────────────────────────────

class DecisionAgent:
    """
    Uses Ollama (Llama 3.1 8B) to generate empathetic response candidates,
    then dynamically scores and ranks them using live context signals.

    Falls back to rule-based candidates if LLM is unavailable.
    """

    async def run(
        self,
        ctx: dict,
        previous_results: dict[str, Any],
        db,
        chroma,
    ) -> dict[str, Any]:
        state  = ctx.get("user_state",  {})
        sensor = ctx.get("sensor_data", {})

        # Extract live signal values for dynamic scoring
        stress_score      = state.get("stress_score", 0)
        notification_count = sensor.get("notification_count", 0)
        screen_on_seconds  = sensor.get("screen_on_time", 0)
        has_active_task    = ctx.get("active_task") is not None
        memory_alignment   = previous_results.get("memory", {}).get("alignment_score", 50.0)

        # Collect candidate texts from upstream agents
        candidates: list[dict] = []

        # 1. Task suggestions — recompute quality/intrusiveness dynamically
        task_result = previous_results.get("task", {})
        for sug in task_result.get("suggestions", []):
            rtype = sug["type"]
            candidates.append({
                "text":           sug["text"],
                "type":           rtype,
                "action_quality": _compute_action_quality(rtype, stress_score, memory_alignment, has_active_task),
                "intrusiveness":  _compute_intrusiveness(rtype, stress_score, notification_count, screen_on_seconds),
                "agent":          "task",
            })

        # 2. Emotion summary → potential check-in message
        emotion_result = previous_results.get("emotion", {})
        emotion_summary = emotion_result.get("summary", "")
        if emotion_summary:
            candidates.append({
                "text":           emotion_summary,
                "type":           "STRESS_CHECK_IN",
                "action_quality": _compute_action_quality("STRESS_CHECK_IN", stress_score, memory_alignment, has_active_task),
                "intrusiveness":  _compute_intrusiveness("STRESS_CHECK_IN", stress_score, notification_count, screen_on_seconds),
                "agent":          "emotion",
            })

        # 3. Memory prompt (if strong memory match)
        mem_result = previous_results.get("memory", {})
        if mem_result.get("alignment_score", 0) >= 60:
            memories = mem_result.get("memories", [])
            if memories:
                top_mem = memories[0]
                top_mem_text = top_mem.get("text", "")
                top_metadata = top_mem.get("metadata") or {}

                prev_action = top_metadata.get("action")
                prev_location = top_metadata.get("location", "home")

                if not prev_action:
                    if "Action: " in top_mem_text:
                        prev_action = top_mem_text.split("Action: ")[-1].strip()

                if prev_action:
                    if "I remember" in prev_action:
                        mem_text = "I noticed similar stress levels earlier today. How are you holding up?"
                    else:
                        mem_text = f"Earlier when you were at {prev_location}, we tried: '{prev_action}'. Would you like to do that again?"
                else:
                    mem_text = "I noticed similar patterns in your activity earlier. How are you feeling?"

                candidates.append({
                    "text":           mem_text,
                    "type":           "MEMORY_PROMPT",
                    "action_quality": _compute_action_quality("MEMORY_PROMPT", stress_score, memory_alignment, has_active_task),
                    "intrusiveness":  _compute_intrusiveness("MEMORY_PROMPT", stress_score, notification_count, screen_on_seconds),
                    "agent":          "memory",
                })

        # 4. LLM-generated empathetic response (best effort)
        # Skip background LLM candidate generation for normal context shifts to save CPU resources
        is_stressed = stress_score >= 60 or emotion_result.get("emotion_label") in ["stressed", "anxious", "overwhelmed"]

        llm_candidate = None
        if is_stressed:
            llm_candidate = await self._llm_candidate(ctx, previous_results, stress_score, memory_alignment, has_active_task, notification_count, screen_on_seconds)
            if llm_candidate:
                candidates.append(llm_candidate)
        else:
            logger.info("Skipping background LLM candidate generation to preserve CPU (stress levels normal)")

        # 5. Guarantee at least one candidate
        if not candidates:
            candidates.append({
                "text":           "I'm here if you need anything.",
                "type":           "GENERIC",
                "action_quality": _compute_action_quality("GENERIC", stress_score, memory_alignment, has_active_task),
                "intrusiveness":  _compute_intrusiveness("GENERIC", stress_score, notification_count, screen_on_seconds),
                "agent":          "decision",
            })

        logger.info(f"DecisionAgent → {len(candidates)} candidates assembled")

        return {"candidates": candidates}

    # ──────────────────────────────────────────────────────────────────────────
    # LLM call
    # ──────────────────────────────────────────────────────────────────────────

    async def _llm_candidate(
        self,
        ctx: dict,
        previous_results: dict,
        stress_score: float,
        memory_alignment: float,
        has_active_task: bool,
        notification_count: int,
        screen_on_seconds: int,
    ) -> dict | None:
        """
        Call Ollama to generate a short, empathetic response.
        Returns a candidate dict or None on failure.
        Respects the Ollama concurrency lock and yields to voice commands.
        """

        state   = ctx.get("user_state",  {})
        sensor  = ctx.get("sensor_data", {})
        mem_ctx = previous_results.get("memory", {}).get("memory_context", "")
        task    = ctx.get("active_task")

        emotion = previous_results.get("emotion", {})

        stress_label = emotion.get(
        "emotion_label",
        state.get("emotion_label", "medium")
        )
        location     = sensor.get("location", "unknown location")
        task_desc    = task["description"] if task else "no active task"

        system_prompt = (
            "You are FRIDAY, an empathetic AI assistant embedded in a Samsung device. "
            "Your goal is to reduce cognitive load and support the user emotionally. "
            "Keep responses under 2 sentences. Be warm, concise, and non-intrusive. "
            "Never sound like a push notification. Sound like a thoughtful friend."
        )

        user_prompt = (
            f"User state: stress level is {stress_label}. "
            f"Location: {location}. "
            f"Active task: {task_desc}. "
            f"{mem_ctx}\n\n"
            "Generate one empathetic, helpful message for the user right now."
        )

        try:
            # Import concurrency controls — skip if voice command is active
            from main import _ollama_lock, _voice_active
            if _voice_active:
                logger.info("Skipping background LLM candidate — voice command has priority")
                return None

            async with _ollama_lock:
                async with httpx.AsyncClient(timeout=settings.LLM_TIMEOUT) as client:
                    resp = await client.post(
                        f"{settings.OLLAMA_BASE_URL}/api/generate",
                        json={
                            "model":  settings.FALLBACK_LLM_MODEL,
                            "prompt": f"<system>{system_prompt}</system>\n{user_prompt}",
                            "stream": False,
                            "options": {"temperature": 0.7, "num_predict": 80},
                        },
                    )
                    resp.raise_for_status()
                    text = resp.json().get("response", "").strip()

                    if text:
                        return {
                            "text":           text,
                            "type":           "LLM_GENERATED",
                            "action_quality": _compute_action_quality("LLM_GENERATED", stress_score, memory_alignment, has_active_task),
                            "intrusiveness":  _compute_intrusiveness("LLM_GENERATED", stress_score, notification_count, screen_on_seconds),
                            "agent":          "llm",
                        }

        except httpx.TimeoutException:
            logger.warning("LLM call timed out — using rule-based candidates only")
        except Exception as exc:
            logger.warning(f"LLM call failed: {exc}")

        return None
