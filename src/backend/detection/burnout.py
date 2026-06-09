"""
FRIDAY - agents/burnout.py
Dedicated burnout prediction agent.
Uses sustained stress history + workload indicators.
"""

from __future__ import annotations

import logging
from typing import Any

logger = logging.getLogger("friday.agent.burnout")


class BurnoutAgent:
    """
    Predicts burnout risk using historical stress and workload data.
    Complements WellbeingAgent with explicit burnout-specific logic.
    """

    # Weight factors for burnout composite score
    _W_SUSTAINED  = 0.40
    _W_WORKLOAD   = 0.35
    _W_SOCIAL     = 0.15
    _W_RECOVERY   = 0.10

    async def run(
        self,
        ctx: dict,
        previous_results: dict[str, Any],
        db,
        chroma,
    ) -> dict[str, Any]:
        user_id = ctx["metadata"].get("device_id", "unknown")
        sensor  = ctx.get("sensor_data", {})
        state   = ctx.get("user_state",  {})

        # ── Sustained stress (from recent DB history) ────────────────────────
        recent = db.recent_stress_logs(user_id=user_id, limit=50)
        avg_recent_stress = (
            sum(r.get("stress_score", 0) for r in recent) / len(recent)
            if recent else 0
        )
        sustained_score = min(avg_recent_stress, 100)

        # ── Workload indicators ──────────────────────────────────────────────
        app_switches   = sensor.get("app_switches", 0)
        notif_count    = sensor.get("notification_count", 0)
        screen_minutes = sensor.get("screen_on_time", 0) / 60
        workload_score = min(
            (app_switches / 30 * 40)
            + (notif_count / 20 * 30)
            + (screen_minutes / 240 * 30),
            100,
        )

        # ── Recovery indicator (lower battery + not charging = overworked) ───
        battery  = sensor.get("battery_level", 100)
        charging = sensor.get("is_charging", False)
        recovery_score = 0 if charging else max(0, (100 - battery))

        # ── Social pressure (placeholder — extend via calendar / voice) ───────
        social_score = 0.0   # TODO: integrate calendar pressure detection

        # ── Composite burnout risk score ─────────────────────────────────────
        composite = (
            sustained_score  * self._W_SUSTAINED
            + workload_score * self._W_WORKLOAD
            + social_score   * self._W_SOCIAL
            + recovery_score * self._W_RECOVERY
        )
        composite = round(min(max(composite, 0), 100), 2)

        # Accuracy of prediction is self-reported after user feedback
        risk_label = (
            "critical" if composite >= 80 else
            "high"     if composite >= 60 else
            "medium"   if composite >= 40 else
            "low"
        )

        logger.info(f"BurnoutAgent → composite={composite} risk={risk_label}")

        return {
            "burnout_composite":  composite,
            "risk_label":         risk_label,
            "sustained_score":    round(sustained_score, 1),
            "workload_score":     round(workload_score, 1),
            "recovery_score":     round(recovery_score, 1),
            "recommendation":     self._recommend(risk_label),
        }

    def _recommend(self, risk: str) -> str:
        recs = {
            "critical": "Please prioritise rest. Consider blocking your calendar for tomorrow morning.",
            "high":     "You're burning through energy fast. A short break now will pay off.",
            "medium":   "Watch your workload — try to finish one thing before starting another.",
            "low":      "You're managing well. Keep it up!",
        }
        return recs.get(risk, "")
