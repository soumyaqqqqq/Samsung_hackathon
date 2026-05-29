Problem Statement Title

Designing Empathetic Intelligence User Experience for Everyday Life

⸻

Team Details

Team Name:
Team Stack Overflow

Team Members:

* Nirvik Goswami
* Soumya Gupta

Institute/College Name:
Vellore Institute of Technology Chennai,Chennai

⸻

Overview

FRIDAY is an AI-powered empathetic ambient intelligence platform designed to reduce cognitive overload for Gen Z and Gen Alpha users in hyper-connected digital environments.

Unlike traditional assistants that react only to commands, FRIDAY continuously understands user context, emotional state, behavioral patterns, and environmental signals across devices to proactively assist users with minimal interruption.

The system integrates behavioral sensing, contextual AI agents, semantic memory, and adaptive decision scoring to create a personalized, privacy-first, cross-device experience.

FRIDAY aims to:

* Reduce cognitive load
* Improve task continuity
* Minimize notification overload
* Detect stress and burnout signals
* Deliver context-aware proactive assistance
* Maintain privacy through local-first AI processing

⸻

Key Features

Empathetic Behavioral Intelligence

FRIDAY continuously analyzes:

* typing cadence
* app switching patterns
* notification overload
* emotional tone
* workload pressure
* sleep and fatigue indicators
* historical behavioral trends

to estimate user stress and cognitive load in real time.

⸻

Adaptive AI Layer

The system dynamically adapts to:

* user habits
* emotional trends
* productivity cycles
* interruption tolerance
* historical interactions

No static assistant personality is used.

⸻

Cross-Device Continuity

FRIDAY operates across:

* Android smartphones
* laptops/desktops
* browser environments

allowing seamless continuity between tasks and devices.

⸻

Proactive Assistance

Examples:

* summarize unread notifications after inactivity
* suppress low-priority interruptions during stress
* prioritize urgent tasks during deadlines
* recommend focus sessions during burnout risk
* restore previous workflows and browsing context

⸻

Offline AI Support

If network connectivity is unavailable:

* on-device Phi-3 Mini inference activates automatically
* local memory retrieval remains operational
* no user disruption occurs

⸻

System Architecture

FRIDAY uses a distributed three-layer architecture.

⸻

1. Signal Layer

Collects contextual and behavioral signals from the user ecosystem.

Inputs

* notifications
* app usage
* app switching
* typing cadence
* typo frequency
* calendar events
* location
* screen time
* battery state
* voice tone
* weather
* environmental audio

Android Services

* NotificationListenerService
* AccessibilityService
* ForegroundService
* Room Database Buffering

⸻

2. Intelligence Layer

Specialized AI agents process user context.
Emotion Agent  Stress estimation

Burnout Agent Long-term fatigue detection

Memory Agent Semantic memory retrieval

Context Agent Environment understanding

Notification Agent Smart interruption filtering

Decision Agent Response scoring and prioritization

Wellbeing Agent Emotional support and workload balancing
------------------------

3. Experience Layer

Delivers empathetic interactions across devices.

Outputs

* continuity prompts
* notification summaries
* adaptive reminders
* smart silence
* proactive focus assistance
* contextual recommendations
-----------------------
Core Innovation

Adaptive Behavioral Scoring Engine

FRIDAY’s central innovation is its dynamic behavioral scoring engine.

The engine evaluates:

* emotional relevance
* timing sensitivity
* historical memory alignment
* interruption cost
* action quality
* environmental context

before deciding whether the system should respond.

Decision Formula

SCORE =
(Emotional_Relevance × 0.30) +
(Timing × 0.25) +
(Memory_Alignment × 0.20) +
(Action_Quality × 0.10) +
(Intrusiveness × 0.15)

Threshold Logic

* SCORE < 40 → system remains silent
* SCORE ≥ 40 → response shown

This prevents unnecessary interruptions and notification fatigue.
-------------------------

Technology Stack
Component	Technology
Android   App	Kotlin
Backend Framework	FastAPI
Local AI Runtime	ONNX Runtime
LLMs	Phi-3 Mini, Llama 3.1
Vector Database	ChromaDB
Relational Storage	SQLite
Cloud Database	Firebase / MongoDB
Speech Recognition	WhisperFlow
Text-to-Speech	Coqui TTS
Communication	WebSockets
Embeddings	all-MiniLM-L6-v2
-------------
Models Used

Model	Purpose
Phi-3 Mini	Offline mobile inference
Llama 3.1 8B	Backend reasoning
WhisperFlow	Voice transcription
Coqui TTS	Voice output
all-MiniLM-L6-v2	Semantic embeddings
-------------
Datasets Used

Dataset	Usage
WESAD	Physiological stress detection
StudentLife	Smartphone behavior and stress
SWELL-KW	Burnout and workload analysis
ExtraSensory	Context and activity recognition
K-EmoPhone	Notification overload analysis
FSD50K	Environmental sound stress detection
Sleep Health Dataset	Fatigue and sleep modeling
Model	Purpose
Phi-3 Mini	Offline mobile inference
Llama 3.1 8B	Backend reasoning
WhisperFlow	Voice transcription
Coqui TTS	Voice output
all-MiniLM-L6-v2	Semantic embeddings
------------

Privacy & Security

FRIDAY follows a privacy-first architecture.

Security Measures

* AES-256 encrypted local storage
* Android Keystore session management
* local-first inference
* encrypted WebSocket communication
* semantic memory compression before upload
* event hashing and audit logging

Sensitive raw behavioral data never leaves the device without encryption.
------------------
Reliability Features

Offline Fallback

If backend connectivity fails:

* local Phi-3 Mini inference activates
* buffered events stored in Room database
* automatic synchronization occurs on reconnection

Battery Optimization

Mode	Behavior
Ghost Mode	Minimal sensing
Aware Mode	Balanced sensing
Active Mode	Full AI capability
-----------------
KPI Evaluation
FRIDAY is evaluated using both qualitative and quantitative metrics.
KPI	Target
Effort Reduction	≥ 30%
Task Completion Rate	≥ 90%
AI Recommendation Quality	≥ 85%
User Satisfaction	≥ 4.5 / 5
Willingness to Pay	≥ 60%

Additional System KPIs

Notification Acceptance Rate
Measures relevance of proactive suggestions.
Stress Reduction Score
Measures reduction in user stress over time.
Burnout Prediction Accuracy
Measures correctness of burnout detection.
False Interruption Rate
Measures unnecessary AI interruptions.
Context Understanding Accuracy
Evaluates environmental and behavioral understanding.
Energy Efficiency
Measures CPU, battery, and inference overhead.

User Testing Plan

Qualitative Testing

8–10 participants:

* emotional usability interviews
* stress and interruption feedback
* contextual experience analysis

⸻

Quantitative Survey

30–50 participants:

* workload reduction analysis
* satisfaction scoring
* recommendation relevance scoring
* willingness-to-pay evaluation

⸻

Proof of Concept

Android Prototype

* real-time signal sensing
* encrypted WebSocket streaming
* local fallback inference
* notification summarization

⸻

Backend Prototype

* multi-agent orchestration
* semantic memory retrieval
* response scoring engine
* KPI audit logging

⸻

Laptop Extension

* cross-device continuity prompts
* contextual sidebar assistance
* synchronized workflow recovery

⸻

Future Scope

* smartwatch integration
* multimodal emotional reasoning
* federated personalization learning
* Samsung ecosystem integration
* predictive cognitive scheduling
* adaptive mental wellbeing support

⸻

Open Source Attribution

This project builds upon and references several open-source technologies and research systems.

Referenced Projects

* AgentScope
* mobile-use
* ChromaDB
* Ollama
* WhisperFlow
* Coqui TTS

All referenced tools retain their original licenses and attribution.

⸻

Conclusion

FRIDAY transforms AI from a reactive assistant into an empathetic ambient intelligence system capable of understanding behavioral context, emotional state, and cognitive pressure in real time.

By combining adaptive AI agents, semantic memory, proactive assistance, and privacy-first local intelligence, FRIDAY creates a seamless and emotionally aware computing experience designed for the next generation of users.

