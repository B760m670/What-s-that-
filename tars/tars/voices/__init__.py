"""Voice socket (text-to-speech). Interface only in milestone 1; the real driver
is an AI clone of the ORIGINAL TARS voice (F5-TTS / XTTS / RVC) in milestone 3,
not a robotic synthesizer."""
from .base import Voice

__all__ = ["Voice"]
