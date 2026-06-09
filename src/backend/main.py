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
from typing import Optional

import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware

from config import settings
from database.sqlite_store import SQLiteStore
from database.chroma_store import ChromaStore
from validation.orchestrator import Orchestrator
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

db:           Optional[SQLiteStore]  = None
chroma:       Optional[ChromaStore]  = None
orchestrator: Optional[Orchestrator] = None


# ──────────────────────────────────────────────────────────────────────────────
# Lifespan (startup / shutdown)
# ──────────────────────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    global db, chroma, orchestrator

    logger.info("FRIDAY backend starting up …")
    db      = SQLiteStore(settings.SQLITE_PATH)
    chroma  = ChromaStore(settings.CHROMA_PATH)
    orchestrator = Orchestrator(db=db, chroma=chroma, laptop_connections=laptop_connections)

    logger.info("All stores and orchestrator initialised.")
    yield

    logger.info("FRIDAY backend shutting down …")
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

            # HMAC verification (optional; skip if sig absent for dev)
            sig = data.pop("hmac_signature", None)
            if settings.ENFORCE_HMAC and sig:
                if not verify_hmac(payload_bytes, sig):
                    await websocket.send_json({"error": "HMAC verification failed"})
                    continue

            # Validate schema
            ok, reason = validate_context_object(data)
            if not ok:
                await websocket.send_json({"error": reason})
                continue

            device_id = data["metadata"]["device_id"]
            android_connections[device_id] = websocket

            # Log receipt
            db.log_session_event(
                session_id=data["metadata"]["session_id"],
                device_id=device_id,
                event_type="context_received",
                is_offline=False,
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
        session_id = data.get("session_id", f"laptop_{int(time.time())}")
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
