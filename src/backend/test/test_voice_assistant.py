import asyncio
import sys
import os
from pathlib import Path

# Set python path to find main and config
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from fastapi.testclient import TestClient
from main import app, generate_voice_response
from agents.voice import VoiceAgent
import main

# Define file paths relative to backend root
BACKEND_DIR = Path(__file__).resolve().parent
JFK_WAV_PATH = BACKEND_DIR / "whisper.cpp" / "samples" / "jfk.wav"

def test_voice_agent_initialization():
    print("\n[TEST] Initializing VoiceAgent...")
    agent = VoiceAgent()
    assert agent.binary_path.exists(), f"Binary path does not exist: {agent.binary_path}"
    assert agent.model_path.exists(), f"Model path does not exist: {agent.model_path}"
    print("-> VoiceAgent initialized successfully.")

def test_voice_agent_transcription():
    print("\n[TEST] Transcribing sample audio file (jfk.wav)...")
    assert JFK_WAV_PATH.exists(), f"Sample audio file not found at {JFK_WAV_PATH}"
    
    agent = VoiceAgent()
    result = asyncio.run(agent.transcribe_file(str(JFK_WAV_PATH)))
    
    assert result["error"] is None, f"Transcription error: {result['error']}"
    text = result["text"].lower()
    print(f"-> Transcribed Text: \"{result['text']}\"")
    assert "fellow" in text or "americans" in text or "country" in text, f"Unexpected transcription: {text}"
    print("-> VoiceAgent transcription test passed.")

def test_generate_voice_response_no_context():
    print("\n[TEST] Generating voice response without active context...")
    main.latest_contexts = {}
    
    response = asyncio.run(generate_voice_response("How is my stress today?"))
    print(f"-> FRIDAY Response: \"{response}\"")
    assert isinstance(response, str), "Response should be a string"
    assert len(response) > 0, "Response should not be empty"
    print("-> Response generation without context passed.")

def test_generate_voice_response_with_context():
    print("\n[TEST] Generating voice response with mocked active context...")
    # Inject a mock context
    main.latest_contexts = {
        "test-session-123": {
            "sensor_data": {
                "location": "library",
                "ambient_noise_db": 32,
                "ambient_light_lux": 150,
                "app_switches": 1,
                "typo_rate": 0.0,
            },
            "user_state": {
                "stress_score": 25,
            },
            "active_task": {
                "name": "Empathetic Intelligence Research Paper"
            }
        }
    }
    
    response = asyncio.run(generate_voice_response("Where am I and how is my stress?"))
    print(f"-> FRIDAY Response: \"{response}\"")
    assert isinstance(response, str), "Response should be a string"
    assert len(response) > 0, "Response should not be empty"
    print("-> Response generation with context passed.")

def test_voice_websocket_text_query():
    print("\n[TEST] Testing voice WebSocket with text query...")
    with TestClient(app) as client:
        with client.websocket_connect("/ws/voice") as websocket:
            # Send start control message
            websocket.send_json({"type": "voice_start", "format": "text"})
            # Send end control message with query
            websocket.send_json({"type": "voice_end", "text": "What is my active task?"})
            
            # Receive response
            data = websocket.receive_json()
            print(f"-> WebSocket Response: {data}")
            assert data["type"] == "VOICE_TRANSCRIPTION", f"Unexpected response type: {data['type']}"
            assert data["text"] == "What is my active task?", f"Unexpected query text: {data['text']}"
            assert "response" in data, "Response key missing from WebSocket response"
            assert isinstance(data["response"], str), "Response should be a string"
            print("-> Voice WebSocket text query test passed.")

def test_voice_websocket_audio_stream():
    print("\n[TEST] Testing voice WebSocket with audio streaming (jfk.wav)...")
    assert JFK_WAV_PATH.exists(), f"Sample audio file not found at {JFK_WAV_PATH}"
    
    with TestClient(app) as client:
        with client.websocket_connect("/ws/voice") as websocket:
            # Send start control message
            websocket.send_json({"type": "voice_start", "format": "wav"})
            
            # Send audio bytes
            audio_bytes = JFK_WAV_PATH.read_bytes()
            websocket.send_bytes(audio_bytes)
            
            # Send end control message
            websocket.send_json({"type": "voice_end"})
            
            # Receive response
            data = websocket.receive_json()
            print(f"-> WebSocket Response: {data}")
            assert data["type"] == "VOICE_TRANSCRIPTION", f"Unexpected response type: {data['type']}"
            text = data["text"].lower()
            assert "fellow" in text or "americans" in text or "country" in text, f"Unexpected transcription text: {data['text']}"
            assert "response" in data, "Response key missing from WebSocket response"
            assert isinstance(data["response"], str), "Response should be a string"
            print("-> Voice WebSocket audio stream test passed.")

def run_all_tests():
    print("=" * 60)
    print("Running FRIDAY Voice Assistant Test Suite")
    print("=" * 60)
    
    test_voice_agent_initialization()
    test_voice_agent_transcription()
    test_generate_voice_response_no_context()
    test_generate_voice_response_with_context()
    test_voice_websocket_text_query()
    test_voice_websocket_audio_stream()
    
    print("=" * 60)
    print("All tests completed successfully!")
    print("=" * 60)

if __name__ == "__main__":
    run_all_tests()
