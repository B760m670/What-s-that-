"""Google Gemini (AI Studio) — free tier, no credit card."""
from __future__ import annotations

from typing import Dict, List

from .base import Brain, BrainError
from ._http import post_json

_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}"


class GeminiBrain(Brain):
    name = "gemini"

    def __init__(self, api_key, model):
        self.api_key = api_key
        self.model = model

    def available(self) -> bool:
        return bool(self.api_key)

    def reply(self, system: str, messages: List[Dict[str, str]]) -> str:
        contents = [
            {
                "role": "user" if m["role"] == "user" else "model",
                "parts": [{"text": m["content"]}],
            }
            for m in messages
        ]
        payload = {
            "system_instruction": {"parts": [{"text": system}]},
            "contents": contents,
            "generationConfig": {"temperature": 0.9},
        }
        url = _ENDPOINT.format(model=self.model, key=self.api_key)
        data = post_json(url, payload)
        try:
            return data["candidates"][0]["content"]["parts"][0]["text"].strip()
        except (KeyError, IndexError):
            raise BrainError("unexpected Gemini response: {}".format(str(data)[:300]))
