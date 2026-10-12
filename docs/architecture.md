# FRIDAY: System Architecture

**Problem Statement #5** — Designing Empathetic Intelligence UX for Everyday Life.

FRIDAY is a three-tier local compute mesh. All reasoning, memory, and inference runs on-device or on a local laptop hub. No cloud APIs. No PII ever leaves the private network boundary.


## 1. Architectural Philosophy

Most AI assistants are designed around a cloud backend: data flows out, a response flows in. FRIDAY inverts this model. The guiding constraints are:

- **Privacy by Default** — behavioral telemetry never crosses a third-party server
- **Passive First** — the system observes before it acts
- **Silence as a Feature** — intervention is suppressed unless mathematically justified
- **Graceful Degradation** — each tier fails safely into the tier below it

These constraints shape every architectural decision from sensor collection to UI rendering.


## 2. High-Level System Topology

The FRIDAY ecosystem is divided into three specialized tiers communicating asynchronously over WebSockets via mDNS local discovery or a private Tailscale/ZeroTier mesh VPN for cross-network operation.

<img width="467" height="659" alt="Screenshot from 2026-06-18 21-15-42" src="https://github.com/user-attachments/assets/22507370-112d-4dc2-a6ec-fabd9580b9b3" />



## 3. Tier 1 — Android Sensing Node

The Android application operates as an intelligent background sensory layer with built-in privacy protection and statistical baseline modeling.

### Signal Collection & Privacy Sanitizer

- **NotificationListenerService** — Captures notification metadata (timestamp, package ID, system category).
  - *Sensitive Blacklist*: Banking, UPI, 2FA authenticators, and medical apps have title replaced with `[Private Notification]` and content replaced with `Content suppressed by FRIDAY Privacy Guard`.
  - *OTP Redaction*: Universal regex pattern catches 4-8 digit verification pins and replaces them with `[REDACTED_SECURITY_CODE]`.
- **AccessibilityService** — Captures passive typing cadence without logging raw keystrokes.
  - *Domain-Only Trimming*: Strips paths, session tokens, and query strings from browser address bars (`https://$host`), discarding private URL parameters.
  - *Search Term Suppression*: Raw queries in the URL bar are suppressed and categorized as `Web Research (SEARCH)`.
  - *Domain Categorization*: Tags domains into `DEVELOPMENT`, `PRODUCTIVITY`, `COMMUNICATION`, `MEDIA_STREAMING`, and `RESEARCH_EDUCATION`.
- **Personal Baseline Engine (Welford's Algorithm)**:
  - Stored in a lightweight SQLite table (`personal_baseline`) in Room DB.
  - Computes online rolling mean ($\mu$), $M2$, and standard deviation ($\sigma$) in $O(1)$ space and $O(1)$ time.
  - Calculates personal $Z$-score deviations ($Z = \frac{x - \mu}{\sigma}$) to adapt dynamically to the user's personal baseline rather than using static thresholds.
- **Contextual Ambiguity Engine**:
  - Distinguishes between stressful context-switching vs productive deep-focus (`FLOW_STATE`). When active in development tools (GitHub, terminal, IDE) or research documents, rapid app switches are recognized as flow state and stress scores are appropriately discounted.

### Storage Optimization & Consent-Driven Model Download

- **Zero-Download Onboarding**: Initial app footprint is under 50 MB (37 MB) with immediate heuristic and statistical baseline functionality ready out of the box.
- **On-Demand Phi-3 Mini Download**: The 2.3 GB quantized neural model (`phi3_mini_int4.onnx` + weights) is downloaded only upon explicit user consent via Settings, with automatic fallback from local LAN compute hub to verified Hugging Face CloudFront CDN.

### Offline Fallback — Phi-3 Mini & Heuristic Safety Net

If the laptop hub goes offline, Android routes traffic to either:
1. On-device **Phi-3 Mini (INT4)** via ONNX Runtime Mobile (if downloaded), or
2. Statistical **Personal Baseline Heuristic Engine** with flow-state awareness.

All events during outages are buffered in an encrypted Room DB and flushed to the hub in strict write-order upon reconnection.



## 4. Tier 2 — Laptop Compute Hub

The hub is the reasoning core of FRIDAY. It runs a Python FastAPI server with LangGraph as the stateful orchestration layer.

### Local Model Matrix

- **Llama 3.1 8B** (Ollama, FP16/GGUF) — primary orchestrator, semantic memory synthesis, action generation
- **RoBERTa-Small** (ONNX, PEFT/LoRA) — cognitive load scoring from typing telemetry; invoked only when Android's heuristic gate fires
- **Whisper-Base** (whisper.cpp, C++ bindings) — sub-second voice transcription, bypasses Python GIL
- **all-MiniLM-L6-v2** (ChromaDB) — generates semantic embeddings of workspace states for memory retrieval
- **Coqui TTS** (PyTorch) — voice output for hands-free coaching

### LangGraph Multi-Agent Orchestration

Incoming telemetry arrives as a sanitized `ContextObject`. The State Router classifies the signal and dispatches it to the relevant agent cluster. All agents share a global state object — no linear pipeline, no state drops.

```
ContextObject
     │
     ▼
 State Router
     ├── High Stress (≥65)     → Emotion Agent + Wellbeing Agent
     ├── Notification Overload → Notification Agent + Emotion Agent
     ├── Active Task           → Task Agent + Memory Agent
     └── Context Shift         → Context Agent + Memory Agent
                                         │
                               Candidate Assembly
                                         │
                                  Decision Agent
                                         │
                             SCORE ≥ 40 → Execute Action
                             SCORE < 40 → Empathetic Silence
```
### Semantic Workspace Memory — ChromaDB

When a user leaves their laptop, the active workspace state is embedded into ChromaDB using `all-MiniLM-L6-v2`. On return, the Memory Agent retrieves the top-k semantic matches. Llama 3.1 processes these matches and generates a Context Card — it does not auto-restore anything. The user confirms.

**Sync Safety Protocol:**
1. Android reconnects → Room DB flush begins
2. Flush acknowledged and committed
3. Memory Agent invalidates any stale reads
4. Llama 3.1 reasoning begins only after sync lock releases

**Pruning:** Workspace embeddings not accessed in 30 days are expired automatically. Prevents ChromaDB bloat and stale context retrieval.

### Database Safety

A FastAPI lifecycle-managed Singleton prevents concurrent SQLite write conflicts during rapid server restarts. The startup handler acquires an exclusive lock before opening ChromaDB collections; the shutdown handler flushes and releases cleanly.

## 5. Tier 3 — Desktop Interaction Surface

The Chrome Extension connects to the hub via local WebSocket (`ws://localhost:8000`) and renders all UI inside an encapsulated Shadow DOM.

### Shadow DOM Isolation

The Shadow DOM ensures:
- Page-level stylesheets cannot break FRIDAY's interface
- FRIDAY's scripts cannot leak into or interfere with the host page
- Ghost Mode can suppress distracting DOM elements without touching the page's own structure

### What the Extension Does

- **Context Cards** — presents workspace restore prompts with `[Yes] / [No]` confirmation. Nothing is loaded without explicit user consent.
- **Ghost Mode** — on high cognitive-load detection, distracting visual elements are hidden. The workspace is shielded.
- **Tab Restoration** — if the user confirms a Context Card, the full task cluster (related tabs, open PDFs, active documents) is restored together.

<img width="499" height="749" alt="Screenshot from 2026-06-18 21-17-21" src="https://github.com/user-attachments/assets/daaf4b9d-8312-4717-be6c-9d9aec9766b3" />

## 6. The Decision Engine — Empathetic Silence

Every candidate action produced by LangGraph passes through a mathematical scoring gate before any output reaches the user.

```
SCORE = (E × 0.30) + (T × 0.25) + (M × 0.20) + (Q × 0.10) + (P × 0.15)
```

- **E** — Emotional relevance: stress score from RoBERTa (0–100)
- **T** — Timing: screen duration and battery state (0–100)
- **M** — Memory alignment: match quality to past workspace context (0–100)
- **Q** — Action quality: base score of the action type (0–100)
- **P** — 100 minus intrusiveness (rewards passive, quiet actions)

**If SCORE < 40 → Empathetic Silence.** The context is logged to SQLite. No notification, no prompt, no UI change. The system protects focus rather than competing with it.

### RLHF Learning Loop

User feedback on every action card is returned to the hub via WebSocket and applied to the scoring weights with time-decay:

- **Helpful** → boosts `wₑ` and `wₘ` weights
- **Dismissed** → reduces `wₚ` for the current session only; baseline restores after a time window
- Weights are renormalized every 10 interactions, decay-adjusted, maintaining a target sum of 1.0

Session-level weight changes never permanently alter the baseline. A stressful meeting cannot mute FRIDAY forever.

<img width="498" height="792" alt="Screenshot from 2026-06-18 21-17-54" src="https://github.com/user-attachments/assets/35c7d3c1-55d7-4ee8-8c27-e78a57b33a48" />


## 7. Cross-Network Resilience

FRIDAY operates across three network conditions without manual configuration:

- **Local Network (Primary)** — mDNS / Zeroconf broadcasts the hub's FastAPI port dynamically. Android and Chrome bind to it automatically. No hardcoded IPs.
- **Cross-Network (Secondary)** — Tailscale or ZeroTier private mesh VPN creates an end-to-end encrypted tunnel between phone and laptop. No PII passes through a third-party relay.
- **Offline (Fallback)** — Phi-3 Mini on Android handles local routing. Room DB buffers all events until the hub reconnects.


## 8. Privacy Architecture

Privacy is structural, not a setting.

- **No internet permission on Android** — the app cannot transmit data to any external server
- **All ML inference runs locally** — Llama 3.1, RoBERTa, Whisper, Phi-3 Mini all execute on local hardware
- **Content-free telemetry** — only behavioral signals are captured; message content, screen text, and media are never accessed
- **AES-256 local storage** — all buffered telemetry in Room DB and SQLite is encrypted at rest
- **No ngrok** — public reverse proxies are explicitly excluded; Tailscale/ZeroTier is the only cross-network path

## 9. Component Dependency Map

```
Android App
  └── NotificationListenerService
  └── AccessibilityService
  └── PII Sanitizer
  └── WebSocket Client (mDNS / Tailscale)
  └── LocalFallbackEngine.kt
        └── Phi-3 Mini (ONNX Runtime Mobile)
        └── Room DB (encrypted buffer)

Laptop Hub
  └── FastAPI Server
        └── LangGraph Orchestrator
              ├── Emotion Agent
              ├── Memory Agent  ←→  ChromaDB (all-MiniLM-L6-v2)
              ├── Context Agent
              ├── Wellbeing Agent
              ├── Task Agent
              └── Decision Agent
                    └── decision.py (scoring formula + RLHF)
        └── Llama 3.1 8B (Ollama)
        └── RoBERTa-Small (ONNX)
        └── Whisper-Base (whisper.cpp)
        └── Coqui TTS
        └── SQLite (KPI logs)

Chrome Extension
  └── WebSocket Client (localhost)
  └── Shadow DOM Sandbox
        ├── Context Card Component
        ├── Ghost Mode Controller
        └── Tab Restoration Handler
```
