"""
FRIDAY - agents/wellbeing.py
Burnout tracking, self-critical language detection, emotional longitudinal trends.
"""

from __future__ import annotations

import logging
import re
from typing import Any

from config import StressLevel

logger = logging.getLogger("friday.agent.wellbeing")

# Patterns indicating self-critical or burnout-adjacent language
_BURNOUT_PATTERNS = re.compile(
    r"\b(can'?t do this|hate (my(self)?|this)|"
    r"so tired|exhausted|overwhelmed|breaking down|"
    r"useless|worthless|giving up|done with (this|it|everything)|"
    r"i (give up|quit|failed))\b",
    re.IGNORECASE,
)

_CRISIS_PATTERNS = re.compile(
    r"\b(can'?t go on|don'?t want to be here|"
    r"end it all|hurt (my)?self|no point)\b",
    re.IGNORECASE,
)


class WellbeingAgent:
    """
    Monitors emotional health over time.

    Detects:
      - Sustained high stress (multi-session burnout risk)
      - Self-critical language in transcribed voice / typed messages
      - Crisis signals → escalate to human support resource
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
        user_id = ctx["metadata"].get("device_id", "unknown")

        stress_score  = state.get("stress_score", 0)
        emotion_label = state.get("emotion_label", "low")

        # Check for self-critical or crisis language
        text_input = ctx.get("text_input", "")   # optional field from Android
        burnout_language = False
        crisis_detected  = False

        if text_input:
            burnout_language = bool(_BURNOUT_PATTERNS.search(text_input))
            crisis_detected  = bool(_CRISIS_PATTERNS.search(text_input))

        # Retrieve recent stress history from DB
        recent_logs = db.recent_stress_logs(user_id=user_id, limit=20)
        sustained_high = self._sustained_high_stress(recent_logs)

        # Determine burnout risk level
        burnout_risk = self._calculate_burnout_risk(
            stress_score, sustained_high, burnout_language
        )

        # Build response
        result: dict[str, Any] = {
            "burnout_risk":     burnout_risk,          # "low" / "medium" / "high"
            "sustained_high":   sustained_high,
            "burnout_language": burnout_language,
            "crisis_detected":  crisis_detected,
            "wellbeing_note":   None,
        }

        if crisis_detected:
            result["wellbeing_note"] = (
                "It sounds like you might be having a really tough time. "
                "Please consider reaching out — iCall (9152987821) is available. 💙"
            )
            logger.warning(f"CRISIS signal detected for user {user_id}")

        elif burnout_risk == "high" or burnout_language:
            result["wellbeing_note"] = (
                "You've been under a lot of pressure lately. "
                "It's okay to step back — want me to clear your next hour?"
            )

        elif sustained_high:
            result["wellbeing_note"] = (
                "You've been stressed for a while. "
                "Even a 5-minute break can reset your focus."
            )

        logger.info(
            f"WellbeingAgent → burnout_risk={burnout_risk} "
            f"crisis={crisis_detected} sustained={sustained_high}"
        )

        return result

    # ──────────────────────────────────────────────────────────────────────────

    def _sustained_high_stress(self, recent_logs: list[dict]) -> bool:
        """Return True if at least 5 of the last 20 logs show high stress."""
        high_count = sum(
            1 for log in recent_logs
            if log.get("stress_score", 0) >= 65  
        )
        return high_count >= 5

    def _calculate_burnout_risk(
        self,
        stress_score: float,
        sustained_high: bool,
        burnout_language: bool,
    ) -> str:
        risk_score = 0
        if stress_score >= 80:          risk_score += 3
        elif stress_score >= 65:        risk_score += 2
        elif stress_score >= 50:        risk_score += 1
        if sustained_high:              risk_score += 2
        if burnout_language:            risk_score += 3

        if risk_score >= 6:  return "high"
        if risk_score >= 3:  return "medium"
        return "low"
