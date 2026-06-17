"""Ollama — fully local/offline brain. Free, private, no network.

Run `ollama serve` and `ollama pull qwen2.5:3b` (a small model that fits modest
hardware). This is the fallback when there's no internet.
"""
from __future__ import annotations

from typing import Dict, List

from .base import Brain, BrainError
from ._http import get_json, post_json


class OllamaBrain(Brain):
    name = "ollama"

    def __init__(self, url, model):
        self.url = url.rstrip("/")
        self.model = model

    def available(self) -> bool:
        try:
            get_json("{}/api/version".format(self.url), timeout=2)
            return True
        except Exception:
            return False

    def reply(self, system: str, messages: List[Dict[str, str]]) -> str:
        msgs = [{"role": "system", "content": system}]
        msgs.extend({"role": m["role"], "content": m["content"]} for m in messages)
        payload = {
            "model": self.model,
            "messages": msgs,
            "stream": False,
            "options": {"temperature": 0.9},
        }
        data = post_json("{}/api/chat".format(self.url), payload, timeout=180)
        try:
            return data["message"]["content"].strip()
        except KeyError:
            raise BrainError("unexpected Ollama response: {}".format(str(data)[:300]))
