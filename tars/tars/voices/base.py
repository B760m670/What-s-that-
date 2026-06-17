"""The Voice interface a text-to-speech driver will implement.

The milestone 3 driver plugs an AI voice clone of the original TARS voice in
here. Where the clone actually runs (on-device, a paired PC, or a free GPU
backend) is just a config detail behind this interface.
"""
from __future__ import annotations

from abc import ABC, abstractmethod


class Voice(ABC):
    @abstractmethod
    def speak(self, text: str) -> None:
        """Render text as the TARS voice and play it."""
