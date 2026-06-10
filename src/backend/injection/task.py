"""
FRIDAY - agents/task.py
Action generation: notification batching, deadline reminders, task suggestions.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any

logger = logging.getLogger("friday.agent.task")


class TaskAgent:
    """
    Generates actionable suggestions based on:
      - Active task in ContextObject (deadline, progress)
      - Pending notification pile-up
      - Time-of-day and session duration
    """

    async def run(
        self,
        ctx: dict,
        previous_results: dict[str, Any],
        db,
        chroma,
    ) -> dict[str, Any]:
        active_task = ctx.get("active_task")
        sensor      = ctx.get("sensor_data", {})
        state       = ctx.get("user_state",  {})

        suggestions: list[dict] = []

        # ── Deadline-aware suggestion ────────────────────────────────────────
        if active_task:
            deadline_suggestion = self._deadline_check(active_task, state)
            if deadline_suggestion:
                suggestions.append(deadline_suggestion)

        # ── Notification batching ────────────────────────────────────────────
        notif_count = sensor.get("notification_count", 0)
        if notif_count >= 5:
            suggestions.append({
                "type":            "BATCH_NOTIFICATIONS",
                "text":            f"You have {notif_count} notifications. Want me to summarise them?",
                "action_quality":  75.0,
                "intrusiveness":   40.0,
            })

        # ── Focus mode suggestion (high app switching) ───────────────────────
        app_switches = sensor.get("app_switches", 0)
        if app_switches >= 12:
            suggestions.append({
                "type":           "SUGGEST_FOCUS",
                "text":           "You've switched apps a lot. Want to lock in on one thing?",
                "action_quality": 70.0,
                "intrusiveness":  50.0,
            })

        # ── Break suggestion (long screen time) ─────────────────────────────
        screen_min = sensor.get("screen_on_time", 0) / 60
        if screen_min > 90:
            suggestions.append({
                "type":           "SUGGEST_BREAK",
                "text":           f"You've been at it for ~{int(screen_min)} min. A short break might help.",
                "action_quality": 65.0,
                "intrusiveness":  35.0,
            })

        logger.info(f"TaskAgent → {len(suggestions)} suggestions generated")

        return {
            "suggestions": suggestions,
            "task_summary": self._task_summary(active_task),
        }

    # ──────────────────────────────────────────────────────────────────────────

    def _deadline_check(self, task: dict, state: dict) -> dict | None:
        """Check how close the deadline is and return an urgency prompt."""
        deadline_str = task.get("deadline")
        progress     = task.get("progress", 0.0)
        description  = task.get("description", "your task")

        if not deadline_str:
            return None

        try:
            deadline = datetime.fromisoformat(deadline_str.replace("Z", "+00:00"))
            now      = datetime.now(timezone.utc)
            hours_left = (deadline - now).total_seconds() / 3600
        except ValueError:
            return None

        if hours_left < 0:
            return {
                "type":           "DEADLINE_MISSED",
                "text":           f'Your deadline for "{description}" has passed.',
                "action_quality": 80.0,
                "intrusiveness":  70.0,
            }

        if hours_left < 2 and progress < 0.8:
            return {
                "type":           "DEADLINE_URGENT",
                "text":           (
                    f'Less than {int(hours_left * 60)} min left for "{description}" '
                    f"and you're {int(progress * 100)}% done. Need help prioritising?"
                ),
                "action_quality": 90.0,
                "intrusiveness":  65.0,
            }

        if hours_left < 6 and progress < 0.5:
            return {
                "type":           "DEADLINE_APPROACHING",
                "text":           (
                    f'"{description}" is due in ~{int(hours_left)} h and you\'re '
                    f"{int(progress * 100)}% done. Want a plan?"
                ),
                "action_quality": 75.0,
                "intrusiveness":  45.0,
            }

        return None

    def _task_summary(self, task: dict | None) -> str:
        if not task:
            return "No active task."
        return (
            f"{task.get('description', 'Task')} — "
            f"{int(task.get('progress', 0) * 100)}% complete"
        )
