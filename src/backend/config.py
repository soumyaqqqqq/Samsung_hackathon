"""
FRIDAY - config.py
Agent registry, thresholds, mode settings, environment config.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any

from dotenv import load_dotenv

load_dotenv()


# ──────────────────────────────────────────────────────────────────────────────
# Settings
# ──────────────────────────────────────────────────────────────────────────────

@dataclass
class Settings:
    # Server
    PORT: int = int(os.getenv("PORT", "8000"))
    DEBUG: bool = os.getenv("DEBUG", "false").lower() == "true"

    # Security
    HMAC_SECRET: str = os.getenv(
        "FRIDAY_HMAC_SECRET",
        "friday-dev-secret-change-in-prod"
    )
    ENFORCE_HMAC: bool = os.getenv(
        "ENFORCE_HMAC",
        "false"
    ).lower() == "true"

    # Storage paths
    SQLITE_PATH: str = os.getenv(
        "SQLITE_PATH",
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "friday.db")
    )
    CHROMA_PATH: str = os.getenv(
        "CHROMA_PATH",
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "chroma")
    )

    # Ollama / LLM
    OLLAMA_BASE_URL: str = os.getenv(
        "OLLAMA_BASE_URL",
        "http://127.0.0.1:11434"
    )
    PRIMARY_LLM_MODEL: str = os.getenv(
        "PRIMARY_MODEL",
        "llama3.1:8b"
    )
    FALLBACK_LLM_MODEL: str = os.getenv(
        "FALLBACK_MODEL",
        "phi3:mini"
    )

    ...

    # ChromaDB embedding model (sentence-transformers, runs locally)
    EMBEDDING_MODEL: str = os.getenv("EMBEDDING_MODEL", "all-MiniLM-L6-v2")

    # Decision threshold — responses below this score are silenced
    RESPONSE_SCORE_THRESHOLD: float = float(os.getenv("SCORE_THRESHOLD", "40.0"))

    # RLHF weight adjustment step
    RLHF_STEP: float = float(os.getenv("RLHF_STEP", "0.05"))

    # Maximum memories to inject into LLM context
    MAX_MEMORY_RESULTS: int = int(os.getenv("MAX_MEMORY_RESULTS", "5"))

    def __post_init__(self):
        # Resolve relative storage paths relative to config.py's directory
        base_dir = os.path.dirname(os.path.abspath(__file__))
        if not os.path.isabs(self.SQLITE_PATH):
            self.SQLITE_PATH = os.path.abspath(os.path.join(base_dir, self.SQLITE_PATH))
        if not os.path.isabs(self.CHROMA_PATH):
            self.CHROMA_PATH = os.path.abspath(os.path.join(base_dir, self.CHROMA_PATH))


settings = Settings()


# ──────────────────────────────────────────────────────────────────────────────
# Scoring weights (mutable — adjusted by RLHF feedback loop)
# ──────────────────────────────────────────────────────────────────────────────

@dataclass
class ScoringWeights:
    """
    Weights for the decision scoring formula:
      SCORE = (emotional_relevance × w_emotion)
            + (timing            × w_timing)
            + (memory_alignment  × w_memory)
            + (action_quality    × w_action)
            + (intrusiveness     × w_intrusive)

    All weights should sum to 1.0. They are adjusted by RLHF feedback.
    """
    w_emotion:   float = 0.30
    w_timing:    float = 0.25
    w_memory:    float = 0.20
    w_action:    float = 0.10
    w_intrusive: float = 0.15   # lower is less intrusive → higher penalty if high

    def renormalize(self):
        total = self.w_emotion + self.w_timing + self.w_memory + self.w_action + self.w_intrusive
        if total > 0:
            self.w_emotion   /= total
            self.w_timing    /= total
            self.w_memory    /= total
            self.w_action    /= total
            self.w_intrusive /= total

    def as_dict(self) -> dict[str, float]:
        return {
            "w_emotion":   self.w_emotion,
            "w_timing":    self.w_timing,
            "w_memory":    self.w_memory,
            "w_action":    self.w_action,
            "w_intrusive": self.w_intrusive,
        }


# Singleton weights instance (shared across all agents in process)
scoring_weights = ScoringWeights()


# ──────────────────────────────────────────────────────────────────────────────
# Agent registry
# ──────────────────────────────────────────────────────────────────────────────

# Maps agent name → module path + class name (lazy-loaded on demand)
AGENT_REGISTRY = {
    "emotion": {
        "module": "agents.emotion",
        "class": "EmotionAgent"
    },
    "memory": {
        "module": "agents.memory",
        "class": "MemoryAgent"
    },

    "task": {
        "module": "injection.task",
        "class": "TaskAgent"
    },
    "decision": {
        "module": "injection.decision",
        "class": "DecisionAgent"
    },
    "wellbeing": {
        "module": "injection.wellbeing",
        "class": "WellbeingAgent"
    },

    "context": {
        "module": "detection.context",
        "class": "ContextAgent"
    },
    "burnout": {
        "module": "detection.burnout",
        "class": "BurnoutAgent"
    },
    "notif": {
        "module": "detection.notif",
        "class": "NotifAgent"
    },
}

# Routing rules: which signals trigger which agent chains
# Each entry maps a condition key → list of agent names to invoke (in order)
ROUTING_RULES: list[dict[str, Any]] = [
    {
        "condition": "high_stress",          # stress_score >= 65
        "agents":    ["emotion", "memory", "wellbeing", "decision"],
        "priority":  1,
    },
    {
        "condition": "notification_overload", # notification_count >= 10
        "agents":    ["notif", "emotion", "decision"],
        "priority":  2,
    },
    {
        "condition": "burnout_risk",          # burnout flag from wellbeing
        "agents":    ["wellbeing", "burnout", "decision"],
        "priority":  1,
    },
    {
        "condition": "task_active",           # active_task present
        "agents":    ["task", "memory", "decision"],
        "priority":  3,
    },
    {
        "condition": "context_shift",         # location or app changed
        "agents":    ["context", "memory", "decision"],
        "priority":  4,
    },
    {
        "condition": "default",
        "agents":    ["emotion", "decision"],
        "priority":  5,
    },
]


# ──────────────────────────────────────────────────────────────────────────────
# Stress thresholds
# ──────────────────────────────────────────────────────────────────────────────

class StressLevel:
    LOW    = (0,  40)
    MEDIUM = (40, 65)
    HIGH   = (65, 85)
    CRISIS = (85, 100)

    @staticmethod
    def label(score: float) -> str:
        if score < 40:  return "low"
        if score < 65:  return "medium"
        if score < 85:  return "high"
        return "crisis"


# ──────────────────────────────────────────────────────────────────────────────
# Notification suppression thresholds
# ──────────────────────────────────────────────────────────────────────────────

NOTIF_OVERLOAD_THRESHOLD = 10     # notifications in current session
APP_SWITCH_OVERLOAD      = 15     # app switches per session
TYPO_RATE_THRESHOLD      = 0.07   # typo rate indicating stress


# ──────────────────────────────────────────────────────────────────────────────
# Battery mode thresholds (mirrors Android BatteryOptimizer.kt)
# ──────────────────────────────────────────────────────────────────────────────

class BatteryMode:
    GHOST  = "ghost"   # < 20 %
    AWARE  = "aware"   # 20–60 %
    ACTIVE = "active"  # > 60 %

    @staticmethod
    def from_level(level: int) -> str:
        if level < 20:  return BatteryMode.GHOST
        if level < 60:  return BatteryMode.AWARE
        return BatteryMode.ACTIVE
