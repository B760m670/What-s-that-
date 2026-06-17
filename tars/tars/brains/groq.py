"""Groq — free tier, OpenAI-compatible API, very fast (Llama 3.3 70B)."""
from __future__ import annotations

from typing import Dict, List

from .base import Brain, BrainError
from ._http import post_json

_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"


class GroqBrain(Brain):
    name = "groq"

    def __init__(self, api_key, model):
        self.api_key = api_key
        self.model = model

    def available(self) -> bool:
        return bool(self.api_key)

    def reply(self, system: str, messages: List[Dict[str, str]]) -> str:
        msgs = [{"role": "system", "content": system}]
        msgs.extend({"role": m["role"], "content": m["content"]} for m in messages)
        payload = {"model": self.model, "messages": msgs, "temperature": 0.9}
        data = post_json(
            _ENDPOINT, payload,
            headers={"Authorization": "Bearer {}".format(self.api_key)},
        )
        try:
            return data["choices"][0]["message"]["content"].strip()
        except (KeyError, IndexError):
            raise BrainError("unexpected Groq response: {}".format(str(data)[:300]))
