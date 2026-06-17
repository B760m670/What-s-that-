"""The Ears interface a speech-to-text driver will implement."""
from __future__ import annotations

from abc import ABC, abstractmethod


class Ears(ABC):
    @abstractmethod
    def listen(self) -> str:
        """Block until speech is heard, then return the recognized text."""
