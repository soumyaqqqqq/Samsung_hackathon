# FRIDAY

**Problem Statement Number** - 5
**Problem Statement Title** - Designing Empathetic Intelligence User Experience for Everyday Life
**Team name** - Team Stack Overflow
**Team members (Names)** - Soumya Gupta, Nirvik Goswami
**Institute/College Name** - Vellore Institute of Technology Chennai, Chennai

**Final Presentation Google Drive Link** - [Insert Google Drive PDF Link Here]
**Full Submission Demo Video Link** - [Insert YouTube Link Here]
**Setup & Result Reproducibility Video Link** - [Insert YouTube Link Here]

---

## Project Artefacts

* **Technical Documentation** - Please see the `docs/` folder for all technical details, including our Tech Stack, OSS libraries used, technical architecture, implementation details, installation instructions, and user guide with screenshots.
* **[Important] Agentic Setup** - Please see `docs/ax.md` for a detailed explanation of how we utilized open weight models, agentic workflows, multi-agent orchestration systems (LangGraph), memory handling (ChromaDB), and our experiences on what worked and what didn't.
* **Source Code** - All developed project source codes, training scripts, and benchmark evaluation codes are located in the `src/` folder.
* **Models Used**:
* [Phi-3 Mini](https://huggingface.co/microsoft/Phi-3-mini-4k-instruct) - Offline mobile inference
* [Llama 3.1 8B](https://huggingface.co/meta-llama/Meta-Llama-3.1-8B) - Backend reasoning & Orchestration
* [WhisperFlow](https://huggingface.co/) - Voice transcription
* [Coqui TTS](https://huggingface.co/) - Voice output
* [all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2) - Semantic embeddings


* **Models Published** - [Insert Link if applicable, or state "N/A"]
* **Datasets Used**:
* [WESAD] - Physiological stress detection
* [StudentLife] - Smartphone behavior and stress
* [SWELL-KW] - Burnout and workload analysis
* [ExtraSensory] - Context and activity recognition
* [K-EmoPhone] - Notification overload analysis
* [FSD50K] - Environmental sound stress detection
* [Sleep Health Dataset] - Fatigue and sleep modeling


* **Datasets Published** - [Insert Link if applicable, or state "N/A"]

---

## Attribution

This project references and builds upon concepts from the following open-source technologies:

* **ChromaDB**: Used for local vector memory and semantic workspace clustering.
* **Ollama**: Used for serving local LLMs without relying on cloud APIs.
* **AgentScope / LangGraph**: Referenced for our multi-agent orchestration pipeline.
* **mobile-use**: Referenced for accessibility and system-level hooking strategies.
* **WhisperFlow & Coqui TTS**: Utilized for localized speech-to-text and text-to-speech without network latency.

---

---

# Project Overview: FRIDAY

FRIDAY is an AI-powered empathetic ambient intelligence platform designed to reduce cognitive overload for Gen Z and Gen Alpha users in hyper-connected digital environments.

Unlike traditional assistants that react only to commands, FRIDAY continuously understands user context, emotional state, behavioral patterns, and environmental signals across devices to proactively assist users with minimal interruption. The system integrates behavioral sensing, contextual AI agents, semantic memory, and adaptive decision scoring to create a personalized, privacy-first, cross-device experience.

### Key Goals

* Reduce cognitive load & minimize notification overload
* Improve task continuity across digital environments
* Detect stress and burnout signals proactively
* Deliver context-aware proactive assistance
* Maintain absolute privacy through local-first AI processing

## Core Innovation: Adaptive Behavioral Scoring Engine

FRIDAY’s central innovation is its dynamic behavioral scoring engine. Before deciding whether to respond to a prompt or interrupt the user, the engine evaluates emotional relevance, timing sensitivity, historical memory alignment, interruption cost, action quality, and environmental context.

**The Decision Formula:**
`SCORE = (Emotional_Relevance × 0.30) + (Timing × 0.25) + (Memory_Alignment × 0.20) + (Action_Quality × 0.10) + (Intrusiveness × 0.15)`

* **SCORE < 40** → System remains silent (Protects user from micro-decision fatigue).
* **SCORE ≥ 40** → Response shown / Action triggered.

## System Architecture

FRIDAY uses a distributed three-layer architecture:

### 1. Signal Layer (Android)

Collects contextual and behavioral signals from the user ecosystem via `NotificationListenerService`, `AccessibilityService`, and `ForegroundService`.

* **Inputs:** Notifications, app switching, typing cadence, typo frequency, calendar events, screen time, battery state, and environmental audio.
* **Buffering:** Uses Room Database for local caching to prevent data loss.

### 2. Intelligence Layer (Compute Hub)

Specialized AI agents process user context asynchronously:

* **Emotion Agent:** Stress estimation
* **Burnout Agent:** Long-term fatigue detection
* **Memory Agent:** Semantic memory retrieval via ChromaDB
* **Context Agent:** Environment understanding
* **Notification Agent:** Smart interruption filtering
* **Decision Agent:** Response scoring and prioritization
* **Wellbeing Agent:** Emotional support and workload balancing

### 3. Experience Layer (Cross-Device)

Delivers empathetic interactions across smartphones, laptops, and browsers. Outputs include continuity prompts, notification summaries, adaptive reminders, "smart silence", and synchronized workflow recovery.

## Technology Stack

| Component | Technology |
| --- | --- |
| **Android App** | Kotlin |
| **Backend Framework** | FastAPI (Python) |
| **Local AI Runtime** | ONNX Runtime Mobile |
| **LLMs** | Phi-3 Mini (Device), Llama 3.1 (Hub) |
| **Vector Database** | ChromaDB |
| **Relational Storage** | SQLite |
| **Communication** | Secure WebSockets |

## Privacy, Security & Reliability

FRIDAY follows a strict privacy-first architecture. Sensitive raw behavioral data never leaves the local device network without encryption.

* **Security:** AES-256 encrypted local storage, Android Keystore session management, event hashing, and encrypted WebSocket communication.
* **Offline Fallback:** If backend connectivity fails, local on-device **Phi-3 Mini** inference activates automatically alongside Room database buffering. Automatic sync occurs upon reconnection.
* **Battery Optimization:** Features dynamic states including Ghost Mode (Minimal sensing), Aware Mode (Balanced), and Active Mode (Full capability).

## KPI Evaluation Targets

* **Effort Reduction:** ≥ 30%
* **Task Completion Rate:** ≥ 90%
* **AI Recommendation Quality:** ≥ 85%
* **User Satisfaction:** ≥ 4.5 / 5
* **Willingness to Pay:** ≥ 60%
*(Additional tracked internal KPIs include Notification Acceptance Rate, Stress Reduction Score, Burnout Prediction Accuracy, False Interruption Rate, and Energy Efficiency).*