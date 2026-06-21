# FRIDAY Setup Guide

There are three things you need to get running: the **backend** on your laptop, the **Chrome extension** for the UI, and the **Android app** as the sensor layer. Follow the parts in order.

---

## Before You Start

Make sure you have these installed:

**On your laptop**
- Python 3.10+
- Git and Make
- Ollama (from [ollama.com](https://ollama.com))
- CMake and a C++ compiler
- Android Studio (if building the Android app yourself)
- JDK 17

**On your phone**
- Android 8.0 or higher
- At least 4GB RAM
- Connected to the same Wi-Fi as your laptop

**Browser**
- Chrome 110+

---

## Part 1 — Laptop Backend

### 1. Clone the repo

```bash
git clone https://github.com/soumyaqqqqq/Samsung_hackathon/tree/main
cd Samsung_hackathon/src/backend
```

### 2. Set up Python

```bash
python -m venv venv
source venv/bin/activate        # Windows: venv\Scripts\activate
pip install -r requirements.txt
```

### 3. Install the main AI model

Start Ollama in a separate terminal and pull the model:

```bash
ollama serve
ollama run llama3.1
```

### 4. Build the voice engine

```bash
cd src/backend
git clone https://github.com/ggerganov/whisper.cpp.git
cd whisper.cpp
make
bash ./models/download-ggml-model.sh small
```

If `make` fails, make sure CMake and a C++17 compiler are installed.

### 5. Set up the burnout scoring model

Install dependencies:

```bash
pip install transformers peft torch
```

Download and verify the model:

```python
from peft import PeftModel
from transformers import AutoModelForSequenceClassification, AutoTokenizer

base      = AutoModelForSequenceClassification.from_pretrained("roberta-base", num_labels=1)
model     = PeftModel.from_pretrained(base, "Rabbit-bot/FRIDAY-roberta-burnout-lora")
tokenizer = AutoTokenizer.from_pretrained("Rabbit-bot/FRIDAY-roberta-burnout-lora")

model.eval()
print("Burnout model loaded successfully.")
```

> Always use `PeftModel.from_pretrained()` here, not `AutoModelForSequenceClassification` directly. The repo only holds the adapter weights, not the full model.

Quick test to confirm it works:

```python
import torch

sample = "[APP] Slack [HOUR] 14 [SESSION_MIN] 90 [NOTIF] 18 [WPM] 82 [ERRORS] 11 [TEXT] Need this report ASAP!"
inputs = tokenizer(sample, return_tensors="pt", truncation=True, max_length=128)

with torch.no_grad():
    score = model(**inputs).logits.squeeze().item()

print(f"Burnout score: {score:.4f}")   # expect ~0.85
```

Save it locally so you don't re-download every time:

```bash
# In Python, after loading:
model.save_pretrained("./models/roberta-burnout-lora")
tokenizer.save_pretrained("./models/roberta-burnout-lora")
```

Then add this to `config/.env`:

```
BURNOUT_MODEL_PATH=./models/roberta-burnout-lora
```

### 6. Start the server

```bash
cd src/backend
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

The server needs `0.0.0.0` so your Android device can find it on the network.

---

## Part 2 — Chrome Extension

1. Open Chrome and go to `chrome://extensions/`
2. Turn on **Developer mode** (top right)
3. Click **Load unpacked** and select the `src/laptop/` folder
4. Find the **FRIDAY Continuity Client** card, click **Details**, and enable **Allow access to file URLs**
5. Open `src/laptop/test.html` in Chrome to confirm the UI loads

By default the extension connects to `ws://localhost:8000/ws`. If your backend is on a different machine, update the WebSocket URL in the extension's options page.

---

## Part 3 — Android App

### Build and install

Open `src/android/app/` in Android Studio and let Gradle sync. Then either click Run, or build manually:

```bash
cd src/android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Enable USB debugging (if needed)

Go to **Settings > About phone**, tap **Build number** 7 times, then enable **USB debugging** under Developer options.

### Grant permissions

On first launch, the app will ask for three permissions. All three are required:

| Permission | What it's for |
|---|---|
| Notification Access | Reading and batching missed alerts |
| Accessibility Service | Tracking typing pace and active app context |
| Draw Over Other Apps | Persistent overlay for the foreground service |

Grant them by going to **Settings > Accessibility > FRIDAY** and **Settings > Apps > Special app access**.

---

## Part 4 — Networking

**Same Wi-Fi (default):** The Android app finds the laptop automatically using mDNS. Just make sure both devices are on the same network.

**Different networks or demo mode:** Run an ngrok tunnel on your laptop:

```bash
ngrok http 8000
```

Paste the `wss://` URL into the Android app's network settings and the Chrome extension's options page.

**No internet at all:** The app automatically falls back to an on-device Phi-3 Mini model and caches everything locally until you reconnect.

---

## Recommended `.env` (Backend)

```
OLLAMA_MODEL=llama3.1
WHISPER_MODEL_PATH=./whisper.cpp/models/ggml-small.bin
BURNOUT_MODEL_PATH=./models/roberta-burnout-lora
HOST=0.0.0.0
PORT=8000
```

---

## Quick Health Checks

```bash
# Is the backend up?
curl http://localhost:8000/health

# Is Ollama running?
curl http://localhost:11434/api/tags

# Is the voice engine working?
cd src/backend/whisper.cpp
./main -m models/ggml-small.bin -f samples/jfk.wav

# Android logs
adb logcat -s FRIDAY
```

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `ollama serve` won't start | Port 11434 is already in use. Stop other Ollama instances. |
| `whisper.cpp` build fails | Install CMake and a C++17 compiler (`build-essential` on Linux, Xcode tools on Mac) |
| Burnout scores stuck around 0.5 | You loaded the base model without the LoRA adapter. Use `PeftModel.from_pretrained()`. |
| `OSError: Can't load config for roberta-base` | No internet on first run. Pre-download with `python -c "from transformers import AutoModel; AutoModel.from_pretrained('roberta-base')"` on a connected machine. |
| Android can't find the backend | Both devices must be on the same Wi-Fi, and your laptop firewall should allow port 8000. |
| Chrome extension shows "Disconnected" | Update the WebSocket URL in extension options to match your current IP or ngrok URL. |
| Accessibility service keeps turning off | Some Android skins (MIUI, OneUI) kill it for battery savings. Disable battery optimization for FRIDAY. |
| ngrok tunnel stopped working | Free tunnels expire after a few hours. Restart `ngrok http 8000` and update the URL on both devices. |
