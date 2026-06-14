import asyncio
import json
import websockets
import time

async def test_enhanced_continuity():
    uri_laptop = "ws://localhost:8000/ws/laptop"
    uri_android = "ws://localhost:8000/ws/android"
    
    print("\n--- STEP 1: Connect Android client & register ---")
    async with websockets.connect(uri_android) as ws_android:
        # Check initial laptop status received on connect
        init_msg = json.loads(await ws_android.recv())
        print(f"Android received initial: {json.dumps(init_msg, indent=2)}")
        
        # Send a sync_request to register device_id
        sync_req = {
            "type": "sync_request",
            "device_id": "phone_enhanced",
            "timestamp": int(time.time() * 1000)
        }
        await ws_android.send(json.dumps(sync_req))
        # Discard the immediate LAPTOP_STATUS reply
        await ws_android.recv()
        print("Android: Registered device_id 'phone_enhanced'")
        
        # Connect laptop and update page
        print("\n--- STEP 2: Connect Laptop & update page telemetry ---")
        async with websockets.connect(uri_laptop) as ws_laptop:
            reg_payload = {"type": "REGISTER", "session_id": "laptop_session_enhanced"}
            await ws_laptop.send(json.dumps(reg_payload))
            reg_resp = await ws_laptop.recv()
            print(f"Laptop registered: {reg_resp}")
            
            # Send laptop page update
            page_update = {
                "type": "LAPTOP_PAGE_UPDATE",
                "active_page": {
                    "url": "https://example.com/laptop-reading",
                    "title": "Amazing Laptop Guide",
                    "timestamp": int(time.time() * 1000)
                }
            }
            await ws_laptop.send(json.dumps(page_update))
            print("Laptop: Sent page update 'Amazing Laptop Guide'")
            
            # Wait for Android to receive the broadcasted LAPTOP_STATUS with the updated recent tabs
            print("\n--- STEP 3: Verify Android receives LAPTOP_STATUS broadcast ---")
            android_received_update = False
            for _ in range(3):
                try:
                    raw_msg = await asyncio.wait_for(ws_android.recv(), timeout=2.0)
                    msg = json.loads(raw_msg)
                    if msg.get("type") == "LAPTOP_STATUS":
                        print(f"Android received broadcasted status: {json.dumps(msg, indent=2)}")
                        recent_tabs = msg.get("recent_tabs", [])
                        if recent_tabs and recent_tabs[0]["title"] == "Amazing Laptop Guide":
                            print("✅ SUCCESS: Android received real-time recent tabs update from Laptop!")
                            android_received_update = True
                            break
                except asyncio.TimeoutError:
                    break
            
            assert android_received_update, "Android did not receive the broadcasted LAPTOP_STATUS update."

        # Connect phone again and send telemetry page
        print("\n--- STEP 4: Send active page telemetry from Phone ---")
        phone_telemetry = {
            "metadata": {
                "timestamp": "2026-06-13T22:00:00.000Z",
                "device_id": "phone_enhanced",
                "session_id": "session_phone_enhanced",
                "message_id": "msg_enh_001"
            },
            "user_state": {
                "stress_score": 35,
                "emotion_label": "calm"
            },
            "sensor_data": {
                "battery_level": 90,
                "location": "home",
                "focused_app": "com.android.chrome",
                "active_page": {
                    "title": "Phone Article About Optimization",
                    "url": "https://example.com/phone-article"
                }
            }
        }
        await ws_android.send(json.dumps(phone_telemetry))
        await asyncio.sleep(0.5)

        # Now simulate user switching phone to background/idle (active_page = None)
        phone_telemetry["sensor_data"]["active_page"] = None
        phone_telemetry["metadata"]["message_id"] = "msg_enh_002"
        await ws_android.send(json.dumps(phone_telemetry))
        print("Phone: Set active_page to None (left off task)")
        await asyncio.sleep(0.5)

    # Note: Both Android and Laptop are now closed/disconnected.
    # Now the user sits at their Laptop and opens the browser (re-connecting to ws/laptop).
    print("\n--- STEP 5: Laptop connects after Phone became idle ---")
    async with websockets.connect(uri_laptop) as ws_laptop_new:
        reg_payload = {"type": "REGISTER", "session_id": "laptop_session_enhanced_2"}
        await ws_laptop_new.send(json.dumps(reg_payload))
        await ws_laptop_new.recv() # register ack
        
        # Read next message to see if SHOW_CONTINUITY leftover is immediately pushed
        print("Laptop: Listening for leftover pushed on connection...")
        try:
            raw_msg = await asyncio.wait_for(ws_laptop_new.recv(), timeout=3.0)
            msg = json.loads(raw_msg)
            print(f"Laptop received: {json.dumps(msg, indent=2)}")
            if msg.get("type") == "SHOW_CONTINUITY":
                print("✅ SUCCESS: Laptop immediately received phone leftover continuity task on connect!")
                content = msg.get("content", {})
                assert "Phone Article About Optimization" in content.get("message", "")
        except asyncio.TimeoutError:
            print("❌ FAILURE: Laptop did not receive leftover task on connection")
            raise AssertionError("Laptop did not receive leftover task on connection")

    # Connect Android and send sync_request to verify manual refresh syncs recent tabs
    print("\n--- STEP 6: Verify sync_request returns LAPTOP_STATUS ---")
    async with websockets.connect(uri_android) as ws_android_new:
        # discard initial connect msg
        await ws_android_new.recv()
        
        sync_req = {
            "type": "sync_request",
            "device_id": "phone_enhanced",
            "timestamp": int(time.time() * 1000)
        }
        await ws_android_new.send(json.dumps(sync_req))
        print("Android: Sent manual sync_request")
        
        try:
            raw_msg = await asyncio.wait_for(ws_android_new.recv(), timeout=3.0)
            msg = json.loads(raw_msg)
            print(f"Android received on sync_request: {json.dumps(msg, indent=2)}")
            if msg.get("type") == "LAPTOP_STATUS":
                print("✅ SUCCESS: Android sync_request returned current LAPTOP_STATUS immediately!")
        except asyncio.TimeoutError:
            print("❌ FAILURE: Android did not receive status reply to sync_request")
            raise AssertionError("Android did not receive status reply to sync_request")

if __name__ == "__main__":
    asyncio.run(test_enhanced_continuity())
