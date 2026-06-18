# FRIDAY: Innovation & Novelty

**Problem Statement #5** — Designing Empathetic Intelligence UX for Everyday Life.

Most AI assistants wait to be asked. They are reactive, cloud-dependent, and blind to how the user actually feels. FRIDAY is none of these things. It is a passive, fully local system that reads behavioral signals — not content — and decides in real-time whether to help or stay silent.

---

## 1. The Empathetic Silence Engine

Most systems measure success by engagement. FRIDAY measures success by the quality of its interventions — including the decision not to intervene.

Every candidate response passes through a mathematical decision gate before any output is produced:

```
SCORE = (E × 0.30) + (T × 0.25) + (M × 0.20) + (Q × 0.10) + (P × 0.15)
```

- **E** — Emotional relevance: current stress level (0–100)
- **T** — Timing: screen duration and battery state (0–100)
- **M** — Memory alignment: match to past workspace context (0–100)
- **Q** — Action quality: base score of the recommended action (0–100)
- **P** — 100 minus intrusiveness (rewards quiet, passive actions)

**If SCORE < 40 → output is fully suppressed.** The context is logged silently. No notification. No prompt. No interruption. This is not a failure state — it is the system correctly choosing silence over noise.

---

## 2. Passive Behavioral Biometrics — Stress Without Surveillance

FRIDAY detects emotional state without reading a single word the user types.

A fine-tuned RoBERTa-Small model (ONNX, Hub-side) analyzes passive typing telemetry only:

- **Typing speed and rhythm** — deviations from personal baseline
- **Backspace frequency** — error rate as a stress proxy
- **Notification dismissal cadence** — urgency and attention patterns

This produces a continuous cognitive load score (0.0–1.0) with zero content exposure. No messages are read. No screen is captured. The signal is purely behavioral.

On high-stress detection, the Chrome Extension activates **Ghost Mode** — the Shadow DOM isolates the workspace and suppresses distracting browser elements automatically, without user input.

---

## 3. Semantic Workspace Continuity

Standard device continuity hands off one active tab. FRIDAY reconstructs the entire cognitive context.

When a user leaves their laptop, the full workspace state is embedded into a local ChromaDB vector store using `all-MiniLM-L6-v2`. On return, the Memory Agent retrieves the top-k semantic matches across all prior sessions and presents a confirmation card:

> *"Restore your Neural Ethics research workspace? [Yes] [No]"*

- If confirmed: related tabs, active documents, and open PDFs are restored together as a cluster.
- If declined: the system stays silent and waits.
- Nothing is restored without explicit consent.

This eliminates the 5–10 minute cognitive ramp-up cost of context switching and returns the user directly to flow state.

---

## 4. Local Compute Mesh — No Cloud, No Single Point of Failure

FRIDAY is a distributed local inference network across two hardware tiers:

- **Laptop Hub** — Llama 3.1 8B runs orchestration and memory synthesis; Whisper.cpp handles voice transcription with sub-second latency by bypassing the Python GIL entirely.
- **Android Fallback** — when the hub is unreachable, Phi-3 Mini (INT4, ONNX Runtime Mobile) takes over local routing on-device. Telemetry is buffered in an encrypted Room DB.
- **Sync Safety** — on reconnect, the Room DB flushes in strict write-order before any reasoning resumes, preventing race conditions on stale data.
- **Private Networking** — cross-network fallback uses Tailscale or ZeroTier mesh VPN. No data passes through a third-party relay at any point.

The system degrades gracefully. If the hub goes offline, the phone does not crash — it routes locally and queues until the connection returns.

---

## Why FRIDAY Is Different

Standard assistants are designed to maximize interaction. FRIDAY is designed to minimize unnecessary interaction.

- **Trigger model** — passive and continuous, not prompt-driven
- **Data handling** — 100% local inference, zero cloud dependency
- **Emotion detection** — typing telemetry only, no content access
- **Workspace restore** — full semantic task cluster with user consent, not a single tab
- **Intervention logic** — suppresses all output unless SCORE ≥ 40
- **Offline resilience** — full fallback to on-device model when hub is unreachable
- **Privacy guarantee** — no internet permission required; all ML runs on local hardware