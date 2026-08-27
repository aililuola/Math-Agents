from __future__ import annotations

import argparse
import json
from pathlib import Path

from mathproofmesh.computation.sandbox import run_sandboxed_python
from mathproofmesh.config import load_config
from mathproofmesh.schemas import ComputationMethod, ExperimentProgram, ExperimentSpec


def _probe() -> tuple[ExperimentSpec, ExperimentProgram]:
    spec = ExperimentSpec(
        target_claim="The bounded search finds 7 as the positive square root of 49.",
        reasoning_basis="A small exact search is sufficient to test the sandbox runtime.",
        why_computation_is_needed="This probe must execute the Docker backend itself.",
        decision_if_confirmed="Accept the local sandbox installation as operational.",
        decision_if_refuted="Reject the local sandbox installation and inspect its output.",
        noncomputational_alternative="Configuration validation alone cannot test Docker execution.",
        method=ComputationMethod.SANDBOXED_PYTHON,
        arguments={"input": {"limit": 20, "target": 49}},
        typed_tool_gap="This installation probe intentionally exercises the fallback backend.",
        max_cases=20,
    )
    source = """def run(data):
    matches = []
    for n in range(2, data["limit"] + 1):
        if n * n == data["target"]:
            matches.append(n)
    return {
        "outcome": "not_refuted",
        "cases_checked": data["limit"] - 1,
        "scope": {"lower": 2, "upper": data["limit"]},
        "exact_arithmetic": True,
        "certificate": {"matches": matches},
    }
"""
    program = ExperimentProgram(
        experiment_id=spec.experiment_id,
        source=source,
        input_schema={
            "type": "object",
            "properties": {
                "limit": {"type": "integer"},
                "target": {"type": "integer"},
                "seed": {"type": "integer"},
            },
            "required": ["limit", "target", "seed"],
        },
        output_schema={
            "type": "object",
            "properties": {
                "outcome": {"type": "string"},
                "cases_checked": {"type": "integer"},
                "scope": {"type": "object"},
                "exact_arithmetic": {"type": "boolean"},
                "certificate": {"type": "object"},
            },
            "required": [
                "outcome",
                "cases_checked",
                "scope",
                "exact_arithmetic",
            ],
        },
    )
    return spec, program


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run the MathProofMesh Docker sandbox probe."
    )
    parser.add_argument(
        "--config",
        type=Path,
        default=Path("config.deepseek-v4-pro.yaml"),
    )
    args = parser.parse_args()
    computation = load_config(args.config).computation
    if not computation.sandboxed_python_enabled:
        raise SystemExit("sandboxed Python is disabled in the selected config")

    spec, program = _probe()
    evidence = run_sandboxed_python(spec, program, computation)
    matches = (evidence.certificate or {}).get("matches")
    if matches != [7] or evidence.cases_checked != 19:
        raise SystemExit("sandbox probe returned an unexpected result")
    print(
        json.dumps(
            {
                "status": "ok",
                "image": computation.sandbox_image,
                "outcome": evidence.outcome.value,
                "evidence_strength": evidence.evidence_strength.value,
                "cases_checked": evidence.cases_checked,
                "certificate": evidence.certificate,
            },
            ensure_ascii=True,
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
