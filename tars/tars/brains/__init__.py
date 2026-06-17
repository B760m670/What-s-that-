"""Brain socket: swappable LLM drivers + a hybrid router that falls back across
them (free cloud first, local offline second)."""
from __future__ import annotations

from ..config import Settings
from .base import Brain, BrainError
from .gemini import GeminiBrain
from .groq import GroqBrain
from .ollama import OllamaBrain
from .router import BrainRouter


def build_brain(settings: Settings) -> BrainRouter:
    registry = {
        "gemini": lambda: GeminiBrain(settings.gemini_key, settings.gemini_model),
        "groq": lambda: GroqBrain(settings.groq_key, settings.groq_model),
        "ollama": lambda: OllamaBrain(settings.ollama_url, settings.ollama_model),
    }
    brains = [registry[name]() for name in settings.brain_order if name in registry]
    return BrainRouter(brains)


__all__ = ["Brain", "BrainError", "BrainRouter", "build_brain"]
