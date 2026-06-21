FRIDAY Technical Documentation
1. Project Overview
FRIDAY

FRIDAY is a privacy-first multi-agent wellbeing assistant designed for Samsung devices.

Unlike traditional assistants that react only to explicit commands, FRIDAY continuously evaluates behavioral signals, emotional state, workload patterns, and contextual information to provide proactive support while minimizing cognitive overload.

The system operates locally using a hybrid architecture consisting of:

Context Awareness Engine
Emotion Detection Engine
Semantic Memory System
Burnout Prediction Framework
Decision Intelligence Layer


2.System Architecture

![alt text](image-3.png)

3.Core Technical Innovation
Instead of relying on a single LLM prompt, FRIDAY decomposes reasoning into specialized agents.



![alt text](image.png)

4.Emotion Detection Engine

The Emotion Agent estimates a real-time stress score using behavioral signals collected from the device.

Inputs are taken from :
1.App Switching Frequency
2.Notification Volume
3.Typing Cadence
4.Typographical Error Rate
5.Screen-On Duration
6.On-Device Stress Model Output

Weighted Stress Formula:
Stress Score =0.20 × App Switches +0.20 × Notifications +0.20 × Typo Rate +0.15 × Typing Cadence +0.15 × Screen Time +0.10 × On-Device Model

![alt text](image-1.png)

5.Context Awareness Engine
The Context Agent enriches raw sensor information.

Context Signals
Location
Time of Day
Battery Status
Charging State
Device Usage Pattern

![alt text](image-2.png)

6. Semantic Memory System
Long-Term Memory Architecture

FRIDAY stores significant user episodes in a local ChromaDB vector database.

![alt text](image.png)
This allows FRIDAY to remember similar past situations and adapt recommendations accordingly.

7. Burnout Prediction Framework

The Burnout Agent predicts long-term fatigue risk.

Inputs
Historical Stress Logs
Screen Usage
Notification Load
App Switching Frequency
Recovery Indicators

Burnout Score =0.40 × Sustained Stress+0.35 × Workload+0.15 × Social Pressure+0.10 × Recovery

![alt text](image-1.png)

8. Wellbeing Monitoring

The Wellbeing Agent performs longitudinal emotional analysis.

Detection Capabilities
Sustained High Stress
Self-Critical Language
Burnout Signals
Crisis Indicators

![alt text](image-2.png)

9. Decision Intelligence Layer

The Decision Agent is responsible for choosing the most useful intervention.

Candidate responses are collected from:

Task Agent
Emotion Agent
Memory Agent
Notification Agent
LLM Generator
Candidate Evaluation

Each candidate is scored using:

Response Score =Action Quality+
Context Relevance+Memory Alignment-
Intrusiveness

10. Privacy & Security

FRIDAY is designed around privacy-first principles.

Local Components

SQLite Database
ChromaDB Memory Store
Stress Detection Engine
Decision Engine

Privacy Features

No cloud storage of personal data
Local memory retrieval
Offline operation
Device-side behavioral analysis

11. Performance Metrics

![alt text](image-4.png)

12. Future Enhancements
Samsung Ecosystem Integration
Galaxy Watch Integration

FRIDAY can incorporate physiological signals from Galaxy Watch devices, including:

Heart Rate Variability (HRV)
Resting Heart Rate
Sleep Quality
Physical Activity
Stress Measurements

This would allow FRIDAY to combine behavioral and physiological indicators for more accurate wellbeing assessment.

Samsung Health Integration

Future versions can leverage Samsung Health APIs to analyze:

Daily activity levels
Sleep patterns
Recovery metrics
Exercise consistency

This enables a more holistic understanding of user wellbeing beyond device usage alone.
