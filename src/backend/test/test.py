import asyncio

from validation.orchestrator import Orchestrator
from database.sqlite_store import SQLiteStore
from database.chroma_store import ChromaStore


async def main():
    db = SQLiteStore("data/friday.db")
    chroma = ChromaStore("data/chroma")

    orchestrator = Orchestrator(
    db,
    chroma,
    {}
)

    ctx = {
        
        "metadata": {
        "session_id": "test-session",
        "message_id": "msg-001",
        "device_id": "test_user",
        "timestamp": "2026-06-10T12:00:00Z"
        },
    

        "sensor_data": {
            "notification_count": 12,
            "app_switches": 18,
            "screen_on_time": 7200,
            "battery_level": 35,
            "location": "library",
        },
        "user_state": {
            "stress_score": 72,
        },
        "active_task": {
            "description": "AI Assignment",
            "deadline": "2026-06-10T14:00:00Z",
            "progress": 0.4,
        },
    }

    result = await orchestrator.process(ctx)

    print(result)


if __name__ == "__main__":
    asyncio.run(main())