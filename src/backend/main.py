"""
FRIDAY - main.py
FastAPI app, WebSocket handlers, validation
Handles Android and Laptop WebSocket connections.
"""

import asyncio
import hashlib
import hmac
import json
import logging
import time
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Optional

import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException, Query, Request as FastAPIRequest
from fastapi.middleware.cors import CORSMiddleware

import os
import sys
from pathlib import Path

# Add backend directory to sys.path to enable local imports when started from root
backend_dir = str(Path(__file__).resolve().parent)
if backend_dir not in sys.path:
    sys.path.insert(0, backend_dir)

from config import settings
from database.sqlite_store import SQLiteStore
from database.chroma_store import ChromaStore
from validation.orchestrator import Orchestrator
from discovery import start_discovery_service
# ──────────────────────────────────────────────────────────────────────────────
# Logging
# ──────────────────────────────────────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(name)s | %(message)s",
)
logger = logging.getLogger("friday.main")

# ──────────────────────────────────────────────────────────────────────────────
# Global state
# ──────────────────────────────────────────────────────────────────────────────

# Active WebSocket connections
android_connections: dict[str, WebSocket] = {}   # device_id → ws
laptop_connections:  dict[str, WebSocket] = {}   # session_id → ws
latest_contexts:     dict[str, dict] = {}        # session_id → latest ContextObject dict

db:           Optional[SQLiteStore]  = None
chroma:       Optional[ChromaStore]  = None
orchestrator: Optional[Orchestrator] = None
zeroconf_instance = None
zeroconf_info = None


# ──────────────────────────────────────────────────────────────────────────────
# Lifespan (startup / shutdown)
# ──────────────────────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    global db, chroma, orchestrator, zeroconf_instance, zeroconf_info

    logger.info("FRIDAY backend starting up …")
    db      = SQLiteStore(settings.SQLITE_PATH)
    chroma  = ChromaStore(settings.CHROMA_PATH)
    orchestrator = Orchestrator(db=db, chroma=chroma, laptop_connections=laptop_connections)

    logger.info("All stores and orchestrator initialised.")

    try:
        zeroconf_instance, zeroconf_info = await start_discovery_service(settings.PORT)
    except Exception as e:
        import traceback
        logger.warning(f"Failed to start Zeroconf discovery: {e}\n{traceback.format_exc()}")

    yield

    logger.info("FRIDAY backend shutting down …")
    
    if zeroconf_instance and zeroconf_info:
        try:
            logger.info("Stopping Zeroconf service discovery …")
            await zeroconf_instance.zeroconf.async_unregister_service(zeroconf_info)
            await zeroconf_instance.async_close()
        except Exception as e:
            logger.warning(f"Failed to clean up Zeroconf: {e}")

    db.close()


# ──────────────────────────────────────────────────────────────────────────────
# App
# ──────────────────────────────────────────────────────────────────────────────

app = FastAPI(
    title="FRIDAY Backend",
    version="1.0.0",
    description="Empathetic AI backend for the FRIDAY cross-device system.",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ──────────────────────────────────────────────────────────────────────────────
# Helpers
# ──────────────────────────────────────────────────────────────────────────────

def verify_hmac(payload_bytes: bytes, signature: str) -> bool:
    """Verify HMAC-SHA256 signature on incoming Android payloads."""
    expected = hmac.new(
        settings.HMAC_SECRET.encode(),
        payload_bytes,
        hashlib.sha256,
    ).hexdigest()
    return hmac.compare_digest(expected, signature)


def validate_context_object(data: dict) -> tuple[bool, str]:
    """Validate required fields in a ContextObject."""
    required_top = {"metadata", "user_state", "sensor_data"}
    missing = required_top - data.keys()
    if missing:
        return False, f"Missing top-level keys: {missing}"

    meta_required = {"timestamp", "device_id", "session_id", "message_id"}
    missing_meta = meta_required - data["metadata"].keys()
    if missing_meta:
        return False, f"Missing metadata keys: {missing_meta}"

    return True, "ok"


# ──────────────────────────────────────────────────────────────────────────────
# REST Endpoints
# ──────────────────────────────────────────────────────────────────────────────

@app.get("/health", tags=["system"])
async def health_check():
    """Server liveness check."""
    return {
        "status": "ok",
        "version": "1.0.0",
        "android_connections": len(android_connections),
        "laptop_connections":  len(laptop_connections),
    }


@app.get("/api/status", tags=["system"])
async def agent_status():
    """Return agent availability and memory store sizes."""
    return {
        "agents":        orchestrator.agent_status() if orchestrator else {},
        "memory_count":  chroma.count() if chroma else 0,
        "kpi_log_count": db.count_logs() if db else 0,
    }


@app.get("/api/logs", tags=["kpi"])
async def export_logs(
    start_date: Optional[str] = Query(None, description="ISO date filter e.g. 2026-01-01"),
    limit:      int           = Query(1000, le=10000),
):
    """
    Export KPI logs as JSON for judge verification.
    Filter by start_date (YYYY-MM-DD) and limit.
    """
    if db is None:
        raise HTTPException(status_code=503, detail="Database not ready")
    rows = db.export_logs(start_date=start_date, limit=limit)
    return {"count": len(rows), "logs": rows}


@app.post("/api/onboarding/complete", tags=["onboarding"])
async def onboarding_complete(request: FastAPIRequest):
    """
    Receive onboarding completion notification from Android.
    Stores device config and enabled modules for session tracking.
    """
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON body")

    device_id = body.get("device_id", "unknown")
    enabled_modules = body.get("enabled_modules", [])
    modules_count = body.get("modules_enabled", 0)
    config = body.get("configuration", {})
    timestamp = body.get("timestamp", int(time.time() * 1000))

    logger.info(
        f"Onboarding complete: device={device_id}, "
        f"modules_enabled={modules_count}, modules={enabled_modules}"
    )

    # Log to KPI database
    if db:
        db.log_session_event(
            session_id=f"onboarding_{device_id}",
            device_id=device_id,
            event_type="onboarding_complete",
            is_offline=False,
            latency_ms=0,
        )

    return {
        "status": "ok",
        "device_id": device_id,
        "modules_acknowledged": modules_count,
        "message": "Onboarding configuration received. FRIDAY backend ready.",
    }


@app.get("/api/telemetry", tags=["telemetry"])
async def get_telemetry(session_id: Optional[str] = Query(None)):
    """
    Get real-time telemetry data (stress, focus, tasks, media) for a session.
    """
    ctx = None
    if session_id and session_id in latest_contexts:
        ctx = latest_contexts[session_id]
    elif latest_contexts:
        # fallback to the most recent context across all sessions
        ctx = list(latest_contexts.values())[-1]

    # Map context to the schema expected by content.js
    if ctx:
        sensor = ctx.get("sensor_data", {})
        user_state = ctx.get("user_state", {})
        active_task = ctx.get("active_task")

        # Focus efficiency calculation based on app switches and typo rate
        app_switches = sensor.get("app_switches", 0)
        typo_rate = sensor.get("typo_rate", 0.0)
        focus_efficiency = 100 - min(int((app_switches / 15 * 40) + (typo_rate * 300)), 90)

        # Map active task
        active_task_payload = None
        if active_task:
            deadline_str = active_task.get("deadline")
            time_left = ""
            if deadline_str:
                try:
                    from datetime import datetime, timezone
                    deadline = datetime.fromisoformat(deadline_str.replace("Z", "+00:00"))
                    now = datetime.now(timezone.utc)
                    diff = deadline - now
                    if diff.total_seconds() < 0:
                        time_left = "Overdue"
                    else:
                        hours = int(diff.total_seconds() // 3600)
                        mins = int((diff.total_seconds() % 3600) // 60)
                        if hours > 0:
                            time_left = f"{hours}h {mins}m left"
                        else:
                            time_left = f"{mins}m left"
                except Exception:
                    time_left = "Approaching deadline"
            progress = active_task.get("progress", 0.0)
            active_task_payload = {
                "title": active_task.get("description", "Active Task Pipeline"),
                "timeLeft": time_left,
                "completionPct": int(progress * 100),
                "totalSegments": 5,
                "activeSegments": int(progress * 5),
                "checklist": active_task.get("checklist") or [
                    {"name": "Understand requirements", "completed": progress >= 0.25},
                    {"name": "Draft implementation", "completed": progress >= 0.5},
                    {"name": "Refine and integrate", "completed": progress >= 0.75},
                    {"name": "Final review", "completed": progress >= 0.95}
                ]
            }

        # Map media handoff
        media_handoff_payload = None
        active_media = sensor.get("active_media")
        if active_media:
            media_handoff_payload = {
                "provider": active_media.get("provider", "youtube"),
                "video_id": active_media.get("video_id", ""),
                "playback_timestamp_seconds": int(active_media.get("playback_timestamp_seconds", 0))
            }

        # Build dynamic recent apps based on location
        loc = sensor.get("location", "home")
        if loc == "library":
            recent_apps = [
                {"name": "VS Code", "icon": "terminal", "time": "2m ago", "color": "#007ACC"},
                {"name": "GitHub", "icon": "hub", "time": "5m ago", "color": "#181717"},
                {"name": "Slack", "icon": "category", "time": "15m ago", "color": "#4A154B"},
                {"name": "Gmail", "icon": "drafts", "time": "1h ago", "color": "#EA4335"}
            ]
        else:
            recent_apps = [
                {"name": "WhatsApp", "icon": "messages", "time": "2m ago", "color": "#25D366"},
                {"name": "YouTube", "icon": "play_circle", "time": "10m ago", "color": "#FF0000"},
                {"name": "Gmail", "icon": "drafts", "time": "15m ago", "color": "#EA4335"},
                {"name": "Slack", "icon": "category", "time": "1h ago", "color": "#4A154B"}
            ]

        payload = {
            "stressScore": int(user_state.get("stress_score", 42)),
            "focusEfficiency": focus_efficiency,
            "activeTask": active_task_payload,
            "mediaHandoff": media_handoff_payload,
            "activeReading": {
                "title": "Designing Empathetic Ambient Intelligence",
                "timeLabel": "15 mins remaining",
                "completionPct": 75
            },
            "pendingStates": [
                {"type": "terminal", "name": "Ethics Lab Assignment Draft", "status": "Draft saved 10m ago", "link": "https://docs.google.com"},
                {"type": "play_circle", "name": "Deep Learning Lecture 4", "status": "Paused at 12:04", "link": "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=724s"}
            ],
            "recentApps": recent_apps,
            "recentTabs": [
                {"title": "FastAPI WebSockets Documentation", "url": "fastapi.tiangolo.com/advanced/websockets", "link": "https://fastapi.tiangolo.com/advanced/websockets/"},
                {"title": "Chrome Extension Developer Guide", "url": "developer.chrome.com/docs/extensions", "link": "https://developer.chrome.com/docs/extensions/"}
            ]
        }
        return payload
    else:
        # Full mockup payload representing realistic active workspace state
        return {
            "stressScore": 42,
            "focusEfficiency": 85,
            "activeTask": {
                "title": "Samsung Hackathon Development",
                "timeLeft": "1h 45m left",
                "completionPct": 60,
                "totalSegments": 5,
                "activeSegments": 3,
                "checklist": [
                    {"name": "Setup FastAPI Backend", "completed": True},
                    {"name": "Implement Chrome Extension UI", "completed": True},
                    {"name": "Establish WebSocket Coupling", "completed": False},
                    {"name": "Run End-to-End Validation", "completed": False}
                ]
            },
            "mediaHandoff": {
                "provider": "youtube",
                "video_id": "dQw4w9WgXcQ",
                "playback_timestamp_seconds": 420
            },
            "activeReading": {
                "title": "Designing Empathetic Ambient Intelligence",
                "timeLabel": "15 mins remaining",
                "completionPct": 75
            },
            "pendingStates": [
                {"type": "terminal", "name": "Ethics Lab Assignment Draft", "status": "Draft saved 10m ago", "link": "https://docs.google.com"},
                {"type": "play_circle", "name": "Deep Learning Lecture 4", "status": "Paused at 12:04", "link": "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=724s"}
            ],
            "recentApps": [
                {"name": "WhatsApp", "icon": "messages", "time": "2m ago", "color": "#25D366"},
                {"name": "Gmail", "icon": "drafts", "time": "15m ago", "color": "#EA4335"},
                {"name": "VS Code", "icon": "terminal", "time": "1h ago", "color": "#007ACC"},
                {"name": "Slack", "icon": "category", "time": "3h ago", "color": "#4A154B"}
            ],
            "recentTabs": [
                {"title": "FastAPI WebSockets Documentation", "url": "fastapi.tiangolo.com/advanced/websockets", "link": "https://fastapi.tiangolo.com/advanced/websockets/"},
                {"title": "Chrome Extension Developer Guide", "url": "developer.chrome.com/docs/extensions", "link": "https://developer.chrome.com/docs/extensions/"}
            ]
        }


# ──────────────────────────────────────────────────────────────────────────────
# WebSocket — Android
# ──────────────────────────────────────────────────────────────────────────────

@app.websocket("/ws/android")
async def android_ws(websocket: WebSocket):
    """
    Persistent WebSocket for Android sensor stream.

    Protocol:
      Android → Backend : JSON ContextObject (every 15 s or on event)
      Backend → Android : JSON response/action card
      Backend → Android : ACK every 100 messages
    """
    await websocket.accept()
    device_id   = None
    msg_counter = 0

    logger.info("Android WebSocket connected")

    try:
        while True:
            raw = await asyncio.wait_for(websocket.receive_text(), timeout=60.0)
            payload_bytes = raw.encode()

            # Parse
            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                await websocket.send_json({"error": "invalid JSON"})
                continue

            # ── Handle feedback messages from Android RLHF ──
            msg_type = data.get("type", "")
            if msg_type == "feedback":
                action_id = data.get("action_id")
                user_reaction = data.get("user_reaction")  # helpful / dismissed / ignored
                if action_id and user_reaction and orchestrator:
                    orchestrator.record_feedback(action_id=action_id, reaction=user_reaction)
                    logger.info(f"Android RLHF feedback: {action_id} → {user_reaction}")
                continue

            # ── Handle individual sensor events (not full ContextObjects) ──
            # The Android side also sends raw events like app_switch,
            # typing_metrics, notification. Log them but don't orchestrate.
            if msg_type in ("app_switch", "typing_metrics", "notification", "biometric_baseline"):
                logger.debug(f"Individual sensor event received: {msg_type}")
                # These are informational; the full ContextObject snapshot
                # sent every 15s is what drives orchestration.
                continue

            # HMAC verification (optional; skip if sig absent for dev)
            sig = data.pop("hmac_signature", None)
            if settings.ENFORCE_HMAC and sig:
                if not verify_hmac(payload_bytes, sig):
                    await websocket.send_json({"error": "HMAC verification failed"})
                    continue

            # Validate ContextObject schema
            ok, reason = validate_context_object(data)
            if not ok:
                logger.debug(f"Non-ContextObject message skipped: {reason}")
                continue

            device_id = data["metadata"]["device_id"]
            android_connections[device_id] = websocket
            session_id = data["metadata"]["session_id"]
            latest_contexts[session_id] = data

            # Track offline replays
            is_offline_replay = data.get("is_offline_replay", False)

            # Log receipt
            db.log_session_event(
                session_id=session_id,
                device_id=device_id,
                event_type="context_received",
                is_offline=is_offline_replay,
                latency_ms=0,
            )

            # Orchestrate
            t0 = time.time()
            response = await orchestrator.process(data)
            latency  = int((time.time() - t0) * 1000)

            # Update latency in session log
            db.log_session_event(
                session_id=data["metadata"]["session_id"],
                device_id=device_id,
                event_type="response_sent",
                is_offline=False,
                latency_ms=latency,
            )

            # Deliver response to Android
            if response:
                await websocket.send_json(response)

            # ACK every 100 messages
            msg_counter += 1
            if msg_counter % 100 == 0:
                await websocket.send_json({"type": "ACK", "count": msg_counter})

    except asyncio.TimeoutError:
        logger.warning("Android connection timed out (no heartbeat for 60 s)")
    except WebSocketDisconnect:
        logger.info(f"Android disconnected: {device_id}")
    except Exception as exc:
        logger.exception(f"Android WS error: {exc}")
    finally:
        if device_id and device_id in android_connections:
            del android_connections[device_id]


# ──────────────────────────────────────────────────────────────────────────────
# WebSocket — Laptop
# ──────────────────────────────────────────────────────────────────────────────

@app.websocket("/ws/laptop")
async def laptop_ws(websocket: WebSocket):
    """
    Persistent WebSocket for Laptop (Chrome Extension) UI stream.

    Protocol:
      Backend  → Laptop : UICommand JSON (SHOW_CONTINUITY / SHOW_TOAST / …)
      Laptop   → Backend: user reaction JSON
    """
    await websocket.accept()
    session_id = None
    logger.info("Laptop WebSocket connected")

    try:
        # First message from laptop must identify its session
        raw = await asyncio.wait_for(websocket.receive_text(), timeout=30.0)
        data = json.loads(raw)
        session_id = data.get("session_id")
        if not session_id:
            session_id = f"laptop_{int(time.time())}"
        laptop_connections[session_id] = websocket
        logger.info(f"Laptop registered session: {session_id}")
        await websocket.send_json({"type": "REGISTERED", "session_id": session_id})

        while True:
            raw = await asyncio.wait_for(websocket.receive_text(), timeout=120.0)
            reaction = json.loads(raw)

            # Handle user feedback / RLHF
            action_id    = reaction.get("action_id")
            user_reaction = reaction.get("user_reaction")  # helpful / dismissed / ignored

            if action_id and user_reaction:
                orchestrator.record_feedback(action_id=action_id, reaction=user_reaction)
                logger.info(f"Feedback recorded: {action_id} → {user_reaction}")

    except asyncio.TimeoutError:
        logger.warning(f"Laptop connection timed out: {session_id}")
    except WebSocketDisconnect:
        logger.info(f"Laptop disconnected: {session_id}")
    except Exception as exc:
        logger.exception(f"Laptop WS error: {exc}")
    finally:
        if session_id and session_id in laptop_connections:
            del laptop_connections[session_id]


# ──────────────────────────────────────────────────────────────────────────────
# Entry point
# ──────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=settings.PORT,
        reload=settings.DEBUG,
        log_level="info",
    )
