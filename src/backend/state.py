"""
FRIDAY - shared state module.
Breaks circular import between main.py and injection/decision.py.
"""

import asyncio
from typing import Optional

_ollama_lock = asyncio.Lock()
_voice_active: bool = False


def get_ollama_lock() -> asyncio.Lock:
    return _ollama_lock


def is_voice_active() -> bool:
    return _voice_active


def set_voice_active(value: bool) -> None:
    global _voice_active
    _voice_active = value
