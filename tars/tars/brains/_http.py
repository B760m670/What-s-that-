"""Tiny stdlib HTTP helpers so the cloud brains need no third-party packages."""
from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Dict, Optional

from .base import BrainError


def post_json(url: str, payload: dict, headers: Optional[Dict[str, str]] = None,
              timeout: int = 60) -> dict:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        raise BrainError("HTTP {}: {}".format(e.code, body[:300])) from e
    except urllib.error.URLError as e:
        raise BrainError("network error: {}".format(e.reason)) from e


def get_json(url: str, timeout: int = 5) -> dict:
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, ValueError) as e:
        raise BrainError(str(e)) from e
