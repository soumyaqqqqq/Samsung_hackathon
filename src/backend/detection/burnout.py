"""
FRIDAY - agents/burnout.py
Dedicated burnout prediction agent.
Uses sustained stress history + workload indicators.
LoRA model used as a fallback/cross-check for uncertain predictions.
"""
from __future__ import annotations
import logging
from typing import Any
from pathlib import Path

logger = logging.getLogger("friday.agent.burnout")

# ── Model loader (lazy — only loads once on first use) ────────────────────────
_model    = None
_tokenizer = None

def _load_model():
    global _model, _tokenizer
    if _model is not None:
        return _model, _tokenizer
    try:
        from peft import PeftModel
        from transformers import AutoModelForSequenceClassification, AutoTokenizer
        import torch

        MODEL_PATH = Path(__file__).resolve().parent.parent / "models" / "roberta-burnout-lora"
        base       = AutoModelForSequenceClassification.from_pretrained("roberta-base", num_labels=1)
        _model     = PeftModel.from_pretrained(base, MODEL_PATH).eval()
        _tokenizer = AutoTokenizer.from_pretrained(MODEL_PATH)
        logger.info("BurnoutAgent: LoRA model loaded successfully.")
    except Exception as e:
        logger.warning(f"BurnoutAgent: Could not load LoRA model — {e}")
        _model, _tokenizer = None, None
    return _model, _tokenizer


def _model_predict(input_text: str) -> float | None:
    """
    Returns a burnout score in [0, 100] from the LoRA model,
    or None if the model is unavailable.
    """
    import torch
    model, tokenizer = _load_model()
    if model is None:
        return None
    try:
        tokens = tokenizer(
            input_text,
            return_tensors="pt",
            padding="max_length",
            truncation=True,
            max_length=128,
        )
        with torch.no_grad():
            logits = model(**tokens).logits  # shape (1, 1), range [0, 1]
        return round(float(logits.squeeze()) * 100, 2)   # scale to [0, 100]
    except Exception as e:
        logger.warning(f"BurnoutAgent: model inference failed — {e}")
        return None


def _build_model_input(sensor: dict, state: dict) -> str:
    """Serialise sensor data into the format the model was trained on."""
    app    = state.get("current_app", "unknown")
    hour   = state.get("hour", 0)
    wpm    = sensor.get("typing_speed_wpm", 0)
    errors = sensor.get("typing_errors", 0)
    notif  = sensor.get("notification_count", 0)
    text   = state.get("last_text_snippet", "")
    return (
        f"[APP] {app} [HOUR] {hour:02d} "
        f"[NOTIF] {notif} [WPM] {wpm} [ERRORS] {errors} "
        f"[TEXT] {text}"
    )


class BurnoutAgent:
    """
    Predicts burnout risk using historical stress and workload data.
    Complements WellbeingAgent with explicit burnout-specific logic.

    Scoring pipeline:
        1. Heuristic composite (always runs)
        2. LoRA model score (runs when model is available)
        3. If both available → weighted blend (60% heuristic, 40% model)
           If model unavailable → heuristic only
    """

    _W_SUSTAINED = 0.40
    _W_WORKLOAD  = 0.35
    _W_SOCIAL    = 0.15
    _W_RECOVERY  = 0.10

    # Blend weights between heuristic and model
    _W_HEURISTIC = 0.60
    _W_MODEL     = 0.40

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

        # ── 1. Sustained stress ───────────────────────────────────────────────
        recent = db.recent_stress_logs(user_id=user_id, limit=50)
        avg_recent_stress = (
            sum(r.get("stress_score", 0) for r in recent) / len(recent)
            if recent else 0
        )
        sustained_score = min(avg_recent_stress, 100)

        # ── 2. Workload indicators ────────────────────────────────────────────
        app_switches   = sensor.get("app_switches", 0)
        notif_count    = sensor.get("notification_count", 0)
        screen_minutes = sensor.get("screen_on_time", 0) / 60
        workload_score = min(
            (app_switches   / 30  * 40)
            + (notif_count  / 20  * 30)
            + (screen_minutes / 240 * 30),
            100,
        )

        # ── 3. Recovery indicator ─────────────────────────────────────────────
        battery        = sensor.get("battery_level", 100)
        charging       = sensor.get("is_charging", False)
        recovery_score = 0 if charging else max(0, (100 - battery))

        # ── 4. Social pressure (placeholder) ─────────────────────────────────
        social_score = 0.0

        # ── 5. Heuristic composite ────────────────────────────────────────────
        heuristic = (
            sustained_score * self._W_SUSTAINED
            + workload_score  * self._W_WORKLOAD
            + social_score    * self._W_SOCIAL
            + recovery_score  * self._W_RECOVERY
        )
        heuristic = round(min(max(heuristic, 0), 100), 2)

        # ── 6. LoRA model score (fallback / cross-check) ──────────────────────
        model_score  = _model_predict(_build_model_input(sensor, state))
        model_used   = model_score is not None

        if model_used:
            composite = round(
                heuristic   * self._W_HEURISTIC
                + model_score * self._W_MODEL,
                2,
            )
            logger.info(
                f"BurnoutAgent → heuristic={heuristic} model={model_score} "
                f"blended={composite}"
            )
        else:
            composite = heuristic
            logger.info(
                f"BurnoutAgent → heuristic only composite={composite} "
                "(LoRA model unavailable)"
            )

        # ── 7. Risk label ─────────────────────────────────────────────────────
        risk_label = (
            "critical" if composite >= 80 else
            "high"     if composite >= 60 else
            "medium"   if composite >= 40 else
            "low"
        )

        logger.info(f"BurnoutAgent → composite={composite} risk={risk_label}")

        return {
            "burnout_composite": composite,
            "risk_label":        risk_label,
            "sustained_score":   round(sustained_score, 1),
            "workload_score":    round(workload_score, 1),
            "recovery_score":    round(recovery_score, 1),
            "model_score":       model_score,       # None if model unavailable
            "model_used":        model_used,
            "recommendation":    self._recommend(risk_label),
        }

    def _recommend(self, risk: str) -> str:
        recs = {
            "critical": "Please prioritise rest. Consider blocking your calendar for tomorrow morning.",
            "high":     "You're burning through energy fast. A short break now will pay off.",
            "medium":   "Watch your workload — try to finish one thing before starting another.",
            "low":      "You're managing well. Keep it up!",
        }
        return recs.get(risk, "")