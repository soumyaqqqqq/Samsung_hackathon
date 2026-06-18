# FRIDAY — Agentic AI

**Privacy-first, edge-compute, cross-device assistant.**
All processing runs locally. No cloud APIs.

## Models
 
| Model | Where It Runs | What It Does |
|---|---|---|
| Llama 3.1 8B | Laptop Hub | Main orchestrator + memory |
| Phi-3 Mini (INT4) | Android | Offline fallback only |
| RoBERTa-Small | Laptop Hub | Typing stress scoring (gated) |
| Whisper-Base | Laptop Hub | Voice transcription |
| all-MiniLM-L6-v2 | Laptop Hub | Workspace memory embeddings |
| Coqui TTS | Laptop Hub | Voice coaching output |


## How It Works

**1. Android collects signals** — typing speed, app usage, accessibility events.

**2. A lightweight heuristic checks for anomalies.** If nothing unusual, nothing happens. If stress or context shift is detected, the signal is sent to the laptop hub.

**3. The hub routes the signal** to the right agents via LangGraph:

- **Emotion Agent** — stress level from typing patterns
- **Memory Agent** — pulls relevant past workspace context
- **Context Agent** — detects location or device change
- **Wellbeing Agent** — checks long-term burnout risk
- **Task Agent** — checks current project progress
- **Decision Agent** — scores the candidate response

**4. The Decision Engine scores the response:**

```
SCORE = (E × 0.30) + (T × 0.25) + (M × 0.20) + (Q × 0.10) + (P × 0.15)
```
| Variable | Meaning |
|---|---|
| E | Emotional relevance (0–100) |
| T | Timing (screen time + battery state) |
| M | Memory match to past context |
| Q | Action quality |
| P | 100 minus intrusiveness |
 
**If SCORE < 40 → silent. No output. No interruption.**
 


**If SCORE < 40 → silent. No output. No interruption.**

 

## Memory

- **ChromaDB** stores workspace embeddings. On return from a break, it retrieves the top matching context and presents a **confirmation card** — user chooses to restore or not.
- **SQLite / Android Room** logs telemetry locally. On reconnect, the Room DB flush completes fully before any reasoning starts (prevents race conditions).
- **Pruning:** embeddings not accessed in 30 days are deleted automatically.

## Networking

- Local discovery via **mDNS / Zeroconf** — no hardcoded IPs.
- Cross-network fallback via **Tailscale or ZeroTier** private mesh VPN.
- **No ngrok.** No PII through third-party relays.
