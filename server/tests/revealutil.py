"""Test helper: scratch every card. A balance and a claim count only SCRATCHED rewards (an
unopened card is not shown, so it is not summed or paid), so tests that earn rewards and then
read the balance have to open them first — the way a person does."""
from __future__ import annotations

import time


def scratch_all(store) -> None:
    with store._lock, store._db:
        store._db.execute("UPDATE rewards SET revealed_at=? WHERE revealed_at IS NULL", (time.time(),))
