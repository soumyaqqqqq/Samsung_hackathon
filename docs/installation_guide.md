# FRIDAY: Installation & Deployment Guide
## Complete Setup and Deployment Instructions

This guide details the exact steps required to deploy the complete FRIDAY ecosystem. Due to the privacy-first, edge-compute nature of this architecture, you will be setting up three interconnected layers: the **FastAPI Compute Hub (Laptop)**, the **Chrome Extension (Desktop UI)**, and the **Android Sensing Node (Mobile)**.

  

### Prerequisites

#### System Requirements

**Compute Hub (Laptop / Desktop)**:
- **Operating System**: Windows 10+, macOS 12+, or Ubuntu 20.04+
- **RAM**: 16GB minimum, 32GB recommended (Llama 3.1 8B runs comfortably at this tier)
- **Storage**: 20GB free space (model weights, vector DB, Whisper binaries)
- **Processor**: Multi-core CPU; a CUDA-capable NVIDIA GPU or Apple Silicon is recommended for faster local inference but is not required

**Target Device (Android Sensing Node)**:
- **Android Version**: 8.0+ (API Level 26+)
- **RAM**: 4GB minimum, 6GB recommended
- **Storage**: 500MB free space (for the embedded Phi-3 Mini ONNX fallback model and local telemetry cache)
- **Connectivity**: Wi-Fi (for mDNS discovery) or cellular data (for the ngrok fallback)

**Browser**:
- **Google Chrome**: Version 110+ (Manifest V3 support required for the extension)

#### Required Software

**Development Tools**:
- **Python**: Version 3.10+ (FastAPI backend and AI routing)
- **Git** and **Make**: For cloning the repo and building local C++ binaries
- **Android Studio**: Arctic Fox (2023.1+) or later
- **Java Development Kit (JDK)**: Version 17
- **Ollama**: Latest version, for local LLM serving
- **CMake**: Required by `whisper.cpp` on most platforms

**Mobile Dependencies** (pulled automatically via Gradle):
- **ONNX Runtime Mobile**: For the on-device Phi-3 Mini offline fallback
- **Gradle**: Version 8.0+
- **Kotlin**: Version 1.9.0+

**Optional**:
- **ngrok**: Free tier account, for cellular/cross-network fallback during demos

  

### Part 1: The Compute Hub (Laptop Backend)

The backend acts as the heavy-lifting brain of FRIDAY, hosting the localized vector databases and model orchestrators.

#### Step 1: Clone the Repository

```bash
git clone https://github.com/soumyaqqqqq/Samsung_hackathon/tree/main
cd Samsung_hackathon/src/backend
```

#### Step 2: Set Up the Python Environment

```bash
python -m venv venv
source venv/bin/activate   # On Windows: venv\Scripts\activate
pip install -r requirements.txt
```

#### Step 3: Install the Primary Brain (Llama 3.1 8B via Ollama)

1. Download and install **Ollama** from [ollama.com](https://ollama.com).
2. Start the Ollama service in its own terminal:
   ```bash
   ollama serve
   ```
3. Pull the core reasoning model:
   ```bash
   ollama run llama3.1
   ```

#### Step 4: Build the Voice Engine (Whisper.cpp)

To achieve sub-second voice transcription that bypasses the Python GIL, FRIDAY uses the C++ port of Whisper.

```bash
cd src/backend
git clone https://github.com/ggerganov/whisper.cpp.git
cd whisper.cpp
make
# Download the small model weights
bash ./models/download-ggml-model.sh small
```

> If `make` fails, confirm CMake and a C++17-capable compiler (gcc/clang/MSVC) are on your `PATH`.

#### Step 5: Install the Burnout Scoring Model

FRIDAY uses a LoRA-adapted RoBERTa model fine-tuned on Android telemetry to predict burnout urgency scores in real time. The adapter weights are published at [Rabbit-bot/FRIDAY-roberta-burnout-lora](https://huggingface.co/Rabbit-bot/FRIDAY-roberta-burnout-lora).

**Install dependencies** (if not already in `requirements.txt`):

```bash
pip install transformers peft torch
```

**Download and verify the model:**

```python
from peft import PeftModel
from transformers import AutoModelForSequenceClassification, AutoTokenizer

MODEL_ID = "Rabbit-bot/FRIDAY-roberta-burnout-lora"

# Load base model + LoRA adapter
base      = AutoModelForSequenceClassification.from_pretrained("roberta-base", num_labels=1)
model     = PeftModel.from_pretrained(base, MODEL_ID)
tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)

model.eval()
print("Burnout model loaded successfully.")
```

> **Important**: Always load with `PeftModel.from_pretrained()`, not `AutoModelForSequenceClassification.from_pretrained()` directly. The Hub repository contains only the LoRA adapter weights (~2 MB), not the full RoBERTa base weights. The base is pulled automatically from `roberta-base` on first load.

**Run a quick inference test** to confirm the model is working before starting the backend:

```python
import torch

sample = (
    "[APP] Slack [HOUR] 14 [SESSION_MIN] 90 [NOTIF] 18 "
    "[WPM] 82 [ERRORS] 11 [TEXT] Need this report ASAP!"
)

inputs = tokenizer(sample, return_tensors="pt", truncation=True, max_length=128)

with torch.no_grad():
    score = model(**inputs).logits.squeeze().item()

# Score is in [0, 1] — higher means greater burnout/urgency
print(f"Burnout score: {score:.4f}")   # expect ~0.85 for this input
```

**Save a local copy** to avoid re-downloading on every backend restart (recommended for demo environments):

```bash
# In Python, after loading:
model.save_pretrained("./models/roberta-burnout-lora")
tokenizer.save_pretrained("./models/roberta-burnout-lora")
```

Then update `config/.env` to point at the local path:

```bash
BURNOUT_MODEL_PATH=./models/roberta-burnout-lora
```

The `BurnoutAgent` in `agents/burnout.py` reads this env variable on startup and loads from disk instead of the Hub if the path exists.

#### Step 6: Start the FastAPI Server

With Ollama running in the background and the burnout model cached locally, start the FRIDAY orchestrator:

```bash
cd src/backend
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

> **Note**: The server must run on `0.0.0.0` so the mDNS/Zeroconf service can broadcast the port to your Android device over the local network.

  

### Part 2: The Desktop UI (Chrome Extension)

The Chrome extension acts as an isolated Shadow DOM rendering surface, communicating with the backend via WebSockets.

#### Step 1: Load the Extension

1. Open Google Chrome and navigate to `chrome://extensions/`.
2. Toggle **Developer mode** ON (top right corner).
3. Click **Load unpacked** and select the `src/laptop/` directory from the repository.

#### Step 2: Enable Sandbox Testing Permissions

To test the extension securely without corporate CORS blocks during the hackathon:

1. Locate the **FRIDAY Continuity Client** card in your extensions list and click **Details**.
2. Scroll down and toggle **Allow access to file URLs** to ON.
3. Open `test.html` (in the `src/laptop/` folder) in your browser to verify the UI injection.

#### Step 3: Point the Extension at the Backend

By default the extension looks for the FastAPI hub at `ws://localhost:8000/ws`. If you are running the backend on another machine on your LAN, update the WebSocket URL in the extension's options page to match the hub's local IP (or the ngrok URL — see Part 4).

  

### Part 3: The Sensing Node (Android App)

The Android app acts as the sensor layer, collecting accessibility telemetry and behavioral metrics, with an on-device LLM fallback for full offline operation.

#### Step 1: Build the Application

1. Open **Android Studio** and select **Open an existing Project**, navigating to `src/android/app/`.
2. Wait for Gradle to sync — this automatically pulls the **ONNX Runtime Mobile** dependency required for the Phi-3 offline fallback.
3. Build a debug APK from the command line if preferred:
   ```bash
   cd src/android
   ./gradlew assembleDebug
   ```
   The output APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

#### Step 2: Install on Device

**Via Android Studio**: Connect your device via USB, enable USB debugging, and click **Run**.

**Via ADB**:
```bash
adb devices
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Via File Manager**: Copy the APK to the device, open it from a file manager, and tap to install (enabling "Install from unknown sources" if prompted).

#### Step 3: Enable Developer Options (if not already enabled)

1. Go to **Settings > About phone**.
2. Tap **Build number** 7 times.
3. Go to **Settings > Developer options** and enable **USB debugging**.

#### Step 4: Grant System Permissions

Upon first launch, the app requests several critical system hooks. You must manually grant:

| Permission | Purpose |
|  |  |
| **Notification Access** | Allows `FRIDAYNotificationListener` to read and batch missed alerts |
| **Accessibility Service** | Allows the app to track typing pace and active UI context for cognitive load scoring |
| **Draw Over Other Apps** | Required for the persistent foreground service overlay |

The relevant manifest entries are:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />
<uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE" />
```

Grant each by navigating to **Settings > Accessibility > FRIDAY > Enable**, and **Settings > Apps > Special app access > Notification access / Display over other apps > FRIDAY > Allow**.

  

### Part 4: Networking & Offline Fallback

#### Option 1: Local Wi-Fi Discovery (Default)

By default, the Android app uses **Network Service Discovery (mDNS)** to automatically find the FastAPI server on your local Wi-Fi router — no IP hardcoding required. Ensure both the laptop and phone are on the same network.

#### Option 2: Cellular / Cross-Network Fallback (ngrok)

If you are moving between networks or presenting on a restricted Wi-Fi network, start an ngrok tunnel on your laptop:

```bash
ngrok http 8000
```

Copy the provided secure WebSocket URL (`wss://<your-ngrok-url>`) and enter it into the Android app's network settings (and the Chrome extension's options page) to bridge the connection across the public internet.

#### Option 3: Absolute Offline Mode

If the backend is entirely unreachable, the Android app automatically falls back to its embedded, quantized **Phi-3 Mini** model (via ONNX) to maintain core functionality, caching telemetry logs locally in a **SQLite Room Database** until reconnection.

  

### Configuration

#### Initial Setup

1. Launch the FastAPI hub and confirm Ollama responds: `curl http://localhost:11434/api/tags`.
2. Confirm the burnout model loads: run the inference test from Part 1, Step 5.
3. Launch the Chrome extension and confirm the WebSocket badge shows **Connected**.
4. Launch the FRIDAY app on Android and grant all requested permissions.
5. Confirm the Android app discovers the hub automatically (mDNS) or connect manually via the ngrok URL.

#### Recommended `.env` Settings (Backend)

```bash
OLLAMA_MODEL=llama3.1
WHISPER_MODEL_PATH=./whisper.cpp/models/ggml-small.bin
BURNOUT_MODEL_PATH=./models/roberta-burnout-lora
HOST=0.0.0.0
PORT=8000
```

  

### Deployment Options

#### Development Deployment

```bash
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
./gradlew assembleDebug
```
Debug builds include verbose logging, performance counters, and the FRIDAY in-app debug overlay.

#### Demo / Hackathon Deployment

For live demos, it's recommended to run the laptop as a personal Wi-Fi hotspot (or ensure both devices share a stable network) so mDNS discovery works without manual configuration. Keep the ngrok tunnel (Part 4, Option 2) ready as a fallback if venue Wi-Fi is unreliable.

Cache the burnout model locally before the demo (`BURNOUT_MODEL_PATH=./models/roberta-burnout-lora`) so the backend starts instantly without a Hub download.

#### Production-Style Deployment

```bash
./gradlew assembleRelease
```
For a release APK, enable code shrinking/obfuscation (R8) in `build.gradle.kts`, and run the backend behind a process manager (e.g. `systemd` or `pm2`) rather than `--reload`.

  

### Testing Deployment

#### Backend Health Checks

```bash
curl http://localhost:8000/health
curl http://localhost:11434/api/tags        # confirms Ollama is serving
```

#### Burnout Model Check

```bash
python - <<'EOF'
from peft import PeftModel
from transformers import AutoModelForSequenceClassification, AutoTokenizer
import torch

base  = AutoModelForSequenceClassification.from_pretrained("roberta-base", num_labels=1)
model = PeftModel.from_pretrained(base, "./models/roberta-burnout-lora")
tok   = AutoTokenizer.from_pretrained("./models/roberta-burnout-lora")
model.eval()

inp   = tok("[APP] Gmail [HOUR] 09 [SESSION_MIN] 120 [NOTIF] 25 [WPM] 95 [ERRORS] 22 [TEXT] URGENT: Server is down.", return_tensors="pt", truncation=True, max_length=128)
score = model(**inp).logits.squeeze().item()
print(f"Score: {score:.4f}  (expect > 0.80 for this input)")
EOF
```

#### Voice Engine Test

```bash
cd src/backend/whisper.cpp
./main -m models/ggml-small.bin -f samples/jfk.wav
```

#### Android Unit & Instrumented Tests

```bash
cd src/android
./gradlew test
./gradlew connectedAndroidTest
```

#### End-to-End Check

1. Speak a command near the phone's mic.
2. Confirm the transcription appears in the FastAPI server logs.
3. Confirm the Chrome extension UI updates in real time via WebSocket.
4. Disable Wi-Fi on the phone and confirm it falls back to the on-device Phi-3 Mini model.

  

### Troubleshooting

| Issue | Likely Cause / Fix |
|  |  |
| `ollama serve` fails to start | Port 11434 already in use — stop other Ollama instances or change the port |
| `whisper.cpp` build fails | Missing CMake or a C++17 compiler; install build-essential (Linux) or Xcode Command Line Tools (macOS) |
| Burnout model returns scores near 0.5 for all inputs | Base model loaded without the LoRA adapter — ensure you used `PeftModel.from_pretrained()`, not `AutoModel` |
| `OSError: Can't load config for roberta-base` | No internet on first run and base weights not cached — run `python -c "from transformers import AutoModel; AutoModel.from_pretrained('roberta-base')"` on a connected machine first |
| Android app can't find the hub | Confirm both devices are on the same Wi-Fi network and that the laptop firewall allows inbound connections on port 8000 |
| Chrome extension shows "Disconnected" | Verify the WebSocket URL in extension options matches the hub's current IP or ngrok URL |
| Accessibility Service keeps disabling | Some OEM Android skins (e.g. MIUI, OneUI) auto-revoke accessibility permissions for battery savings — disable battery optimization for FRIDAY |
| ngrok tunnel times out | Free-tier ngrok tunnels expire after a few hours — restart `ngrok http 8000` and update the URL on both clients |

#### Debug Logging

```bash
# Backend
uvicorn main:app --host 0.0.0.0 --port 8000 --reload --log-level debug

# Android
adb logcat -s FRIDAY
adb logcat -s FRIDAY > friday_logs.txt
```

  

### Security & Privacy Considerations

**Data Handling**:
- All inference (Llama 3.1, Whisper, Phi-3 Mini, RoBERTa burnout model) runs **locally** — no audio, transcripts, or telemetry are sent to third-party cloud services by default.
- The only traffic that leaves the local network is the optional ngrok tunnel, which the user explicitly opts into.
- Telemetry collected by the Android Sensing Node (notifications, typing pace, UI context) is cached locally in the SQLite Room Database and is never uploaded.

**Permission Management**:
- Notification Access, Accessibility Service, and Draw Over Other Apps are powerful permissions — only grant them on a device you control, and review what each is used for in Part 3, Step 4.
- Users should be able to revoke any permission and clear cached telemetry from within the app settings.
