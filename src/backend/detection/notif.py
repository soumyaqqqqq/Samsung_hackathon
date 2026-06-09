"""
FRIDAY - agents/notif.py
Notification overload agent — prioritises and batches notifications.
"""

from __future__ import annotations

import logging
from typing import Any

logger = logging.getLogger("friday.agent.notif")

# App categories and their priority weight
_APP_PRIORITY: dict[str, int] = {
    # Critical
    "phone":      10,
    "messages":   9,
    "whatsapp":   8,
    "gmail":      7,
    "calendar":   7,
    # Medium
    "slack":      6,
    "teams":      6,
    "outlook":    5,
    # Low
    "instagram":  2,
    "twitter":    2,
    "youtube":    1,
    "zomato":     1,
    "flipkart":   1,
    "amazon":     1,
    "default":    3,
}


class NotifAgent:
    """
    Evaluates the notification landscape and recommends batching or suppression.

    Outputs:
      - priority_count : number of critical notifications
      - suppressed     : list of low-priority app names to suppress
      - summary_text   : human-readable notification digest
    """

    async def run(
        self,
        ctx: dict,
        previous_results: dict[str, Any],
        db,
        chroma,
    ) -> dict[str, Any]:
        sensor = ctx.get("sensor_data", {})

        notif_count     = sensor.get("notification_count", 0)
        notif_apps = sensor.get("notification_apps") or [] # optional list of app names
        stress_score    = ctx.get("user_state", {}).get("stress_score", 0)

        # Score each app
        app_scores  = self._score_apps(notif_apps)
        priority    = [a for a, s in app_scores.items() if s >= 7]
        suppressed  = [a for a, s in app_scores.items() if s <= 3]

        # Build digest
        if notif_count == 0:
            summary = "No pending notifications."
        elif notif_count < 5:
            summary = f"You have {notif_count} notification(s). Nothing urgent."
        elif suppressed:
            summary = (
                f"{notif_count} notifications. "
                f"Critical: {', '.join(priority) or 'none'}. "
                f"I can hold {len(suppressed)} low-priority ones for later."
            )
        else:
            summary = (
                f"{notif_count} notifications waiting. "
                "Want me to batch the non-urgent ones?"
            )

        # Intrusiveness should decrease when user is stressed
        # (don't pile on more info when already overwhelmed)
        intrusiveness = 50.0 - min(stress_score / 4, 20)

        logger.info(
            f"NotifAgent → count={notif_count} priority={len(priority)} "
            f"suppressed={len(suppressed)}"
        )

        return {
            "notification_count": notif_count,
            "priority_apps":      priority,
            "suppressed_apps":    suppressed,
            "summary":            summary,
            "intrusiveness":      round(intrusiveness, 1),
            "action_quality":     70.0,
        }

    # ──────────────────────────────────────────────────────────────────────────

    def _score_apps(self, app_list: list[str]) -> dict[str, int]:
        """Return priority score for each app in the list."""
        scores = {}
        for app in app_list:
            key = app.lower().strip()
            scores[app] = _APP_PRIORITY.get(key, _APP_PRIORITY["default"])
        return scores
