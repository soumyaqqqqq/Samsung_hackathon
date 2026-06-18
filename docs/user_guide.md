# 📖 FRIDAY: User Experience Guide

### Comprehensive Guide to Your Ambient AI Workspace Partner

---

## Table of Contents
1. [Welcome to FRIDAY](#1-welcome-to-friday)
2. [Getting Started: The Daily Routine](#2-getting-started-the-daily-routine)
3. [Core Features & How They Work](#3-core-features--how-they-work)
4. [Understanding the Visual Interface](#4-understanding-the-visual-interface)
5. [Network Behavior & Offline Mode](#5-network-behavior--offline-mode)
6. [Privacy & Data: What Stays On Your Device](#6-privacy--data-what-stays-on-your-device)
7. [Settings & Customization](#7-settings--customization)
8. [Troubleshooting & FAQ](#8-troubleshooting--faq)


## 1. Welcome to FRIDAY

Most digital assistants wait for you to ask them a question. **FRIDAY is different.**

FRIDAY is an ambient, empathetic digital partner that lives across your phone and laptop at once. It understands your context, protects your focus, and rebuilds your workspace so you can stay in your "flow state" without cognitive overload — all while keeping every byte of analysis on your own devices.

This guide walks through setup, every core feature, what the interface is telling you, and how to troubleshoot things if they go sideways.

> **🖼️ Diagram spot — Hero/concept diagram:** A simple two-device illustration (phone + laptop) connected by a glowing line, with small icon callouts for "Focus Protection," "Digest," "Workspace Restore," and "Ghost Mode" radiating outward. This sets the mental model before the reader hits any details.

---

## 2. Getting Started: The Daily Routine

Because FRIDAY protects your privacy by processing everything locally, your devices need to be talking to each other before anything magical happens.

1. **Start your Laptop Hub** — open your terminal and run the FRIDAY backend. This wakes up your local AI brain.
2. **Open the Android App** — launch FRIDAY on your phone. It automatically discovers your laptop over the local Wi-Fi and connects silently in the background.
3. **Focus on your work** — that's it. Put your phone down. FRIDAY is now actively shielding your attention.

**First-Time Checklist:**
- [ ] Laptop hub running (check terminal for "FRIDAY is listening...")
- [ ] Phone and laptop on the same network
- [ ] Android app shows a connected status
- [ ] Notification access granted on phone (required for Empathetic Silence)
- [ ] Microphone permission granted (required for voice commands)

> **🖼️ Diagram spot — Pairing architecture:** A flow diagram showing Phone ⇄ Local Wi-Fi ⇄ Laptop Hub, with a small lock icon labeled "no cloud round-trip." This is the single most useful diagram in the whole guide — it answers "where does my data actually go?" at a glance, and removes the need to explain the handshake in prose.

---

## 3. Core Features & How They Work

### 🧘‍♂️ Empathetic Silence (Focus Protection)

**What it does:** Ever been deep into a difficult coding problem or essay, only to get interrupted by five buzzing group-chat messages?

**How you use it:** Nothing — it's passive. FRIDAY monitors your typing pace and active windows. When it detects high focus or high cognitive load, it enforces **Empathetic Silence**: non-urgent notifications on your phone are intercepted, silenced, and held for you until you resurface.

> **🖼️ Diagram spot — Decision flow:** A small flowchart: *Typing pace + active window* → *Focus score calculated* → *Threshold crossed?* → *Yes: hold notification / No: deliver normally*. Readers respond well to seeing the "if/then" logic instead of just being told to trust it.

### 📝 The Contextual Digest

**What it does:** When you finally take a break and pick up your phone, you won't see a stressful wall of 50 missed notifications.

**How you use it:** Unlock your phone after a long work session. FRIDAY instantly generates a 2–3 sentence AI summary of everything you missed (for example: *"Soumya sent 4 messages about the hackathon database, and you have a calendar reminder for 3:00 PM."*).

> **🖼️ Diagram spot — Before/after mockup:** Side-by-side phone screens — left shows a cluttered notification shade (50+ alerts), right shows a single clean digest card. This is the highest-impact "show, don't tell" moment in the whole guide.

### 🔄 Semantic Workspace Restoration

**What it does:** Moving from your phone back to your laptop usually means spending five minutes reopening the tabs, PDFs, and notes you need.

**How you use it:**
1. Sit back down at your laptop and click the **FRIDAY Chrome Extension**.
2. FRIDAY remembers what task you were working on before you left (e.g., "Neural Ethics Research").
3. Click **Restore Flow**. FRIDAY instantly side-loads your exact cluster of related tabs and documents, dropping you right back into the zone.

> **🖼️ Diagram spot — Sequence diagram:** Three-step horizontal flow: *Walk away (context saved)* → *Click Restore Flow* → *Tab cluster re-opens*. Could include a small thumbnail of "tab cluster" as a stacked-cards icon to make the abstraction tangible.

### 👻 Ghost Mode (Privacy & Burnout Shield)

**What it does:** FRIDAY actively measures your behavioral biometrics — specifically your typing rhythm and backspace frequency.

**How you use it:**
- **If you're burning out:** if your typing becomes erratic and error-prone, FRIDAY assumes you're highly stressed. The extension toggles into **Ghost Mode** (a dark "Block Navy" theme), hides distracting tabs, and gently suggests you step away.
- **If someone else uses your laptop:** because their typing rhythm doesn't match yours, FRIDAY instantly triggers Ghost Mode, locking down your sensitive workspace memory until you return.

> **🖼️ Diagram spot — State diagram:** A simple 3-node state machine: *Aware Mode → (stress detected OR rhythm mismatch) → Ghost Mode → (verified return) → Aware Mode*. This is a natural fit for a diagram since the feature is literally a state machine with two different triggers — worth showing both paths converging on the same "Ghost" state.

### 🎙️ Sub-Second Voice Commands

**What it does:** Hands-free control that actually responds instantly.

**How you use it:** Say the wake-word **"FRIDAY"** near your phone, followed by your request — for example, *"FRIDAY, remind me to email Thomas sir about the purchase order."* Because it uses a local C++ transcription engine, your voice is processed in milliseconds without ever leaving the device.

> **🖼️ Diagram spot — Pipeline diagram:** *Wake word detected → On-device C++ transcription → Intent parsed → Action executed*, with a "0 network calls" badge somewhere on the diagram to reinforce the privacy story.

---

## 4. Understanding the Visual Interface

FRIDAY uses color psychology on your laptop extension to communicate its current state without requiring you to read text:

| Color | State | What It Means |
|---|---|---|
| 🪻 Block Lilac | **Aware Mode** | FRIDAY is active, learning your context, and routing data normally. Your environment is relaxed. |
| 🌑 Block Navy | **Ghost Mode** | FRIDAY has detected stress, erratic typing, or an unrecognized user. The interface goes dark, and non-essential tabs are hidden to protect you. |
| 🟢 Block Lime | **Interaction Mode** | A proactive UI card needs your attention — for example, asking if you want to restore a workspace cluster. |

> **🖼️ Diagram spot — Color state legend:** This table is a strong candidate to become a visual instead of a table: three colored chips (lilac, navy, lime) arranged in a small triangle or cycle, each with a one-line trigger underneath. Because this section already exists as a table, converting it into a diagram is mostly a styling exercise, not new content.

---

## 5. Network Behavior & Offline Mode

**"What happens if my Wi-Fi drops or I leave my house?"**

You never have to worry about FRIDAY crashing or losing your data:

- **Network Switching:** if you switch from Wi-Fi to 5G, FRIDAY automatically bridges the connection to your laptop via a secure tunnel.
- **Total Offline Fallback:** if you're on a plane or your laptop dies, the Android app seamlessly routes your data to its own **on-device local AI (Phi-3 Mini)**. FRIDAY keeps generating summaries and logging your context, syncing back to your laptop the moment you reconnect.

> **🖼️ Diagram spot — Connectivity decision tree:** *Laptop reachable?* → Yes: use laptop hub. No: → *Any network at all?* → Yes (5G/Wi-Fi): secure tunnel bridge. No: fall back to on-device Phi-3 Mini, queue for sync. This is the most "architecture-flavored" moment in the guide and benefits from a real flowchart rather than prose.

---

## 6. Privacy & Data: What Stays On Your Device

FRIDAY's core promise is that **everything stays on your device.**

**Processed locally, always:**
- Typing rhythm and focus-state detection
- Notification triage and digest generation
- Voice transcription and intent parsing
- Workspace/tab clustering and restoration logic

**Never sent to an external server:**
- Raw audio from voice commands
- Notification content
- Typing biometrics
- Browsing/tab history

> **🖼️ Diagram spot — Local-only data flow:** A simple boundary diagram: a dashed box labeled "Your Devices" containing phone + laptop icons and all the processing arrows looping inside it, with a crossed-out cloud icon outside the box. Readers (and hackathon judges) tend to trust a one-glance privacy diagram more than a paragraph of claims.

---

## 7. Settings & Customization

While FRIDAY is designed to work well out of the box, a few things are worth knowing how to adjust:

- **Focus sensitivity:** how aggressively typing pace and active-window signals trigger Empathetic Silence.
- **Digest frequency:** whether you get a digest only after long sessions, or after every break.
- **Ghost Mode triggers:** tune how sensitive the typing-rhythm mismatch detection is, especially on shared devices.
- **Wake word sensitivity:** adjust how easily "FRIDAY" is picked up in noisy environments.

*(If these aren't yet exposed as toggles in your current build, this section doubles as a roadmap for what to expose next.)*

---

## 8. Troubleshooting & FAQ

**FRIDAY on my phone says it isn't connected to my laptop.**
- Confirm the laptop hub terminal process is still running.
- Confirm both devices are on the same Wi-Fi network.
- Restart the Android app to re-trigger discovery.

**Empathetic Silence isn't holding any notifications.**
- Check that notification access is granted to FRIDAY in your phone's settings.
- Make sure you're in a sustained typing session — brief bursts may not cross the focus threshold.

**Ghost Mode triggers too often when it's just me.**
- Your typing rhythm naturally drifts with fatigue, posture, and time of day. Lower the Ghost Mode sensitivity, or give it a few sessions to recalibrate.

**Restore Flow isn't bringing back the right tabs.**
- Make sure the Chrome Extension was active during your original session — FRIDAY can only restore clusters it saw you create.

**Voice commands aren't responding.**
- Confirm microphone permission is granted.
- Try the wake word in a quieter environment — sensitivity is tunable in Settings.


