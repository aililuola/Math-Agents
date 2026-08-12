"""Read-only Python oracle for Java-to-Python canonical JSON differential tests."""

from __future__ import annotations

import hashlib
import json
import sys
from typing import Any


def canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def stable_hash(value: Any, mode: str) -> str:
    if mode == "raw_utf8_string":
        raw = value.encode("utf-8")
    elif mode == "canonical_json":
        raw = canonical_json(value).encode("utf-8")
    else:
        raise ValueError(f"unsupported hash mode: {mode}")
    return hashlib.sha256(raw).hexdigest()


def main() -> int:
    for raw_line in sys.stdin:
        if not raw_line.strip():
            continue
        request = json.loads(raw_line)
        value = request["input"]
        mode = request["mode"]
        response = {
            "canonical_json": canonical_json(value),
            "stable_hash": stable_hash(value, mode),
        }
        sys.stdout.write(
            json.dumps(response, ensure_ascii=False, separators=(",", ":")) + "\n"
        )
        sys.stdout.flush()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
