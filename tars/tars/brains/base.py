"""The Brain interface every driver implements."""
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Dict, List


class BrainError(Exception):
    """A brain driver could not produce a reply (bad key, network, etc.)."""


class Brain(ABC):
    name = "brain"

    @abstractmethod
    def available(self) -> bool:
        """Can this driver be used right now (configured / reachable)?"""

    @abstractmethod
    def reply(self, system: str, messages: List[Dict[str, str]]) -> str:
        """Given a system prompt and a list of {role, content} turns
        (role is 'user' or 'assistant'), return TARS's reply text."""
