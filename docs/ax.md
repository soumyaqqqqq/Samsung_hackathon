# FRIDAY: Technical Architecture & Agentic Design

*Empathetic Context-Aware Multi-Agent System | Privacy-First Local-Compute Architecture*

## Executive Summary & Design Philosophy

FRIDAY is an ambient, privacy-first, cross-device AI system addressing Samsung EnnovateX 2026 Problem Statement 5: Designing Empathetic Intelligence User Experience for Everyday Life.

Traditional digital assistants are reactive, session-based, and context-blind. FRIDAY acts as a system-level ambient intelligence layer running across Android (mobile) and Chrome/Workstation Hub (laptop), monitoring behavioral signals to offer proactive assistance while preserving cognitive bandwidth.

**Core Pillars:**
1. **Silence-Awareness:** Default to "Empathetic Silence." Interrupt only when cognitive benefit outweighs distraction cost.
2. **Local-First Compute:** All model inference runs locally. Zero external API calls. User data never leaves the local network.
3. **Cross-Device Continuity:** Context synchronizes across devices via mDNS zeroconf networking without manual configuration.

##   1: Open-Weight Models & Local-First Compute

All inference runs locally on consumer-grade hardware. No cloud APIs. No data exfiltration.

| Model | Source | Size | Format | Deployment | Purpose |
|-------|--------|------|--------|------------|---------|
| **Llama 3.1 8B** | Meta (Open) | 5.2 GB | INT4 GGUF via Ollama | Workstation Hub | Decision reasoning, prompt synthesis, response generation |
| **Phi-3 Mini** | Microsoft (Open) | 2.3 GB | INT4 ONNX | Android Device | Offline fallback reasoning when hub disconnected |
| **roberta-base** | HuggingFace (PEFT/LoRA) | 340 MB | PyTorch | Workstation Hub | Fine-tuning for burnout score regression from telemetry |
| **all-MiniLM-L6-v2** | Sentence-Transformers (Open) | 90 MB | ONNX Runtime | Workstation Hub | 384-dim embeddings for semantic memory (ChromaDB) |
| **Whisper-Base** | OpenAI (MIT License) | 141 MB | GGML C++ | Workstation Hub | Speech-to-text via whisper.cpp native binary |

**Model Selection Rationale:**

Llama 3.1 8B outperforms all smaller models on reasoning tasks. INT4 quantization drops to ~6 GB VRAM (fits consumer laptops). Phi-3 Mini is smallest viable on-device model without severe hallucination. roberta-base fine-tuning (not DistilRoBERTa) uses PEFT/LoRA for efficient parameter update on synthetic telemetry datasets. all-MiniLM-L6-v2 provides dense semantic embeddings for memory retrieval. Whisper-Base via whisper.cpp achieves 94% ASR accuracy without Python overhead.

##   2: Multi-Agent Specialist Architecture

FRIDAY replaces monolithic LLM design with specialized agents executing in orchestrated chains.

### Orchestration Flow

```
Android/Laptop Telemetry
         ↓
Central Orchestrator (src/backend/validation/orchestrator.py)
         ↓
Evaluate Routing Rules (src/backend/config.py)
         ↓
Select Agent Chain (deterministic)
         ↓
Execute Agents Sequential (src/backend/_run_chain)
         ↓
Decision Agent Scores (src/backend/injection/decision.py)
         ↓
Score >= 40.0? → Surface Card : Empathetic Silence
```

### Agent Implementation Contract

All agents implement the unified async signature:

```python
async def run(
    self, 
    ctx: dict, 
    previous_results: dict[str, Any], 
    db, 
    chroma
) -> dict[str, Any]:
    """
    Execute agent logic.
    
    Args:
        ctx: ContextObject with user state (stress, emotion, task, location)
        previous_results: Upstream agent outputs
        db: SQLiteStore instance
        chroma: ChromaStore instance
    
    Returns:
        dict with 'candidates', 'reasoning', 'score' keys
    """
```

This enables agents to cooperate by consuming prior results without tight coupling.

### Specialist Agent Registry

**1. Emotion Agent** (`src/backend/agents/emotion.py`)
Computes stress score (0–100) via deterministic weighted formula. Inputs: app_switches, notification_count, typo_rate, typing_speed, screen_on_time, on-device score. No LLM. Executes in ~40ms.

**2. Memory Agent** (`src/backend/agents/memory.py`)
Retrieves similar past episodes from ChromaDB. Constructs query embedding from current context. Filters by emotion + location. Returns top-5 similar memories with alignment scores and past user feedback.

**3. Task Agent** (`src/backend/injection/task.py`)
Extracts task context from browser history + calendar. Estimates deadline. Calculates time-to-deadline. Suggests task-specific interventions.

**4. Wellbeing Agent** (`src/backend/injection/wellbeing.py`)
Monitors longitudinal digital wellness. Detects burnout risk patterns via rolling statistics. Triggers intervention resources at burnout_risk > 0.65.

**5. Context Agent** (`src/backend/detection/context.py`)
Models environmental state: location classification (library, office, home), time-of-day, battery status, connectivity state, WiFi SSID matching.

**6. Burnout Agent** (`src/backend/detection/burnout.py`)
Predicts burnout risk via context fragmentation. Detects rapid app switching (<45s per app), late-night work (>22:00), sustained stress.

**7. Notif Agent** (`src/backend/detection/notif.py`)
Evaluates incoming notifications. Assigns urgency scores based on app priority, sender importance, focus window state. Recommends batching vs. immediate display.

**8. Voice Agent** (`src/backend/agents/voice.py`)
Transcribes audio via local whisper.cpp binary. Model: `ggml-base.bin`. Offline, no external ASR API calls. Returns transcript + confidence.

**9. Decision Agent** (`src/backend/injection/decision.py`)
Only agent invoking LLM. Gathers candidates from upstream agents. Calls Llama 3.1 8B via Ollama. Generates empathetic response text. Scores using interruption formula.

##   3: Orchestration & Routing Pipelines

### Signal Context Contract

Typical ContextObject from Android via WebSocket:

```json
{
  "metadata": {
    "message_id": "msg_90123",
    "session_id": "sess_456",
    "device_id": "android_galaxy_s24",
    "timestamp": "2026-06-20T00:30:00Z"
  },
  "sensor_data": {
    "battery_level": 85,
    "screen_on_time": 4500,
    "app_switches": 18,
    "typo_rate": 0.08,
    "notification_count": 12,
    "location": "library",
    "active_page": "https://samsung-ennovate.com/docs",
    "active_media": null
  },
  "user_state": {
    "stress_score": 68,
    "emotion_label": "stressed"
  },
  "active_task": {
    "task_id": "task_445",
    "description": "Writing technical manifesto document"
  }
}
```

### Routing Rules (`src/backend/config.py`)

Rather than using heavy state-machine frameworks (LangGraph), FRIDAY implements a custom single-pass orchestrator (`src/backend/validation/orchestrator.py`) with deterministic routing rules:

| Condition | Rule | Agent Chain | Priority |
|-----------|------|-------------|----------|
| `burnout_risk` | `stress >= 80 AND app_switches >= 20` | `wellbeing`, `burnout`, `decision` | 1 |
| `high_stress` | `stress >= 65` | `emotion`, `memory`, `wellbeing`, `decision` | 1 |
| `notif_overload` | `notifs >= 10 OR app_switches >= 15 OR typo_rate >= 0.07` | `notif`, `emotion`, `decision` | 2 |
| `task_active` | `active_task IS NOT NULL` | `task`, `memory`, `decision` | 3 |
| `context_shift` | location or URL changed | `context`, `memory`, `decision` | 4 |
| `default` | fallback | `emotion`, `decision` | 5 |

**Sequential Execution:**

```python
# src/backend/validation/orchestrator.py
async def _run_chain(self, agent_names: list[str], ctx: dict) -> dict[str, Any]:
    """Lazy-load and run each agent in sequence."""
    results: dict[str, Any] = {}
    for name in agent_names:
        agent_class = self.agent_registry[name]
        agent = agent_class(self.model_service)
        output = await agent.run(ctx, results, self.db, self.chroma)
        results[name] = output
    return results
```

No loops. No branching. Single-pass execution. Latency capped at 200ms.

##   4: Decision Scoring Engine & RLHF

### Interruption Formula

$$SCORE = (E \times w_e) + (T \times w_t) + (M \times w_m) + (Q \times w_q) + (P \times w_p)$$

Where:
- **E (Emotional Relevance):** Stress score (0–100)
- **T (Timing Context):** Battery state + screen fatigue (0–100)
- **M (Memory Alignment):** ChromaDB similarity (0–100)
- **Q (Action Quality):** Suggestion profile quality (0–100)
- **P (Intrusiveness Penalty):** 100 - intrusiveness raw score

```python
# src/backend/validation/orchestrator.py
def _score_candidate(self, candidate: dict, state: dict, sensor: dict, results: dict) -> float:
    w = scoring_weights
    
    emotional = min(state.get("stress_score", 0), 100)
    timing = self._compute_timing(sensor)
    memory = results.get("memory", {}).get("alignment_score", 50.0)
    action = candidate.get("action_quality", 60.0)
    intrusive = 100 - candidate.get("intrusiveness", 50.0)
    
    score = (
        emotional * w.w_emotion
        + timing * w.w_timing
        + memory * w.w_memory
        + action * w.w_action
        + intrusive * w.w_intrusive
    )
    return round(score, 2)
```

### Threshold Gating

If **SCORE < 40.0**, FRIDAY stays silent. No UI cards. No sounds. Event logged in SQLite as `[SILENCED]`.

### Reinforcement Learning (RLHF)

User feedback adjusts weights locally:
- **Helpful:** w_emotion += 0.025, w_memory += 0.025
- **Dismissed:** w_intrusive -= 0.05
- **Renormalization:** Every 10 interactions, weights sum to 1.0

No cloud sync. No user data leaves device. Personalization emerges locally.

##   5: Local Model Topology & Power Optimization

### Dynamic Battery Modes

```
ACTIVE (Battery > 60%):      Streams telemetry @ 1-min intervals
AWARE (Battery 20-60%):      Streams @ 5-min intervals
GHOST (Battery < 20%):       Local logging only, no backend calls
```

Each mode adjusts agent chain complexity:
- ACTIVE: Full chains (emotion + memory + wellbeing + decision)
- AWARE: Reduced chains (emotion + decision)
- GHOST: Heuristic only (no LLM, no networking)

##   6: Memory System Architecture

Dual-database strategy separates semantic retrieval from audit logging.

### ChromaDB (Semantic Memory)

**Wrapper:** `ChromaStore` in `src/backend/database/chroma_store.py`

**Schema:**
- `doc_id`: message_id
- `embedding`: 384-dim (all-MiniLM-L6-v2)
- `text`: compressed summary (<100 words)
- `metadata`: timestamp, session_id, device_id, location, stress_score, agent_action

**Index:** HNWS (Hierarchical Navigable Small Worlds), cosine distance

**Pruning:** Supports `delete_memory(doc_id)` for manual deletion. No auto-cron. Local footprint remains bounded.

### SQLite (Deterministic Audit)

**Wrapper:** `SQLiteStore` in `src/backend/database/sqlite_store.py`

**Tables:**
- `kpi_logs`: user_id, stress_score, suggested_action, response_score, user_reaction ('helpful'/'dismissed'/'ignored'), agent_type
- `session_events`: session_id, device_id, event_type ('context_received'/'response_sent'), is_offline, latency_ms
- `stress_history`: timestamp, user_id, stress_score

**Android Sync:** Room database buffers telemetry locally. mDNS reconnect triggers systematic flush to SQLite.

##   7: Multi-Device Cognitive Continuity

### Continuity Protocol

```
Android Device               Workstation Hub              Chrome Extension
  (AccessibilityService)    (Orchestrator)              (Shadow DOM Sidebar)
        |                           |                            |
        |---Telemetry JSON------→   |                            |
        |                           |                            |
        |                    (Run agent chain)                    |
        |                           |                            |
        |                    (Score candidates)                   |
        |                           |                            |
        |                    SHOW_CONTINUITY event               |
        |                           |----JSON over WebSocket--→   |
        |                           |                    (Render card)
        |                           |←---User feedback------------|
        |                           |
        |←---Update RLHF weights----|
```

**Steps:**
1. AccessibilityService captures active URL, location, scroll position
2. WebSocket streams ContextObject to orchestrator
3. Orchestrator evaluates routing rules, runs agent chain
4. Decision agent generates response + scores it
5. If score >= 40.0, sends SHOW_CONTINUITY JSON to laptop extension
6. Extension injects Shadow DOM sidebar (no style conflicts)
7. User clicks "Resume" → extension opens URL + auto-scrolls
8. Feedback sent back via WebSocket → RLHF weights adjusted

##   8: Empirical Performance Metrics

Tested against KPIs using 31 Gen Z survey  icipants + 8 prototype users.

| KPI | Target | Achieved | Evidence |
|-----|--------|----------|----------|
| Effort Reduction | ≥30% | 42% | User survey: fewer app checks |
| Task Completion | ≥90% | 94% | Behavioral logs: distraction-free workflow |
| AI Relevance | ≥85% | 87.5% | Prototype: suggestion quality scores |
| Satisfaction | ≥4.5/5 | 4.6/5 | Post-interaction ratings |
| Willingness to Pay | ≥60% | 68.2% | Survey: pay for context-aware wellness |
| False Interruptions | <15% | 11.2% | Dismissal rate from logs |
| Stress Reduction | >25% | 28.7% | Post-trial stress assessment |

##   9: Engineering Learnings

### What Worked

**Dual-Tier Reasoning Gates:** Heuristic checks on mobile filter 85% of telemetry before expensive LLM calls. Battery efficiency improved 60%.

**Interruption Scoring Threshold:** 40.0 cutoff reduced false interruptions from 60% (all prompts shown) to 11%. Users feel "supported" not "spammed."

**mDNS Zeroconf Pairing:** Hardcoded IPs fail on corporate/dorm networks. mDNS enabled out-of-box setup. No "enter backend IP" dialog.

**Single-Pass Orchestrator:** Custom `_run_chain()` executes agents once, deterministically, in <200ms. Eliminated latency from agentic loops.

**DistilRoBERTa not in Runtime:** Fine-tuning roberta-base offline for burnout prediction works well, but runtime agents use pure heuristics (sub-50ms) to guarantee latency. Trade model accuracy for speed + reliability.

### What Failed

**Direct LLM on Mobile CPU:** Phi-3 Mini on Android CPU: 15–45s latency + thermal throttling + 15% battery drain in 30min. Switched to local-on-device ONNX with Ollama fallback.

**Context-Free ChromaDB:** Raw keyword queries ("stressed") retrieved irrelevant memories. Solution: embed location + emotion + time-of-day metadata. Precision improved 45%→87%.

**Agentic Loops:** Agents spawning sub-agents → infinite loops, hallucination cascades, 5–15s latency. Switched to pre-determined chains. Deterministic <200ms.

**Coqui TTS Not Implemented:** Exlcluded as Android Security didn't allow running custom TTS in background.

## Summary

FRIDAY demonstrates empathetic, agentic AI is practical when agents own single domains, orchestration is deterministic, and local-first is non-negotiable. Scoring gates (thresholds) protect user attention. RLHF enables personalization without cloud sync.

**All components open-source. Zero proprietary dependencies.**