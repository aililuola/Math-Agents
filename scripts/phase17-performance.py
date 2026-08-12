#!/usr/bin/env python3
"""Aggregate phase-17 benchmark fragments and enforce the 20 percent budget."""

from __future__ import annotations

import ctypes
import json
import os
import platform
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
FRAGMENT_ROOT = ROOT / "target" / "benchmark-reports"
REFERENCE = ROOT / "migration" / "baseline" / "phase-17-performance-reference.json"
REPORT = ROOT / "migration" / "reports" / "phase-17-performance.json"
MAX_REGRESSION = 1.20
SCENARIOS = {
    "message_admission_dedup_delivery_10000": (
        "phase17-message.json",
        ("large_elapsed_ns",),
    ),
    "concurrent_mock_agent_calls_100": (
        "phase17-concurrent-mock.json",
        ("elapsed_ns",),
    ),
    "large_proof_graph": (
        "phase17-proof-graph.json",
        (
            "build_elapsed_ns",
            "closure_and_debt_elapsed_ns",
            "counterexample_propagation_elapsed_ns",
        ),
    ),
    "checkpoint_outbox_retry_1000": (
        "phase17-checkpoint-outbox.json",
        (
            "checkpoint_elapsed_ns",
            "outbox_insert_elapsed_ns",
            "claim_release_reclaim_publish_elapsed_ns",
        ),
    ),
    "python_sidecar_cold_warm": (
        "phase17-python-sidecar.json",
        ("cold_elapsed_ns", "warm_total_elapsed_ns"),
    ),
    "sse_long_stream_resume": (
        "phase17-sse-resume.json",
        ("stream_elapsed_ns", "resume_elapsed_ns"),
    ),
    "temporal_multi_route_replay_continue_as_new": (
        "phase17-temporal.json",
        (
            "multi_route_elapsed_ns",
            "replay_elapsed_ns",
            "continue_as_new_elapsed_ns",
        ),
    ),
}


def memory_bytes() -> int:
    if os.name == "nt":
        class MemoryStatus(ctypes.Structure):
            _fields_ = [
                ("length", ctypes.c_ulong),
                ("memory_load", ctypes.c_ulong),
                ("total_physical", ctypes.c_ulonglong),
                ("available_physical", ctypes.c_ulonglong),
                ("total_page_file", ctypes.c_ulonglong),
                ("available_page_file", ctypes.c_ulonglong),
                ("total_virtual", ctypes.c_ulonglong),
                ("available_virtual", ctypes.c_ulonglong),
                ("available_extended_virtual", ctypes.c_ulonglong),
            ]

        status = MemoryStatus()
        status.length = ctypes.sizeof(status)
        if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
            return int(status.total_physical)
        return 0
    try:
        return int(os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES"))
    except (AttributeError, OSError, ValueError):
        return 0


def java_version() -> str:
    executable = ROOT / ".tools" / "jdk-25" / "bin" / (
        "java.exe" if os.name == "nt" else "java"
    )
    if not executable.is_file():
        java_home = os.environ.get("JAVA_HOME")
        if java_home:
            executable = Path(java_home) / "bin" / (
                "java.exe" if os.name == "nt" else "java"
            )
    if not executable.is_file():
        discovered = shutil.which("java")
        if not discovered:
            raise FileNotFoundError("JDK 25 java executable was not found")
        executable = Path(discovered)
    completed = subprocess.run(
        [str(executable), "-version"],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return " | ".join(
        line.strip()
        for line in (completed.stderr + completed.stdout).splitlines()
        if line.strip()
    )


def machine() -> dict[str, object]:
    processor = platform.processor() or os.environ.get("PROCESSOR_IDENTIFIER", "")
    java = java_version()
    return {
        "os": platform.system(),
        "os_release": platform.release(),
        "architecture": platform.machine(),
        "processor": processor,
        "logical_cpus": os.cpu_count() or 0,
        "physical_memory_bytes": memory_bytes(),
        "jdk": java,
        "jvm_flags": {
            "MAVEN_OPTS": os.environ.get("MAVEN_OPTS", ""),
            "JAVA_TOOL_OPTIONS": os.environ.get("JAVA_TOOL_OPTIONS", ""),
            "surefire": "@{argLine} (JaCoCo agent during verify)",
        },
    }


def fingerprint(data: dict[str, object]) -> dict[str, object]:
    return {
        key: data[key]
        for key in (
            "os",
            "architecture",
            "processor",
            "logical_cpus",
            "physical_memory_bytes",
            "jdk",
        )
    }


def read_fragments() -> tuple[dict[str, object], list[str]]:
    results: dict[str, object] = {}
    failures: list[str] = []
    for scenario, (filename, fields) in SCENARIOS.items():
        path = FRAGMENT_ROOT / filename
        if not path.is_file():
            failures.append(f"missing benchmark fragment: {path.relative_to(ROOT)}")
            continue
        try:
            fragment = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            failures.append(f"invalid benchmark fragment {filename}: {error}")
            continue
        if fragment.get("result") != "PASS":
            failures.append(f"benchmark fragment did not pass: {filename}")
        values: dict[str, int] = {}
        for field in fields:
            value = fragment.get(field)
            if not isinstance(value, int) or value <= 0:
                failures.append(f"{filename} has invalid positive metric {field}")
                continue
            values[field] = value
        metric_ns = sum(values.values())
        results[scenario] = {
            "fragment": path.relative_to(ROOT).as_posix(),
            "metric_definition": " + ".join(fields),
            "metric_ns": metric_ns,
            "measurements_ns": values,
            "evidence": fragment,
        }
    return results, failures


def main() -> int:
    now = datetime.now(timezone.utc).isoformat()
    environment = machine()
    results, failures = read_fragments()
    comparison: dict[str, object] = {}
    reference_action = "compared"

    if not failures and not REFERENCE.is_file():
        reference_action = "established"
        reference_payload = {
            "schema_version": "1.0",
            "phase": "17",
            "established_at_utc": now,
            "policy": (
                "First passing reference on the recorded machine. Subsequent runs "
                "must be no more than 20 percent slower for every scenario."
            ),
            "machine_fingerprint": fingerprint(environment),
            "scenarios": {
                name: {
                    "metric_definition": value["metric_definition"],
                    "metric_ns": value["metric_ns"],
                }
                for name, value in results.items()
            },
        }
        REFERENCE.parent.mkdir(parents=True, exist_ok=True)
        REFERENCE.write_text(
            json.dumps(reference_payload, indent=2) + "\n", encoding="utf-8"
        )

    if REFERENCE.is_file():
        try:
            reference = json.loads(REFERENCE.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            failures.append(f"invalid performance reference: {error}")
            reference = {}
        expected_machine = reference.get("machine_fingerprint")
        current_machine = fingerprint(environment)
        if expected_machine != current_machine:
            failures.append("performance reference machine fingerprint changed")
        reference_scenarios = reference.get("scenarios", {})
        for scenario, current in results.items():
            expected = reference_scenarios.get(scenario, {})
            baseline = expected.get("metric_ns")
            if not isinstance(baseline, int) or baseline <= 0:
                failures.append(f"reference is missing scenario {scenario}")
                continue
            ratio = current["metric_ns"] / baseline
            passed = ratio <= MAX_REGRESSION
            if not passed:
                failures.append(
                    f"{scenario} regression ratio {ratio:.6f} exceeds {MAX_REGRESSION:.2f}"
                )
            comparison[scenario] = {
                "reference_ns": baseline,
                "current_ns": current["metric_ns"],
                "ratio": round(ratio, 6),
                "maximum_ratio": MAX_REGRESSION,
                "result": "PASS" if passed else "FAIL",
            }

    payload = {
        "schema_version": "1.0",
        "phase": "17",
        "generated_at_utc": now,
        "result": "PASS" if not failures else "FAIL",
        "policy": (
            "Seven required scenarios use elapsed monotonic time. The first passing "
            "run is the immutable local reference; every later scenario must remain "
            "within 20 percent on the same machine fingerprint."
        ),
        "reference": REFERENCE.relative_to(ROOT).as_posix(),
        "reference_action": reference_action,
        "machine": environment,
        "scenarios": results,
        "comparison": comparison,
        "failures": failures,
    }
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"PHASE 17 PERFORMANCE: {payload['result']} ({reference_action})")
    for failure in failures:
        print(f"  - {failure}", file=sys.stderr)
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
