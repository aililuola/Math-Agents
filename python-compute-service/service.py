"""One-line UTF-8 JSON-RPC 2.0 process service. No sockets are opened."""

from __future__ import annotations

import hashlib
import json
import sys
import time
from typing import Any

import sympy
import z3

from handlers import execute


PROTOCOL_VERSION = "1.0"
TOOL_VERSION = f"mathproofmesh-python-compute/0.8.0;sympy={sympy.__version__};z3={z3.get_version_string()}"
MAX_REQUEST_BYTES = 1_000_000
MAX_RESPONSE_BYTES = 2_000_000
REQUEST_FIELDS = {
    "jsonrpc",
    "protocol_version",
    "request_id",
    "method",
    "params",
    "limits",
}
LIMIT_FIELDS = {"max_cases", "seed", "timeout_ms", "max_output_bytes"}
METHODS = {
    "sympy_simplify",
    "sympy_equivalent",
    "polynomial_factor",
    "numeric_counterexample",
    "real_inequality",
}


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=True,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def response_hash(result_or_error: Any) -> str:
    return hashlib.sha256(canonical_bytes(result_or_error)).hexdigest()


def validate_request(request: Any) -> tuple[str, str, dict[str, Any], dict[str, Any]]:
    if not isinstance(request, dict):
        raise ProtocolError(-32600, "request must be a JSON object")
    unknown = sorted(set(request) - REQUEST_FIELDS)
    missing = sorted(REQUEST_FIELDS - set(request))
    if unknown:
        raise ProtocolError(-32600, f"unsupported request fields: {', '.join(unknown)}")
    if missing:
        raise ProtocolError(-32600, f"missing request fields: {', '.join(missing)}")
    if request["jsonrpc"] != "2.0":
        raise ProtocolError(-32600, "jsonrpc must be 2.0")
    if request["protocol_version"] != PROTOCOL_VERSION:
        raise ProtocolError(-32600, "unsupported protocol_version")
    request_id = request["request_id"]
    if (
        not isinstance(request_id, str)
        or not request_id
        or len(request_id) > 128
        or any(ord(character) < 33 or ord(character) > 126 for character in request_id)
    ):
        raise ProtocolError(-32600, "request_id must be 1..128 printable ASCII characters")
    method = request["method"]
    if not isinstance(method, str) or method not in METHODS:
        raise ProtocolError(-32601, "unknown computation method")
    params = request["params"]
    limits = request["limits"]
    if not isinstance(params, dict) or not isinstance(limits, dict):
        raise ProtocolError(-32602, "params and limits must be JSON objects")
    unknown_limits = sorted(set(limits) - LIMIT_FIELDS)
    if unknown_limits:
        raise ProtocolError(
            -32602, f"unsupported limit fields: {', '.join(unknown_limits)}"
        )
    max_cases = limits.get("max_cases", 100_000)
    seed = limits.get("seed", 20260719)
    timeout_ms = limits.get("timeout_ms", 10_000)
    max_output = limits.get("max_output_bytes", MAX_RESPONSE_BYTES)
    for label, value, minimum, maximum in (
        ("max_cases", max_cases, 1, 100_000_000),
        ("seed", seed, -(2**63), 2**63 - 1),
        ("timeout_ms", timeout_ms, 1, 60_000),
        ("max_output_bytes", max_output, 256, MAX_RESPONSE_BYTES),
    ):
        if isinstance(value, bool) or not isinstance(value, int):
            raise ProtocolError(-32602, f"{label} must be an integer")
        if not minimum <= value <= maximum:
            raise ProtocolError(-32602, f"{label} is outside its allowed range")
    return request_id, method, params, limits


def handle(request: Any) -> dict[str, Any]:
    started = time.process_time_ns()
    request_id = request.get("request_id", "") if isinstance(request, dict) else ""
    try:
        request_id, method, params, limits = validate_request(request)
        result = execute(method, params, limits)
        payload = {
            "jsonrpc": "2.0",
            "protocol_version": PROTOCOL_VERSION,
            "request_id": request_id,
            "result": result,
            "error": None,
            "certificate": result.get("certificate"),
            "stdout_hash": response_hash(result),
            "tool_version": TOOL_VERSION,
            "cpu_ms": max(0, (time.process_time_ns() - started) // 1_000_000),
        }
        maximum = int(limits.get("max_output_bytes", MAX_RESPONSE_BYTES))
        if len(canonical_bytes(payload)) > maximum:
            raise ProtocolError(-32001, "response exceeds max_output_bytes")
        return payload
    except ProtocolError as exc:
        error = {"code": exc.code, "message": exc.message}
    except KeyError:
        error = {"code": -32602, "message": "required method parameter is missing"}
    except (ArithmeticError, TypeError, ValueError) as exc:
        message = str(exc).replace("\r", " ").replace("\n", " ")[:1000]
        error = {"code": -32602, "message": message}
    except Exception as exc:  # Fail closed without leaking a traceback.
        error = {
            "code": -32000,
            "message": f"sidecar execution failed: {type(exc).__name__}",
        }
    return {
        "jsonrpc": "2.0",
        "protocol_version": PROTOCOL_VERSION,
        "request_id": request_id,
        "result": None,
        "error": error,
        "certificate": None,
        "stdout_hash": response_hash(error),
        "tool_version": TOOL_VERSION,
        "cpu_ms": max(0, (time.process_time_ns() - started) // 1_000_000),
    }


def main() -> int:
    if sys.argv[1:] == ["--self-check"]:
        print(
            json.dumps(
                {
                    "status": "ok",
                    "protocol_version": PROTOCOL_VERSION,
                    "tool_version": TOOL_VERSION,
                    "transport": "stdin-stdout-jsonl",
                    "tcp": False,
                },
                sort_keys=True,
            )
        )
        return 0
    while True:
        raw = sys.stdin.buffer.readline(MAX_REQUEST_BYTES + 1)
        if not raw:
            return 0
        if len(raw) > MAX_REQUEST_BYTES or not raw.endswith(b"\n"):
            response = handle({"request_id": ""})
        else:
            try:
                response = handle(json.loads(raw.decode("utf-8")))
            except (UnicodeDecodeError, json.JSONDecodeError):
                response = handle({"request_id": ""})
        encoded = canonical_bytes(response)
        if len(encoded) > MAX_RESPONSE_BYTES:
            return 70
        sys.stdout.buffer.write(encoded + b"\n")
        sys.stdout.buffer.flush()


class ProtocolError(Exception):
    def __init__(self, code: int, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


if __name__ == "__main__":
    raise SystemExit(main())
