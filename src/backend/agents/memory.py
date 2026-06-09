"""
FRIDAY - agents/memory.py
ChromaDB semantic search, history injection.

Retrieves past episodes similar to the current context and injects
them as enriched memory into subsequent agents.
"""

from __future__ import annotations

import logging
from typing import Any

from config import settings

logger = logging.getLogger("friday.agent.memory")


class MemoryAgent:
    """
    Semantic memory retrieval agent.

    1. Builds a query string from the current ContextObject.
    2. Queries ChromaDB for the most similar past episodes.
    3. Calculates a memory alignment score.
    4. Returns retrieved episodes for injection into the decision agent.
    """

    async def run(
        self,
        ctx: dict,
        results: dict[str, Any],
        db,
        chroma,
    ) -> dict[str, Any]:
        state  = ctx.get("user_state",  {})
        sensor = ctx.get("sensor_data", {})
        meta   = ctx.get("metadata",    {})

        # Build query text from current context
        query = self._build_query(state, sensor)

        # Retrieve similar memories
        memories = chroma.query_memories(query_text=query, n_results=settings.MAX_MEMORY_RESULTS)

        # Score alignment: how closely do past memories match current context?
        alignment_score = self._alignment_score(memories)

        # Build memory context summary for prompt injection
        memory_context = self._build_context_text(memories)

        logger.info(
            f"MemoryAgent → retrieved {len(memories)} memories, "
            f"alignment={alignment_score:.1f}"
        )

        return {
            "memories":        memories,
            "alignment_score": alignment_score,
            "memory_context":  memory_context,
        }

    # ──────────────────────────────────────────────────────────────────────────

    def _build_query(self, state: dict, sensor: dict) -> str:
        """Compose a descriptive query string for vector similarity search."""
        parts = []

        emotion = previous_results.get("emotion", {})
        label = emotion.get("emotion_label", "unknown")
        stress = emotion.get("stress_score", 0)

        parts.append(f"emotion:{label}")
        parts.append(f"location:{location}")
        parts.append(f"stress:{int(stress)}")

        if sensor.get("notification_count", 0) >= 10:
            parts.append("notification_overload")
        if sensor.get("app_switches", 0) >= 15:
            parts.append("app_switching_high")

        return " ".join(parts)

    def _alignment_score(self, memories: list[dict]) -> float:
        """
        Score 0–100 based on similarity of top memory.
        ChromaDB returns distances (lower = closer). We invert.
        """
        if not memories:
            return 0.0
        top_distance = memories[0].get("distance", 1.0)
        # distance is cosine distance 0–2; 0 = identical
        score = max(0.0, (1.0 - top_distance / 2.0)) * 100
        return round(score, 2)

    def _build_context_text(self, memories: list[dict]) -> str:
        """Summarise retrieved memories into a short prompt injection."""
        if not memories:
            return "No relevant past episodes found."

        lines = []
        for i, mem in enumerate(memories[:3], 1):
            text = mem.get("text", "")
            dist = mem.get("distance", 1.0)
            sim  = round((1 - dist / 2) * 100)
            lines.append(f"{i}. [{sim}% match] {text}")

        return "Relevant past episodes:\n" + "\n".join(lines)
