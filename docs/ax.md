whiere you explain in detail how you utilizes open weight models and/or agentic development tools to implement your solution. Explain in detail your Agentic AI setup , Agentic workflows, Reasoning & planning pipelines, Tool use / tool chaining, Coding assistants, agents, harness, MCP servers, agents.md, skills, Memory / context handling, Multi-agent orchestration systems, etc. Please highlight from your experience - what worked and what did not work.



# Agentic AI Setup & Open Weight Models

This document details the Agentic AI architecture, open-weight models, reasoning pipelines, and memory handling systems utilized in the **FRIDAY** ecosystem to deliver an empathetic, privacy-first user experience.

## 1. Open Weight Models Utilized

To satisfy the problem statement's core requirements of **Privacy & Trust** and **Efficiency** (preventing battery drain), we completely avoided generic cloud API black-boxes. Instead, we implemented a localized, hybrid compute-hub strategy using state-of-the-art open weight models:

* **Primary Brain / Orchestrator (Llama 3.1 8B):** Served locally via **Ollama** on the Laptop Compute Hub. It processes aggregate contexts, semantic memory, and determines final cross-device actions.
* **Offline Fallback Agent (Phi-3 Mini INT4):** Quantized and run directly on the Android device via the **ONNX Runtime Mobile framework**. This provides zero-crash continuity, ensuring basic AI tasks and data buffering continue even if the laptop hub goes offline.
* **Sentiment & Behavioral Engine (RoBERTa-Small):** Fine-tuned via PEFT/LoRA to analyze linguistic fatigue (typing pace) and context, outputting a discrete numerical stress value (0.0 to 1.0) to drive empathetic responses.
* **Voice Transcription (Whisper Base/Small):** Bound via **whisper.cpp** for high-performance, near-native execution speed on the laptop CPU. It completely eliminates the Python GIL, delivering sub-second transcription latency without requiring GPU overhead.

## 2. Multi-Agent Orchestration System

Our backend relies on a sophisticated multi-agent orchestration pipeline powered by **LangGraph**. Instead of rigid sequential code, we treat AI tasks as a stateful graph where a global "State" dictionary is passed between specialized agent nodes.

**The Workflow:**

1. **Ingestion:** The Android Sensing Layer (Accessibility/Notification listeners) sends raw, sanitized `ContextObject` payloads via WebSockets to our FastAPI server.
2. **Routing (orchestrator.py):** The LangGraph pipeline catches the packet and routes it to specific nodes based on the data type (e.g., audio goes to VoiceAgent, text goes to SentimentAgent).
3. **Analysis:**
* *Sentiment Agent (RoBERTa):* Calculates user stress levels.
* *Memory Agent (ChromaDB):* Checks current workspace context and clusters semantic intent.


4. **Final State Generation:** The graph concludes by passing the aggregated context to the **Decision Engine**.

## 3. Reasoning & Planning Pipelines (The Decision Engine)

To prevent our AI from contributing to "micro-decision fatigue" (a core problem statement challenge), we built a deterministic reasoning constraint matrix in `decision.py`.

Before LangGraph fires an action, the pipeline calculates a weighted score (0 to 100):
`SCORE = (Emotional Relevance * 0.30) + (Timing * 0.25) + (Memory Alignment * 0.20) + (Action Quality * 0.10) + (Intrusiveness * 0.15)`

* **Action Output (Score $\ge$ 40):** The AI executes a tool command (e.g., sending a WebSocket frame to the Chrome Extension to trigger "Ghost Mode" or side-load related semantic browser tabs).
* **Silence Output (Score < 40):** The pipeline explicitly chooses to do nothing. This "empathetic silence" protects the user from unnecessary interruptions.

## 4. Memory & Context Handling

We employ a **Dual-Database Strategy** to manage agentic memory securely:

* **Vector Memory (ChromaDB):** Used for Semantic Workspace Clustering. ChromaDB stores raw contextual memories and embeddings so the Llama 3.1 model can instantly recall related tasks, allowing the system to restore a user's digital momentum after a break.
* **Relational Logging (SQLite):** Used to securely log deterministic system events (the `kpi_logs`). If the local connection drops, Android temporarily caches events using a Room Database buffer, flushing them to the laptop upon reconnection to prevent data loss.

## 5. Tool Use & Chaining

Our agents interact with the real world without requiring manual user input. The primary "Tool" our LLM controls is the **Laptop UI Layer (Chrome Extension)**.
Through a local WebSocket (`ws://localhost:8000/ws/laptop`), the LangGraph final state can command the extension's isolated Shadow DOM to alter CSS states, strip distracting active content, or compile cross-device clipboard payloads.

---

## 6. Developer Experience: What Worked & What Didn't

*(Note: Highlighted below are the authentic challenges and breakthroughs our team experienced while building this agentic architecture).*

**What Worked (The Successes):**

* **LangGraph for State Management:** Transitioning from linear Python scripts to LangGraph's node-based state routing made our multi-agent system infinitely more debuggable. When an agent failed, we knew exactly which node dropped the state.
* **C++ Bindings for Whisper:** Switching from the standard Python Hugging Face pipeline to `whisper.cpp` was a massive success. The latency reduction was incredible and saved laptop CPU resources for Llama 3.1.
* **Shadow DOM for UI Injection:** Letting the AI control an encapsulated Shadow DOM inside the Chrome Extension worked perfectly to prevent host websites from overriding our UI styles.

**What Didn't Work (The Failures & Pivots):**

* **ChromaDB File Corruption:** *Failure:* During rapid iteration and server restarts, we constantly corrupted our ChromaDB persistent storage because the SQLite locks weren't closing. *Fix:* We had to implement a graceful shutdown pattern hooking into FastAPI's lifespan events to release the database locks before the process died.
* **Hardcoded Networking:** *Failure:* Initially, we hardcoded the laptop's local IP address into the Android app. This failed immediately when routers reassigned IPs via DHCP. *Fix:* We had to pivot and implement native Android `Network Service Discovery (mDNS)` for autodetecting the backend, alongside an `ngrok` fallback for cross-network (5G) connectivity.
* **Running LLMs on Mobile:** *Failure:* Trying to run anything larger than a heavily quantized model on the Android device resulted in immediate thermal throttling and massive battery drain. *Fix:* We pivoted to the "Compute Hub" model (FastAPI laptop backend) and strictly limited the Android device to a 4-bit quantized Phi-3 Mini strictly for offline fallback.