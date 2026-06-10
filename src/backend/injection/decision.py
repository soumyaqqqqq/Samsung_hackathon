"""
FRIDAY - agents/decision.py
Generates response candidates via LLM and scores each one.
Enforces the RESPONSE_SCORE_THRESHOLD via the orchestrator.
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from config import settings

logger = logging.getLogger("friday.agent.decision")

# Response type → (base_action_quality, base_intrusiveness)
_RESPONSE_PROFILES: dict[str, tuple[float, float]] = {

    "SUGGEST_BREAK":         (65.0, 30.0),
    "SUGGEST_FOCUS":         (70.0, 45.0),
    "BATCH_NOTIFICATIONS":   (75.0, 40.0),
    "DEADLINE_APPROACHING":  (78.0, 50.0),
    "DEADLINE_URGENT":       (90.0, 65.0),
    "STRESS_CHECK_IN":       (72.0, 35.0),
    "MEMORY_PROMPT":         (68.0, 25.0),
    "LLM_GENERATED":         (80.0, 30.0),
    "GENERIC":               (60.0, 50.0),
}


class DecisionAgent:
    """
    Uses Ollama (Llama 3.1 8B) to generate empathetic response candidates,
    then scores and ranks them.

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

        # Collect candidate texts from upstream agents
        candidates: list[dict] = []

        # 1. Task suggestions already scored
        task_result = previous_results.get("task", {})
        for sug in task_result.get("suggestions", []):
            candidates.append({
                "text":           sug["text"],
                "type":           sug["type"],
                "action_quality": sug.get("action_quality", 60.0),
                "intrusiveness":  sug.get("intrusiveness",  50.0),
                "agent":          "task",
            })

        # 2. Emotion summary → potential check-in message
        emotion_result = previous_results.get("emotion", {})
        emotion_summary = emotion_result.get("summary", "")
        if emotion_summary:
            aq, intr = _RESPONSE_PROFILES["STRESS_CHECK_IN"]
            candidates.append({
                "text":           emotion_summary,
                "type":           "STRESS_CHECK_IN",
                "action_quality": aq,
                "intrusiveness":  intr,
                "agent":          "emotion",
            })

        # 3. Memory prompt (if strong memory match)
        mem_result = previous_results.get("memory", {})
        if mem_result.get("alignment_score", 0) >= 60:
            memories = mem_result.get("memories", [])
            if memories:
                top_mem   = memories[0].get("text", "")
                mem_text  = f"I remember something similar — {top_mem[:120]}. Ring a bell?"
                aq, intr  = _RESPONSE_PROFILES["MEMORY_PROMPT"]
                candidates.append({
                    "text":           mem_text,
                    "type":           "MEMORY_PROMPT",
                    "action_quality": aq,
                    "intrusiveness":  intr,
                    "agent":          "memory",
                })

        # 4. LLM-generated empathetic response (best effort)
        llm_candidate = await self._llm_candidate(ctx, previous_results)
        if llm_candidate:
            candidates.append(llm_candidate)

        # 5. Guarantee at least one candidate
        if not candidates:
            aq, intr = _RESPONSE_PROFILES["GENERIC"]
            candidates.append({
                "text":           "I'm here if you need anything.",
                "type":           "GENERIC",
                "action_quality": aq,
                "intrusiveness":  intr,
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
    ) -> dict | None:
        """
        Call Ollama to generate a short, empathetic response.
        Returns a candidate dict or None on failure.
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
            async with httpx.AsyncClient(timeout=8.0) as client:
                resp = await client.post(
                    f"{settings.OLLAMA_BASE_URL}/api/generate",
                    json={
                        "model":  settings.PRIMARY_LLM_MODEL,
                        "prompt": f"<system>{system_prompt}</system>\n{user_prompt}",
                        "stream": False,
                        "options": {"temperature": 0.7, "num_predict": 80},
                    },
                )
            resp.raise_for_status()
            text = resp.json().get("response", "").strip()

            if text:
                aq, intr = _RESPONSE_PROFILES["STRESS_CHECK_IN"]
                return {
                    "text":           text,
                    "type":           "LLM_GENERATED",
                    "action_quality": aq + 10,   # LLM responses get a slight bonus
                    "intrusiveness":  intr - 5,
                    "agent":          "llm",
                }

        except httpx.TimeoutException:
            logger.warning("LLM call timed out — using rule-based candidates only")
        except Exception as exc:
            logger.warning(f"LLM call failed: {exc}")

        return None
