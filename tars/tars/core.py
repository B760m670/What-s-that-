"""The orchestrator: wires the four sockets together into one TARS."""
from __future__ import annotations

from .brains import build_brain
from .config import Settings
from .memory import Memory
from .personality import build_system_prompt


class Tars:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.memory = Memory(settings.memory_path)
        self.brain = build_brain(settings)

    def respond(self, user_text: str) -> str:
        system = build_system_prompt(self.settings.personality, self.memory.facts_text())
        history = self.memory.recent_turns(self.settings.history_turns)
        messages = history + [{"role": "user", "content": user_text}]
        reply = self.brain.reply(system, messages)
        self.memory.add_turn("user", user_text)
        self.memory.add_turn("assistant", reply)
        return reply
