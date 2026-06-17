"""The hybrid brain: try each driver in order, fall back on failure.

This is what makes TARS feel like one mind that thinks "outside" (free cloud)
when online and "inside" (local model) when offline.
"""
from __future__ import annotations

from typing import Dict, List

from .base import Brain, BrainError


class BrainRouter(Brain):
    name = "router"

    def __init__(self, brains: List[Brain]):
        self.brains = brains
        self.last_used = None

    def available(self) -> bool:
        return any(b.available() for b in self.brains)

    def reply(self, system: str, messages: List[Dict[str, str]]) -> str:
        errors = []
        for brain in self.brains:
            if not brain.available():
                continue
            try:
                out = brain.reply(system, messages)
                self.last_used = brain.name
                return out
            except BrainError as e:
                errors.append("{}: {}".format(brain.name, e))
        if errors:
            raise BrainError("no brain could answer -> " + " | ".join(errors))
        raise BrainError(
            "no brain available. Set GEMINI_API_KEY or GROQ_API_KEY (free, no "
            "card), or run Ollama locally."
        )
