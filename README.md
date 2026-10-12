# Friday : An Ambient Intelliegence


* **Problem Number** — 5
* **Problem Statement** — Designing Empathetic Intelligence User Experience for Everyday Life
* **Team name** — Team Stack Overflow
* **Team members** — Soumya Gupta, Nirvik Goswami
* **Institute** — Vellore Institute of Technology Chennai
* **Final Presentation** — https://drive.google.com/file/d/1SHavfFVWFOdMEpJ8ktXQreIBMSdtjZbs/view?usp=sharing
* **Demo Video** — https://www.youtube.com/watch?v=_jL1IqCfOOw
* **Setup & Reproducibility Video** — https://youtu.be/uFKSZyt4N3U


## Project Overview

**FRIDAY** is an ambient intelligence layer for Android that detects cognitive overload and burnout signals in real time — and responds by doing less, not more. Rather than adding another notification to an already overwhelmed user, FRIDAY enforces silence when it matters most.

The system captures passive behavioral signals (typing speed, error rate, notification backlog, time-of-day, session length) and produces a continuous urgency score. When that score drops below a threshold, the system enters **empathetic silence** — deferring non-critical prompts, suppressing low-value alerts, and reducing interface friction.

### Key technical differentiator

A fine-tuned RoBERTa regression model replaces heuristic scoring entirely. Trained on synthetic Android telemetry mapped to a `[0, 1]` burnout score, the model runs on-device via ONNX Runtime — no cloud call, no latency, no data exfiltration. The decision to act or stay silent happens in under 80 ms.



## Project Artefacts

* **Technical Documentation** — [docs/](./docs/) — architecture, agent design, API reference, and user guides
* **Agentic Setup (read this first)** — [docs/ax.md](./docs/ax.md) — LangGraph orchestration, memory strategy, open-weight model choices, and implementation notes
* **Source Code** — [src/](./src/) — Android app, FastAPI backend, Chrome Extension

### Models used

| Model | Role |
|---|---|
| Fine-tuned RoBERTa (ours) | On-device burnout regression (ONNX) |
| Phi-3 Mini | Offline mobile reasoning fallback |
| Llama 3.1 8B | Backend orchestration and summarisation |
| whisper.cpp | Zero-latency voice transcription (C++ binding) |
| Coqui TTS | Empathetic voice output |
| all-MiniLM-L6-v2 | Semantic embeddings for context retrieval |

### Datasets used


**Dataset published**: [Rabbit-bot/FRIDAY-Synthetic-Burnout-Telemetry](https://huggingface.co/) — Apache 2.0

#### Reference Dataset

| Dataset | Used for |
|---|---|
| WESAD | Physiological stress baseline |
| StudentLife | Longitudinal burnout pattern reference |
| SWELL-KW | Cognitive load and task-switch modeling |
| ExtraSensory | Activity and context signal calibration |
| FRIDAY Synthetic Telemetry (ours) | RoBERTa fine-tune training set |

 #### Due to permission requirement and dataset size we were unable use the above dataset.


---

## Architecture

FRIDAY is a three-layer distributed system designed so the critical path never leaves the device.

### Layer 1 — Signal capture & Privacy Guard (Android)

`NotificationListenerService` and `AccessibilityService` collect behavioral telemetry under strict on-device privacy guarantees:
- **Privacy Sanitizer**: Sensitive packages (banking, 2FA authenticators, health/fitness) have notification text completely suppressed (`[Private Notification]`). Real-time regex redaction eliminates OTPs and verification pins (`[REDACTED_SECURITY_CODE]`).
- **Domain-Only URL Trimming**: Browser telemetry strips all paths, tracking tokens, and query parameters, retaining only root hosts and high-level productivity categories (`DEVELOPMENT`, `PRODUCTIVITY`, `COMMUNICATION`). Raw search queries are never logged.
- **Personal Baseline Engine**: Local SQLite database tracks rolling mean ($\mu$) and standard deviation ($\sigma$) per user using Welford's algorithm ($O(1)$ space and time). Real-time stress scoring evaluates personal Z-score deviations instead of arbitrary static thresholds.
- **Contextual Ambiguity Engine**: Differentiates stressful context-switching from productive deep-focus (`FLOW_STATE`) during development and research tasks.
- **Storage & Battery Optimization**: Base APK is kept strictly under 50 MB (37 MB) via zero-download instant onboarding. The 2.3 GB Phi-3 Mini ONNX neural fallback is downloaded on-demand with explicit user consent over verified Hugging Face CDN channels. Three runtime power states (Ghost, Aware, Active) protect battery longevity.

### Layer 2 — Intelligence (Compute Hub)

A FastAPI backend runs a LangGraph multi-agent pipeline with four specialized agents: Emotion, Burnout, Memory, and Context. Each agent processes its domain independently; a Fusion agent combines their outputs into a single intervention decision.

### Layer 3 — Experience (Cross-device)

A Chrome Extension connects to the Compute Hub over secure WebSockets. It uses an isolated Shadow DOM so it never interferes with the host page's styles or scripts. When the hub is unreachable, Phi-3 Mini activates locally via ONNX Runtime within 200 ms.


## Measured performance

| Metric | Result |
|---|---|
| Base APK install footprint | < 50 MB (37 MB) |
| Burnout model inference latency | < 80 ms on-device |
| Offline fallback activation time | < 200 ms |
| Cross-device workflow handoff | < 1.2 s |
| Additional battery consumption | < 3% |
| RAM footprint (Android service) | ~45 MB |

> *Figures measured on a Galaxy S23 (Snapdragon 8 Gen 2) running Android 14 with the compute hub on a local LAN.*


## Key features

**Privacy-first by design** — Strict notification suppression for banking/authenticators, universal OTP redaction, domain-only web context trimming, and AES-256 encrypted local storage. Behavioral telemetry never leaves the local network.

**Empathetic Personal Baseline** — Replaces hardcoded stress assumptions with personalized Z-score deviation tracking using Welford's algorithm. Detects flow states during coding and research without generating false overload alerts.

**Empathetic silence engine** — Instead of firing another notification, FRIDAY suppresses low-value interruptions when the user's burnout score exceeds a threshold. The system actively reduces cognitive load rather than contributing to it.

**Zero-Download Instant Onboarding** — Lightweight installation (<50MB) with rule-based statistical intelligence ready immediately out of the box; heavy 2.3 GB neural weights downloaded on demand with user consent.

**Semantic workspace memory** — ChromaDB stores vectorised session context so FRIDAY can restore a complex multi-tab workflow after a break without asking the user to reconstruct it manually.

**Dynamic power states** — Three operating modes (Ghost, Aware, Active) trade capability for battery life based on user activity. Ghost Mode draws negligible power while still logging passive signals.

## Innovation and impact

### Why this is technically novel

Most ambient intelligence systems are cloud-dependent — they offload inference to keep the device lightweight, at the cost of latency and privacy. FRIDAY inverts this: the critical scoring model runs entirely on-device via ONNX, while the cloud backend handles only non-urgent reasoning tasks. The result is a system that can make real-time decisions even in airplane mode.

Using `whisper.cpp` C++ bindings eliminates Python GIL bottlenecks, achieving sub-second voice round-trips that pure Python implementations cannot match on mobile hardware.

### Alignment with Samsung's ecosystem

FRIDAY integrates naturally with Samsung Health SDK (physiological baselines), Galaxy Continuity (cross-device handoff), and Knox (enterprise security policy). The edge-compute architecture scales with device hardware — no server costs grow with user count.

### Market context

Digital wellness and productivity tooling for Gen Z is a $10B+ market with no dominant platform-level player. FRIDAY's competitive position is the combination of privacy-by-design and hardware-optimized local inference — neither of which generic cloud API products can replicate.


## Attribution

Built from scratch using the following open-source technologies:

* **ChromaDB** — local vector memory
* **Ollama** — localized LLM serving
* **whisper.cpp** — high-performance audio transcription
* **ONNX Runtime** — on-device model inference
* **Android Jetpack** — Room, WorkManager, Compose


## License

Apache License 2.0 — see [LICENSE](./LICENSE) for details.


## Samsung EnnovateX 2026

FRIDAY is a proof that ambient intelligence does not require surveillance. By keeping the decision loop on-device and enforcing silence as a first-class response, it demonstrates that empathetic AI means knowing when not to act — not just when to act faster.
