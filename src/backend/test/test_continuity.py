import asyncio
import json
import websockets
import time

async def simulate_continuity():
    uri_laptop = "ws://localhost:8000/ws/laptop"
    uri_android = "ws://localhost:8000/ws/android"
    
    print("Connecting Laptop client...")
    async with websockets.connect(uri_laptop) as ws_laptop:
        # Send registration first
        reg_payload = {"type": "REGISTER", "session_id": "test_laptop_session"}
        await ws_laptop.send(json.dumps(reg_payload))
        
        # Expect registered response
        reg_resp = await ws_laptop.recv()
        print(f"Laptop received registration ack: {reg_resp}")
        
        print("\nConnecting Android client...")
        async with websockets.connect(uri_android) as ws_android:
            # Android immediately receives LAPTOP_STATUS on connect
            init_msg = await ws_android.recv()
            print(f"Android received on connect: {init_msg}")
            
            # 1. Send active_page telemetry from Phone
            phone_telemetry_active = {
                "metadata": {
                    "timestamp": "2026-06-13T21:30:00.000Z",
                    "device_id": "phone_test",
                    "session_id": "session_phone_test",
                    "message_id": "msg_001"
                },
                "user_state": {
                    "stress_score": 40,
                    "emotion_label": "calm"
                },
                "sensor_data": {
                    "battery_level": 80,
                    "location": "office",
                    "app_switches": 1,
                    "notification_count": 0,
                    "typo_rate": 0.02,
                    "screen_on_time": 60,
                    "focused_app": "com.android.chrome",
                    "active_page": {
                        "title": "Samsung Hackathon Continuity Guide",
                        "url": "https://developer.samsung.com/continuity"
                    }
                }
            }
            print("\nPhone: Browsing 'https://developer.samsung.com/continuity'...")
            await ws_android.send(json.dumps(phone_telemetry_active))
            
            # Allow backend to register state
            await asyncio.sleep(1)
            
            # 2. Send empty active_page telemetry from Phone (Leftover event)
            phone_telemetry_leftover = {
                "metadata": {
                    "timestamp": "2026-06-13T21:31:00.000Z",
                    "device_id": "phone_test",
                    "session_id": "session_phone_test",
                    "message_id": "msg_002"
                },
                "user_state": {
                    "stress_score": 40,
                    "emotion_label": "calm"
                },
                "sensor_data": {
                    "battery_level": 80,
                    "location": "office",
                    "app_switches": 2,
                    "notification_count": 0,
                    "typo_rate": 0.02,
                    "screen_on_time": 120,
                    "focused_app": "com.sec.android.app.launcher",
                    "active_page": None  # Leftover trigger!
                }
            }
            print("Phone: Locking screen / closing browser (active_page = None)...")
            await ws_android.send(json.dumps(phone_telemetry_leftover))
            
            # 3. Listen on Laptop client for the SHOW_CONTINUITY trigger
            print("\nLaptop: Listening for incoming leftover alerts...")
            start_time = time.time()
            test_passed = False
            
            while time.time() - start_time < 6.0:
                try:
                    # Wait up to 3 seconds for each message
                    raw_message = await asyncio.wait_for(ws_laptop.recv(), timeout=3.0)
                    message = json.loads(raw_message)
                    msg_type = message.get("type")
                    print(f"Laptop received event type: {msg_type}")
                    
                    if msg_type == "SHOW_CONTINUITY":
                        print(f"Event Details: {json.dumps(message, indent=2)}")
                        print("\n✅ TEST PASSED: Laptop received the 'SHOW_CONTINUITY' leftover alert successfully!")
                        content = message.get("content", {})
                        print(f"  Title: {content.get('title')}")
                        print(f"  Message: {content.get('message')}")
                        print(f"  URL: {message.get('url')}")
                        test_passed = True
                        break
                except asyncio.TimeoutError:
                    break
            
            if not test_passed:
                print("\n❌ TEST FAILED: Timeout or did not receive SHOW_CONTINUITY leftover alert.")

if __name__ == "__main__":
    asyncio.run(simulate_continuity())
