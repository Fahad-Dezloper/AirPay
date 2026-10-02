"""Test helper: a live session for an address, made directly in the store (the signing path has
its own tests in test_auth.py) so HTTP tests can act as a particular wallet."""
from __future__ import annotations

import secrets
import time

from duffleserver.auth import TOKEN_PREFIX, token_hash


def session_for(svc, address: str, ttl: float = 3600.0) -> str:
    token = TOKEN_PREFIX + secrets.token_urlsafe(32)
    svc.store.put_session(token_hash(token), address, time.time(), time.time() + ttl, 10)
    return token
