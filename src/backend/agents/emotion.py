"""
FRIDAY - agents/emotion.py
Calculates a stress score (0–100) from behavioral signals.
Uses a deterministic weighted formula; no LLM call needed.
"""

from __future__ import annotations

import logging
from typing import Any

from config import StressLevel, TYPO_RATE_THRESHOLD

logger = logging.getLogger("friday.agent.emotion")


class EmotionAgent:
    """
    Deterministic emotion/stress scorer.

    Input signals (all from sensor_data):
      - app_switches       : rapid switching → stress
      - notification_count : overload → stress
      - typing_cadence     : slow typing → fatigue
      - typo_rate          : high typos → stress / distraction
      - screen_on_time     : long sessions → fatigue
      - battery_level      : low battery → low-priority window

    Plus user_state.stress_score from Android's on-device model (if present).
    """

    # Signal weights (sum should be 1.0)
    _WEIGHTS = {
        "app_switches":       0.20,
        "notification_count": 0.20,
        "typo_rate":          0.20,
        "typing_cadence":     0.15,
        "screen_on_time":     0.15,
        "on_device_score":    0.10,  # pass-through from Android model
    }

    # Normalisation ceilings (values at which the sub-score hits 100)
    _CEILINGS = {
        "app_switches":       30,   # 30+ switches → max
        "notification_count": 20,   # 20+ notifications → max
        "typo_rate":          0.20, # 20 % typo rate → max
        "typing_cadence":     20,   # ≤ 20 chars/min → fatigued (inverted)
        "screen_on_time":     10800, # 3 h of screen-on → max fatigue
    }

    async def run(
        self,
        ctx: dict,
        previous_results: dict[str, Any],
        db,
        chroma,
    ) -> dict[str, Any]:
        sensor = ctx.get("sensor_data", {})
        state  = ctx.get("user_state",  {})

        sub_scores: dict[str, float] = {}

        # 1. App switches
        sub_scores["app_switches"] = self._normalise(
            sensor.get("app_switches", 0), self._CEILINGS["app_switches"]
        )

        # 2. Notification count
        sub_scores["notification_count"] = self._normalise(
            sensor.get("notification_count", 0), self._CEILINGS["notification_count"]
        )

        # 3. Typo rate (higher → more stressed)
        sub_scores["typo_rate"] = self._normalise(
            sensor.get("typo_rate", 0.0), self._CEILINGS["typo_rate"]
        )

        # 4. Typing cadence (lower cadence → more fatigued → higher stress)
        raw_cadence = sensor.get("typing_cadence", 60)
        ceiling     = self._CEILINGS["typing_cadence"]
        cadence_stress = max(0.0, 1.0 - raw_cadence / (ceiling * 3)) * 100
        sub_scores["typing_cadence"] = cadence_stress

        # 5. Screen on time
        sub_scores["screen_on_time"] = self._normalise(
            sensor.get("screen_on_time", 0), self._CEILINGS["screen_on_time"]
        )

        # 6. On-device model score (already 0–100)
        on_device = state.get("stress_score", 50)
        sub_scores["on_device_score"] = float(on_device)

        # Weighted average
        composite = sum(
            sub_scores[k] * self._WEIGHTS[k]
            for k in self._WEIGHTS
        )
        composite = round(min(max(composite, 0), 100), 2)

        emotion_label = StressLevel.label(composite)

        logger.info(f"EmotionAgent → stress={composite} label={emotion_label}")

        return {
            "stress_score":   composite,
            "emotion_label":  emotion_label,
            "sub_scores":     sub_scores,
            "summary":        self._generate_summary(composite, emotion_label, sensor),
        }

    # ──────────────────────────────────────────────────────────────────────────
    # Helpers
    # ──────────────────────────────────────────────────────────────────────────

    @staticmethod
    def _normalise(value: float, ceiling: float) -> float:
        """Clamp value to [0, ceiling], then scale to 0–100."""
        return round(min(value / ceiling, 1.0) * 100, 2)

    @staticmethod
    def _generate_summary(score: float, label: str, sensor: dict) -> str:
        """Human-readable explanation of the current emotional state."""
        if label == "crisis":
            return (
                "You seem to be under intense pressure right now. "
                "Looks like a lot is happening — want to take a quick breath?"
            )
        if label == "high":
            notif = sensor.get("notification_count", 0)
            if notif >= 10:
                return (
                    f"You have {notif} notifications piling up and your activity "
                    "looks a bit scattered. Want me to help prioritise?"
                )
            return "Looks like you're stressed. Want to prioritise your tasks?"
        if label == "medium":
            return "You seem a bit busy. I'm keeping an eye out for you."
        return "You seem calm. Carry on!"
