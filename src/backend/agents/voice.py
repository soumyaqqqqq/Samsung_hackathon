"""
FRIDAY - VoiceAgent
Transcribes audio files using whisper.cpp binary for near-native CPU performance.

Pipeline:
  1. Receive raw audio bytes (any format: AMR, AAC, OGG, WAV, etc.)
  2. Convert to 16kHz mono WAV via ffmpeg (required by whisper.cpp)
  3. Run whisper-cli binary for transcription
  4. Return clean text output

Designed to be called from the orchestrator or directly from main.py
when audio data arrives via WebSocket.
"""

import asyncio
import logging
import os
import subprocess
import tempfile
import time
import uuid
from pathlib import Path

logger = logging.getLogger("friday.voice")

# Resolve paths relative to the backend root (where main.py lives)
_BACKEND_DIR = Path(__file__).resolve().parent.parent
_WHISPER_CLI = _BACKEND_DIR / "whisper.cpp" / "build" / "bin" / "whisper-cli"
_MODEL_PATH = _BACKEND_DIR / "whisper.cpp" / "models" / "ggml-base.bin"
_AUDIO_DIR = _BACKEND_DIR / "data" / "audio"

# whisper.cpp shared libraries need to be on LD_LIBRARY_PATH
_LIB_DIRS = ":".join([
    str(_BACKEND_DIR / "whisper.cpp" / "build" / "src"),
    str(_BACKEND_DIR / "whisper.cpp" / "build" / "ggml" / "src"),
])


class VoiceAgent:
    """Transcribes audio to text using the whisper.cpp binary."""

    def __init__(
        self,
        model_path: str | None = None,
        binary_path: str | None = None,
        language: str = "en",
    ):
        self.model_path = Path(model_path) if model_path else _MODEL_PATH
        self.binary_path = Path(binary_path) if binary_path else _WHISPER_CLI
        self.language = language

        # Validate at init time so we fail fast
        if not self.binary_path.exists():
            raise FileNotFoundError(
                f"whisper-cli binary not found at {self.binary_path}. "
                f"Run 'make' inside whisper.cpp/ to build it."
            )
        if not self.model_path.exists():
            raise FileNotFoundError(
                f"Whisper model not found at {self.model_path}. "
                f"Run: bash whisper.cpp/models/download-ggml-model.sh base"
            )

        # Ensure audio staging directory exists
        _AUDIO_DIR.mkdir(parents=True, exist_ok=True)
        logger.info(
            f"VoiceAgent ready: binary={self.binary_path.name}, "
            f"model={self.model_path.name}, lang={self.language}"
        )

    # ──────────────────────────────────────────────────────────────────────
    # Public API
    # ──────────────────────────────────────────────────────────────────────

    async def transcribe_bytes(self, audio_data: bytes, source_format: str = "wav") -> dict:
        """
        Transcribe raw audio bytes. Returns a dict with:
          - text: the transcribed text
          - duration_ms: wall-clock time for transcription
          - audio_file: path to the saved WAV file
          - error: error message if transcription failed (None on success)
        """
        t0 = time.time()
        file_id = uuid.uuid4().hex[:12]

        # 1. Save raw bytes to a temporary file
        raw_path = _AUDIO_DIR / f"{file_id}_raw.{source_format}"
        raw_path.write_bytes(audio_data)

        try:
            # 2. Convert to 16kHz mono WAV (whisper.cpp requirement)
            wav_path = _AUDIO_DIR / f"{file_id}.wav"
            await self._convert_to_wav(raw_path, wav_path)

            # 3. Run whisper-cli
            text = await self._run_whisper(wav_path)

            duration_ms = int((time.time() - t0) * 1000)
            logger.info(
                f"Transcription complete: {len(text)} chars in {duration_ms}ms"
            )

            return {
                "text": text,
                "duration_ms": duration_ms,
                "audio_file": str(wav_path),
                "error": None,
            }

        except Exception as e:
            duration_ms = int((time.time() - t0) * 1000)
            logger.error(f"Transcription failed after {duration_ms}ms: {e}")
            return {
                "text": "",
                "duration_ms": duration_ms,
                "audio_file": str(raw_path),
                "error": str(e),
            }
        finally:
            # Clean up the raw upload (keep the WAV for debugging/replay)
            if raw_path.exists() and raw_path.suffix != ".wav":
                raw_path.unlink(missing_ok=True)

    async def transcribe_file(self, audio_file_path: str) -> dict:
        """Transcribe from an existing file on disk."""
        path = Path(audio_file_path)
        if not path.exists():
            return {"text": "", "duration_ms": 0, "audio_file": str(path), "error": "File not found"}

        audio_data = path.read_bytes()
        return await self.transcribe_bytes(audio_data, source_format=path.suffix.lstrip("."))

    # ──────────────────────────────────────────────────────────────────────
    # Orchestrator-compatible interface
    # ──────────────────────────────────────────────────────────────────────

    async def run(self, ctx: dict, prev_results: dict, db, chroma) -> dict:
        """
        Agent chain interface. Expects ctx to contain:
          ctx["voice_audio_bytes"] — raw audio bytes
          ctx["voice_source_format"] — e.g. "amr", "ogg", "wav"
        """
        audio_bytes = ctx.get("voice_audio_bytes")
        if not audio_bytes:
            return {"text": "", "error": "No voice_audio_bytes in context"}

        source_fmt = ctx.get("voice_source_format", "wav")
        result = await self.transcribe_bytes(audio_bytes, source_format=source_fmt)
        return result

    # ──────────────────────────────────────────────────────────────────────
    # Internal helpers
    # ──────────────────────────────────────────────────────────────────────

    async def _convert_to_wav(self, input_path: Path, output_path: Path) -> None:
        """Convert any audio format to 16kHz mono WAV using ffmpeg."""
        cmd = [
            "ffmpeg", "-y",             # overwrite output
            "-i", str(input_path),      # input file
            "-ar", "16000",             # 16kHz sample rate
            "-ac", "1",                 # mono
            "-c:a", "pcm_s16le",        # 16-bit PCM
            str(output_path),
        ]

        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        _, stderr = await proc.communicate()

        if proc.returncode != 0:
            err_msg = stderr.decode().strip().split("\n")[-1] if stderr else "Unknown error"
            raise RuntimeError(f"ffmpeg conversion failed: {err_msg}")

        if not output_path.exists() or output_path.stat().st_size == 0:
            raise RuntimeError("ffmpeg produced empty output")

        logger.debug(f"Converted {input_path.name} → {output_path.name}")

    async def _run_whisper(self, wav_path: Path) -> str:
        """Run whisper-cli and return the transcribed text."""
        cmd = [
            str(self.binary_path),
            "-m", str(self.model_path),
            "-f", str(wav_path),
            "-l", self.language,
            "--no-timestamps",
            "-t", str(min(os.cpu_count() or 4, 8)),  # cap threads
        ]

        env = os.environ.copy()
        existing_ld = env.get("LD_LIBRARY_PATH", "")
        env["LD_LIBRARY_PATH"] = f"{_LIB_DIRS}:{existing_ld}" if existing_ld else _LIB_DIRS

        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env=env,
        )
        stdout, stderr = await proc.communicate()

        if proc.returncode != 0:
            err_msg = stderr.decode().strip().split("\n")[-1] if stderr else "Unknown error"
            raise RuntimeError(f"whisper-cli failed (exit {proc.returncode}): {err_msg}")

        # whisper-cli writes transcript to stdout, logs to stderr
        text = stdout.decode().strip()

        # Clean up leading/trailing whitespace and empty lines
        lines = [line.strip() for line in text.split("\n") if line.strip()]
        return " ".join(lines)