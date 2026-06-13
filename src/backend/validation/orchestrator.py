"""
FRIDAY - orchestrator.py
Context interpretation, agent routing, response assembly.

This is the "backend brain". It:
  1. Reads the ContextObject payload.
  2. Determines which condition applies (stress / notif overload / task / …).
  3. Lazy-loads the required agent chain.
  4. Runs agents in order, collecting intermediate results.
  5. Builds a final scored response.
  6. Delivers UICommand to any connected Laptop WebSocket.
  7. Stores the decision in SQLite KPI log.
  8. Handles RLHF feedback to adjust scoring weights.
"""

from __future__ import annotations

import asyncio
import importlib
import logging
import time
import uuid
from typing import Any, Optional

from config import (
    AGENT_REGISTRY,
    ROUTING_RULES,
    BatteryMode,
    NOTIF_OVERLOAD_THRESHOLD,
    APP_SWITCH_OVERLOAD,
    TYPO_RATE_THRESHOLD,
    settings,
    scoring_weights,
)

logger = logging.getLogger("friday.orchestrator")


class Orchestrator:
    """Routes ContextObjects to the right agent chains and assembles responses."""

    def __init__(self, db, chroma, laptop_connections: dict):
        self.db                = db
        self.chroma            = chroma
        self.laptop_connections = laptop_connections

        # Lazy-loaded agent cache: name → agent instance
        self._agents: dict[str, Any] = {}

        # Pending action map for RLHF: action_id → log_id
        self._pending_actions: dict[str, int] = {}

        # Interaction counter per user for weight renormalisation
        self._interaction_counter: dict[str, int] = {}

    # ──────────────────────────────────────────────────────────────────────────
    # Public API
    # ──────────────────────────────────────────────────────────────────────────

    async def process(self, ctx: dict) -> Optional[dict]:
        """
        Main entry point. Accepts a validated ContextObject dict.
        Returns a response dict to send to Android (may be None if silenced).
        """
        session_id = ctx["metadata"]["session_id"]
        user_id    = ctx["metadata"].get("device_id", "unknown")
        battery    = ctx["sensor_data"].get("battery_level", 100)
        mode       = BatteryMode.from_level(battery)

        # Push real-time telemetry updates to laptop if connected
        ws = self.laptop_connections.get(session_id)
        if ws:
            # 1. Interruption Shield (focus preservation)
            stress_score = ctx.get("user_state", {}).get("stress_score", 0)
            try:
                await ws.send_json({
                    "type": "INTERRUPTION_SHIELD",
                    "stress_score": stress_score
                })
                logger.info(f"Pushed interruption shield (stress: {stress_score}) to laptop session: {session_id}")
            except Exception as e:
                logger.warning(f"Failed to push interruption shield: {e}")

            # 2. Media Handoff (if active media is present)
            active_media = ctx.get("sensor_data", {}).get("active_media")
            if active_media:
                try:
                    await ws.send_json({
                        "type": "MEDIA_HANDOFF",
                        "active_media": active_media
                    })
                    logger.info(f"Pushed media handoff to laptop session: {session_id}")
                except Exception as e:
                    logger.warning(f"Failed to push media handoff: {e}")

            # 3. Page Handoff (if active page is present)
            active_page = ctx.get("sensor_data", {}).get("active_page")
            if active_page:
                try:
                    await ws.send_json({
                        "type": "PAGE_HANDOFF",
                        "active_page": active_page
                    })
                    logger.info(f"Pushed page handoff to laptop session: {session_id}")
                except Exception as e:
                    logger.warning(f"Failed to push page handoff: {e}")

        # Ghost mode: no inference, just log
        if mode == BatteryMode.GHOST:
            logger.info(f"Ghost mode active (battery {battery}%) — skipping inference")
            return None

        # Determine condition → agent chain
        condition, agent_names = self._route(ctx)
        logger.info(f"Condition: {condition} | Chain: {agent_names}")

        # Run agents
        agent_results = await self._run_chain(agent_names, ctx)

        # Score the candidate response
        score, response_text, agent_used = self._assemble(ctx, agent_results, condition)

        logger.info(f"Response score: {score:.1f} / 100 (threshold {settings.RESPONSE_SCORE_THRESHOLD})")

        if score < settings.RESPONSE_SCORE_THRESHOLD:
            logger.info("Score below threshold → SILENCE")
            # Still log the silenced decision
            self.db.log_kpi(
                user_id=user_id,
                stress_score=ctx["user_state"].get("stress_score", 0) ,
                suggested_action="[SILENCED]",
                response_score=score,
                user_reaction=None,
                agent_type=agent_used,
            )
            return None

        # Build action
        action_id = str(uuid.uuid4())
        response  = {
            "type":      "FRIDAY_CARD",
            "action_id": action_id,
            "score":     round(score, 1),
            "condition": condition,
            "message":   response_text,
            "agent":     agent_used,
            "timestamp": ctx["metadata"]["timestamp"],
            "laptop_active": len(self.laptop_connections) > 0,
        }

        # Persist KPI log
        log_id = self.db.log_kpi(
            user_id=user_id,
            stress_score=ctx["user_state"].get("stress_score", 0) ,
            suggested_action=response_text,
            response_score=score,
            user_reaction=None,
            agent_type=agent_used,
        )
        self._pending_actions[action_id] = log_id

        # Push to laptop if connected
        await self._push_to_laptop(session_id, response, ctx)

        # Store memory episode in ChromaDB (async, don't block response)
        asyncio.create_task(self._store_memory(ctx, response_text, agent_used))

        return response

    def record_feedback(self, action_id: str, reaction: str):
        """
        Called when user responds to a FRIDAY card (helpful / dismissed / ignored).
        Updates the KPI log and adjusts scoring weights (RLHF).
        """
        log_id = self._pending_actions.pop(action_id, None)
        if log_id:
            self.db.update_reaction(log_id=log_id, reaction=reaction)

        # RLHF weight adjustment
        step = settings.RLHF_STEP
        if reaction == "helpful":
            # Positive: reinforce emotional relevance and memory
            scoring_weights.w_emotion += step * 0.5
            scoring_weights.w_memory  += step * 0.5
        elif reaction == "dismissed":
            scoring_weights.w_intrusive = max(
        0.01,
        scoring_weights.w_intrusive - step
        )

        # Renormalise every 10 interactions (approximate, not per-user here)
        self._global_counter = getattr(self, "_global_counter", 0) + 1
        if self._global_counter % 10 == 0:
            scoring_weights.renormalize()
            logger.info(f"Weights renormalised: {scoring_weights.as_dict()}")

    def agent_status(self) -> dict:
        """Return which agents are loaded."""
        return {
            name: ("loaded" if name in self._agents else "lazy")
            for name in AGENT_REGISTRY
        }

    # ──────────────────────────────────────────────────────────────────────────
    # Routing
    # ──────────────────────────────────────────────────────────────────────────

    def _route(self, ctx: dict) -> tuple[str, list[str]]:
        """Determine the highest-priority matching condition and its agent chain."""
        sensor = ctx.get("sensor_data", {})
        state  = ctx.get("user_state",  {})

        stress      = state.get("stress_score", 0)
        notif_count = sensor.get("notification_count", 0)
        app_switches = sensor.get("app_switches", 0)
        typo_rate   = sensor.get("typo_rate", 0.0)
        active_task = ctx.get("active_task")

        # Evaluate conditions
        flags: dict[str, bool] = {
            "high_stress":          stress >= 65,
            "notification_overload": notif_count >= NOTIF_OVERLOAD_THRESHOLD
                                     or app_switches >= APP_SWITCH_OVERLOAD
                                     or typo_rate >= TYPO_RATE_THRESHOLD,
            "burnout_risk":         stress >= 80 and app_switches >= 20,
            "task_active":          active_task is not None,
            "context_shift":        sensor.get("location") is not None,
            "default":              True,
        }

        # Pick highest-priority matching rule
        for rule in sorted(ROUTING_RULES, key=lambda r: r["priority"]):
            if flags.get(rule["condition"], False):
                return rule["condition"], rule["agents"]

        return "default", ["emotion", "decision"]

    # ──────────────────────────────────────────────────────────────────────────
    # Agent chain execution
    # ──────────────────────────────────────────────────────────────────────────

    async def _run_chain(self, agent_names: list[str], ctx: dict) -> dict[str, Any]:
        """Lazy-load and run each agent in the chain, collecting results."""
        results: dict[str, Any] = {}
        for name in agent_names:
            agent = self._load_agent(name)
            if agent is None:
                logger.warning(f"Agent '{name}' could not be loaded — skipping")
                continue
            try:
                result = await agent.run(ctx, results, self.db, self.chroma)
                results[name] = result
            except Exception as exc:
                logger.exception(f"Agent '{name}' raised: {exc}")
                results[name] = {"error": str(exc)}
        return results

    def _load_agent(self, name: str) -> Optional[Any]:
        """Lazy-load an agent by name from the registry."""
        if name in self._agents:
            return self._agents[name]

        spec = AGENT_REGISTRY.get(name)
        if not spec:
            logger.error(f"Unknown agent: {name}")
            return None

        try:
            module = importlib.import_module(spec["module"])
            cls    = getattr(module, spec["class"])
            instance = cls()
            self._agents[name] = instance
            logger.info(f"Lazy-loaded agent: {name}")
            return instance
        except Exception as exc:
            logger.exception(f"Failed to load agent '{name}': {exc}")
            return None

    # ──────────────────────────────────────────────────────────────────────────
    # Response assembly & scoring
    # ──────────────────────────────────────────────────────────────────────────

    def _assemble(
        self,
        ctx: dict,
        results: dict[str, Any],
        condition: str,
    ) -> tuple[float, str, str]:
        """
        Score each candidate response and pick the best one.
        Returns (score, response_text, agent_name).
        """
        # Gather candidate texts from decision agent (or fallback to emotion)
        decision_result = results.get("decision", {})
        candidates      = decision_result.get("candidates", [])

        if not candidates:
            # Fallback: use emotion agent summary
            emotion_result = results.get("emotion", {})
            fallback_text  = emotion_result.get("summary", "I noticed something. How are you holding up?")
            candidates     = [{"text": fallback_text, "agent": "emotion"}]

        best_score    = -1.0
        best_text     = candidates[0]["text"]
        best_agent    = candidates[0].get("agent", "decision")

        state  = ctx.get("user_state",  {})
        sensor = ctx.get("sensor_data", {})

        for cand in candidates:
            score = self._score_candidate(cand, state, sensor, results)
            if score > best_score:
                best_score = score
                best_text  = cand["text"]
                best_agent = cand.get("agent", "decision")

        return best_score, best_text, best_agent

    def _score_candidate(
        self,
        candidate: dict,
        state: dict,
        sensor: dict,
        results: dict,
    ) -> float:
        """
        SCORE = (Emotional_Relevance × w_emotion)
              + (Timing             × w_timing)
              + (Memory_Alignment   × w_memory)
              + (Action_Quality     × w_action)
              + (Intrusiveness_Penalty × w_intrusive)

        Each sub-score is 0–100.
        """
        w = scoring_weights

        emotional_relevance = min(state.get("stress_score", 0), 100)

        timing_score = self._compute_timing(sensor)

        memory_data    = results.get("memory", {})
        memory_score   = memory_data.get("alignment_score", 50.0)

        action_quality = candidate.get("action_quality", 60.0)

        # Intrusiveness is inverse: high intrusiveness → lower score
        intrusiveness_raw     = candidate.get("intrusiveness", 50.0)
        intrusiveness_penalty = 100 - intrusiveness_raw

        score = (
            emotional_relevance * w.w_emotion
            + timing_score      * w.w_timing
            + memory_score      * w.w_memory
            + action_quality    * w.w_action
            + intrusiveness_penalty * w.w_intrusive
        )
        return round(score, 2)

    def _compute_timing(self, sensor: dict) -> float:
        """
        Timing score (0–100): penalise if battery is low or screen has been
        on for a very long time (user likely fatigued).
        """
        battery    = sensor.get("battery_level", 100)
        screen_min = sensor.get("screen_on_time", 0) / 60  # seconds → minutes

        battery_factor = battery / 100  # 0.0 – 1.0
        fatigue_factor = max(0, 1 - screen_min / 180)  # degrades over 3 h

        return round((battery_factor * 0.5 + fatigue_factor * 0.5) * 100, 1)

    # ──────────────────────────────────────────────────────────────────────────
    # Laptop push
    # ──────────────────────────────────────────────────────────────────────────

    async def _push_to_laptop(self, session_id: str, response: dict, ctx: dict):
        """Forward a UI command to the connected Laptop Chrome Extension."""
        ws = self.laptop_connections.get(session_id)
        if not ws:
            return

        ui_command = {
            "type":      "SHOW_CONTINUITY",
            "action_id": response["action_id"],
            "content": {
                "title":         "FRIDAY",
                "message":       response["message"],
                "score":         response["score"],
                "condition":     response["condition"],
                "source_device": "android",
            },
        }
        try:
            await ws.send_json(ui_command)
            logger.info(f"UICommand pushed to laptop session: {session_id}")
        except Exception as exc:
            logger.warning(f"Failed to push to laptop: {exc}")

    # ──────────────────────────────────────────────────────────────────────────
    # Memory storage
    # ──────────────────────────────────────────────────────────────────────────

    async def _store_memory(self, ctx: dict, response_text: str, agent_used: str = ""):
        """Compress and store a memory episode in ChromaDB."""
        if agent_used == "memory" or "I remember" in response_text:
            logger.info("Skipping memory storage for recall prompt to prevent recursion")
            return
        try:
            meta = ctx["metadata"]
            state  = ctx.get("user_state", {})
            sensor = ctx.get("sensor_data", {})

            summary = (
                f"[{meta['timestamp']}] "
                f"Stress={state.get('stress_score', 0)} "
                f"Emotion={state.get('emotion_label', 'unknown')} "
                f"Location={sensor.get('location', 'unknown')} "
                f"Action: {response_text[:100]}"
            )

            self.chroma.add_memory(
                doc_id=meta["message_id"],
                text=summary,
                metadata={
                    "timestamp":    meta["timestamp"],
                    "session_id":   meta["session_id"],
                    "device_id":    meta["device_id"],
                    "emotion":      state.get("emotion_label", "unknown"),
                    "stress_score": str(state.get("stress_score", 0)),
                    "location":     sensor.get("location", "unknown"),
                    "action":       response_text,
                },
            )
        except Exception as exc:
            logger.warning(f"Memory storage failed: {exc}")
