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
from functools import lru_cache
import hashlib

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
    "DEADLINE_MISSED":       (80.0, 70.0),
}


class DecisionAgent:
    """
    Uses Ollama (Llama 3.1 8B) to generate empathetic response candidates,
    then scores and ranks them.

    Falls back to rule-based candidates if LLM is unavailable.
    """

    def __init__(self):
        # OPTIMIZATION #2: Response caching
        self._response_cache = {}
        self._cache_timestamps = {}

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

                aq, intr  = _RESPONSE_PROFILES["MEMORY_PROMPT"]
                candidates.append({
                    "text":           mem_text,
                    "type":           "MEMORY_PROMPT",
                    "action_quality": aq,
                    "intrusiveness":  intr,
                    "agent":          "memory",
                })

        # 4. LLM-generated empathetic response (best effort)
        # OPTIMIZATION #10: Only call LLM for high stress or low confidence
        stress_score = emotion_result.get("stress_score", 0)
        best_rule_based_score = max([c.get("action_quality", 0) for c in candidates], default=0)
        
        should_call_llm = (
            stress_score >= settings.LLM_STRESS_THRESHOLD or
            best_rule_based_score < settings.LLM_CONFIDENCE_THRESHOLD
        )
        
        if should_call_llm:
            import hashlib, time, asyncio
            cache_key = hashlib.md5(f"{int(stress_score)}{sensor.get('location', 'home')}".encode()).hexdigest()
            if cache_key in self._response_cache and time.time() - self._cache_timestamps.get(cache_key, 0) < settings.LLM_CACHE_TTL_SECONDS:
                llm_candidate = await self._llm_candidate(ctx, previous_results)
                if llm_candidate:
                    candidates.append(llm_candidate)
            else:
                asyncio.create_task(self._llm_candidate(ctx, previous_results))
                logger.info("Fired LLM candidate generation in background for next time")
        else:
            logger.debug(f"Skipping LLM (stress={stress_score}, confidence={best_rule_based_score})")

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
    # LLM call with caching & conditional execution
    # ──────────────────────────────────────────────────────────────────────────

    async def _llm_candidate(
        self,
        ctx: dict,
        previous_results: dict,
    ) -> dict | None:
        """
        Call Ollama to generate a short, empathetic response.
        OPTIMIZATION #2: Uses response caching by stress + location
        OPTIMIZATION #8: Reduced token prediction & lower temperature
        Returns a candidate dict or None on failure.
        """
        import time
        import random
        
        # OPTIMIZATION #3: Probabilistic Sampling (Skip LLM 60% of time)
        if random.random() >= 0.4:
            logger.info("Skipping LLM due to probabilistic sampling (60% skip rate)")
            return None
        
        state   = ctx.get("user_state",  {})
        sensor  = ctx.get("sensor_data", {})
        
        # OPTIMIZATION #2: Create cache key from stress state + location
        stress_score = state.get("stress_score", 0)
        location = sensor.get("location", "home")
        cache_key = hashlib.md5(
            f"{int(stress_score)}{location}".encode()
        ).hexdigest()
        
        # Check cache and TTL
        current_time = time.time()
        if cache_key in self._response_cache:
            cached_time = self._cache_timestamps.get(cache_key, 0)
            if current_time - cached_time < settings.LLM_CACHE_TTL_SECONDS:
                logger.info(f"Cache hit for stress={stress_score}, location={location}")
                return self._response_cache[cache_key]
        
        mem_ctx = previous_results.get("memory", {}).get("memory_context", "")[:150]
        task    = ctx.get("active_task")
        emotion = previous_results.get("emotion", {})

        stress_label = emotion.get(
            "emotion_label",
            state.get("emotion_label", "medium")
        )
        task_desc = task["description"][:50] if task else "no active task"

        system_prompt = (
            "You are FRIDAY, an empathetic AI assistant. "
            "Keep response under 2 sentences. Be warm and concise."
        )

        user_prompt = (
            f"Stress: {stress_label}. Location: {location}. Task: {task_desc}. "
            f"Message: {mem_ctx[:100]}"
        )

        try:
            async with httpx.AsyncClient(timeout=settings.LLM_TIMEOUT) as client:
                resp = await client.post(
                    f"{settings.OLLAMA_BASE_URL}/api/generate",
                    json={
                        "model":  settings.PRIMARY_LLM_MODEL,
                        "prompt": f"<system>{system_prompt}</system>\n{user_prompt}",
                        "stream": False,
                        "options": {
                            "temperature": 0.5,
                            "num_predict": settings.LLM_MAX_TOKENS,
                            "top_k": 20,
                            "top_p": 0.8,
                        },
                    },
                )
            resp.raise_for_status()
            text = resp.json().get("response", "").strip()

            if text:
                aq, intr = _RESPONSE_PROFILES["STRESS_CHECK_IN"]
                result = {
                    "text":           text,
                    "type":           "LLM_GENERATED",
                    "action_quality": aq + 10,
                    "intrusiveness":  intr - 5,
                    "agent":          "llm",
                }
                
                self._response_cache[cache_key] = result
                self._cache_timestamps[cache_key] = current_time
                
                if len(self._response_cache) > settings.LLM_RESPONSE_CACHE_SIZE:
                    oldest_key = min(self._cache_timestamps, key=self._cache_timestamps.get)
                    del self._response_cache[oldest_key]
                    del self._cache_timestamps[oldest_key]
                    logger.debug(f"Cache evicted oldest entry (size={len(self._response_cache)})")
                
                return result

        except httpx.TimeoutException:
            logger.warning(f"LLM call timed out ({settings.LLM_TIMEOUT}s) — using rule-based candidates only")
        except Exception as exc:
            logger.warning(f"LLM call failed: {exc}")

        return None
