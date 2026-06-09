"""
FRIDAY - agents/context.py
Context awareness — location, time, environment shifts.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any

logger = logging.getLogger("friday.agent.context")

# Map known location strings to descriptive contexts
_LOCATION_PROFILES: dict[str, dict] = {
    "college":       {"label": "college",      "expected_activity": "studying",  "noise_level": "medium"},
    "library":       {"label": "library",      "expected_activity": "studying",  "noise_level": "low"},
    "home":          {"label": "home",         "expected_activity": "relaxing",  "noise_level": "low"},
    "office":        {"label": "office",       "expected_activity": "working",   "noise_level": "medium"},
    "gym":           {"label": "gym",          "expected_activity": "exercising","noise_level": "high"},
    "coffee_shop":   {"label": "coffee shop",  "expected_activity": "casual",   "noise_level": "medium"},
    "commute":       {"label": "commuting",    "expected_activity": "transit",   "noise_level": "high"},
    "unknown":       {"label": "unknown",      "expected_activity": "general",   "noise_level": "unknown"},
}


class ContextAgent:
    """
    Provides a rich contextual understanding of the user's environment.
    Detects location changes, time-of-day, and surface anomalies.
    """

    async def run(
        self,
        ctx: dict,
        previous_results: dict[str, Any],
        db,
        chroma,
    ) -> dict[str, Any]:
        sensor  = ctx.get("sensor_data", {})
        meta    = ctx.get("metadata",    {})

        location_raw = sensor.get("location", "unknown").lower()
        location_profile = _LOCATION_PROFILES.get(location_raw, _LOCATION_PROFILES["unknown"])

        # Time context
        time_context = self._time_context(meta.get("timestamp"))

        # Battery context
        battery  = sensor.get("battery_level", 100)
        charging = sensor.get("is_charging", False)
        battery_note = self._battery_note(battery, charging)

        # Anomaly detection: is user behaving unusually for this location?
        anomaly = self._detect_anomaly(sensor, location_profile)

        context_summary = (
            f"User is at {location_profile['label']} "
            f"({time_context['period']}). "
            f"Expected activity: {location_profile['expected_activity']}."
        )

        logger.info(f"ContextAgent → location={location_raw} period={time_context['period']}")

        return {
            "location":        location_raw,
            "location_profile": location_profile,
            "time_context":    time_context,
            "battery_note":    battery_note,
            "anomaly":         anomaly,
            "context_summary": context_summary,
        }

    # ──────────────────────────────────────────────────────────────────────────

    def _time_context(self, timestamp_str: str | None) -> dict:
        try:
            ts = datetime.fromisoformat(
                (timestamp_str or "").replace("Z", "+00:00")
            )
        except (ValueError, TypeError):
            ts = datetime.now(timezone.utc)

        hour = ts.hour
        if 5  <= hour < 12: period = "morning"
        elif 12 <= hour < 17: period = "afternoon"
        elif 17 <= hour < 21: period = "evening"
        else:                   period = "night"

        is_weekend = ts.weekday() >= 5

        return {
            "hour":       hour,
            "period":     period,
            "is_weekend": is_weekend,
            "weekday":    ts.strftime("%A"),
        }

    def _battery_note(self, level: int, charging: bool) -> str | None:
        if charging:
            return None
        if level < 15:
            return "Battery is very low. FRIDAY will operate in minimal mode."
        if level < 30:
            return "Battery below 30%. Consider charging soon."
        return None

    def _detect_anomaly(self, sensor: dict, profile: dict) -> dict | None:
        """
        Simple rule: if app_switches are very high for a 'low noise' location
        (library, home) the user might be distracted or anxious.
        """
        app_switches = sensor.get("app_switches", 0)
        noise_level  = profile.get("noise_level", "unknown")

        if noise_level == "low" and app_switches >= 10:
            return {
                "type":    "distraction",
                "message": "You seem unusually scattered for this environment.",
            }
        return None
