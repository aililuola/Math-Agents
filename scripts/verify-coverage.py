#!/usr/bin/env python3
"""Enforce the phase-17 JaCoCo and critical-scenario coverage gates."""

from __future__ import annotations

import json
import os
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
REPORT = ROOT / "migration" / "reports" / "phase-17-coverage.json"
MODULES = {
    "contracts": "mathproofmesh-contracts",
    "core": "mathproofmesh-core",
    "server": "mathproofmesh-server",
    "desktop": "mathproofmesh-desktop",
}
INVARIANT_CLASSES = [
    "io/github/aililuola/mathproofmesh/communication/MessageAdmissionPolicy",
    "io/github/aililuola/mathproofmesh/communication/RouteRegistry",
    "io/github/aililuola/mathproofmesh/computation/ComputationPolicy",
    "io/github/aililuola/mathproofmesh/memory/MemoryPromotionPolicy",
    "io/github/aililuola/mathproofmesh/memory/MemoryInvalidationService",
    "io/github/aililuola/mathproofmesh/memory/TypedMemory",
    "io/github/aililuola/mathproofmesh/orchestration/ContinuationFunctions$CheckpointLedger",
    "io/github/aililuola/mathproofmesh/orchestration/InProcessRunCoordinator",
    "io/github/aililuola/mathproofmesh/proofcontrol/CommonModeAnalyzer",
    "io/github/aililuola/mathproofmesh/proofcontrol/ControlActionDispatcher",
    "io/github/aililuola/mathproofmesh/proofcontrol/DependencyResolver",
    "io/github/aililuola/mathproofmesh/proofcontrol/ExecutableTaskController",
    "io/github/aililuola/mathproofmesh/proofcontrol/FalsificationService",
    "io/github/aililuola/mathproofmesh/proofcontrol/InductionMeasureSelector",
    "io/github/aililuola/mathproofmesh/proofcontrol/MessageUtilityController",
    "io/github/aililuola/mathproofmesh/proofcontrol/ScopeGuard",
    "io/github/aililuola/mathproofmesh/proofcontrol/SemanticQualityGate",
    "io/github/aililuola/mathproofmesh/proofgraph/ProofGraphStore",
    "io/github/aililuola/mathproofmesh/verification/ClaimVerificationLedger",
]
CRITICAL_SCENARIOS = {
    "canonical_json_and_stable_hash": [
        "io.github.aililuola.mathproofmesh.contract.CanonicalJsonParityTest",
    ],
    "ordered_message_admission_and_exactly_once_delivery": [
        "io.github.aililuola.mathproofmesh.communication.MessageAdmissionPolicyParityTest",
        "io.github.aililuola.mathproofmesh.communication.Phase17MessageAdmissionHardeningTest",
    ],
    "typed_memory_promotion_and_invalidation": [
        "io.github.aililuola.mathproofmesh.memory.TypedMemoryParityTest",
        "io.github.aililuola.mathproofmesh.persistence.MemoryProofGraphPostgresIT",
    ],
    "counterexample_propagation_and_graph_reopen": [
        "io.github.aililuola.mathproofmesh.proofgraph.ProofGraphParityTest",
    ],
    "checkpoint_cas_lease_outbox_and_inbox": [
        "io.github.aililuola.mathproofmesh.persistence.PersistencePostgresIT",
    ],
    "proof_control_action_materialization": [
        "io.github.aililuola.mathproofmesh.proofcontrol.ControlActionMaterializationParityTest",
    ],
    "temporal_decisions_replay_and_continue_as_new": [
        "io.github.aililuola.mathproofmesh.workflow.TemporalWorkflowGateTest",
    ],
    "provider_idempotency_and_failure_recovery": [
        "io.github.aililuola.mathproofmesh.agent.StructuredAgentRunnerTest",
    ],
    "rest_sse_cli_resume_contract": [
        "io.github.aililuola.mathproofmesh.api.ApiGateTest",
    ],
}


def counter(element: ET.Element, kind: str) -> tuple[int, int]:
    for item in element.findall("counter"):
        if item.get("type") == kind:
            return int(item.get("covered", "0")), int(item.get("missed", "0"))
    return 0, 0


def filesystem_path(path: Path) -> Path:
    name = str(path)
    if os.name == "nt" and not name.startswith("\\\\?\\"):
        return Path("\\\\?\\" + name)
    return path


def metric(covered: int, missed: int) -> dict[str, int | float]:
    total = covered + missed
    percent = 100.0 if total == 0 else covered * 100.0 / total
    return {
        "covered": covered,
        "missed": missed,
        "total": total,
        "percent": round(percent, 6),
    }


def module_report(module: str) -> ET.Element:
    path = ROOT / "target" / "modules" / module / "site" / "jacoco" / "jacoco.xml"
    if not path.is_file():
        raise FileNotFoundError(f"missing JaCoCo XML: {path.relative_to(ROOT)}")
    return ET.parse(path).getroot()


def raw_metrics(root: ET.Element) -> dict[str, dict[str, int | float]]:
    line_covered, line_missed = counter(root, "LINE")
    branch_covered, branch_missed = counter(root, "BRANCH")
    return {
        "line": metric(line_covered, line_missed),
        "branch": metric(branch_covered, branch_missed),
    }


def generated_accessor_ranges(source: Path) -> set[int]:
    begin_marker = "// BEGIN GENERATED DEFENSIVE ACCESSORS"
    end_marker = "// END GENERATED DEFENSIVE ACCESSORS"
    excluded: set[int] = set()
    begin: int | None = None
    source_text = filesystem_path(source).read_text(encoding="utf-8")
    for number, text in enumerate(source_text.splitlines(), 1):
        if begin_marker in text:
            if begin is not None:
                raise ValueError(f"nested generated accessor marker in {source}")
            begin = number
        if end_marker in text:
            if begin is None:
                raise ValueError(f"unpaired generated accessor marker in {source}")
            excluded.update(range(begin, number + 1))
            begin = None
    if begin is not None:
        raise ValueError(f"unterminated generated accessor marker in {source}")
    return excluded


def contracts_adjusted(root: ET.Element) -> dict[str, object]:
    line_covered = line_missed = branch_covered = branch_missed = 0
    excluded_line_covered = excluded_line_missed = 0
    excluded_branch_covered = excluded_branch_missed = 0
    files_with_exclusions = 0
    source_root = ROOT / "mathproofmesh-contracts" / "src" / "main" / "java"
    for package in root.findall("package"):
        package_name = package.get("name", "")
        for sourcefile in package.findall("sourcefile"):
            source = source_root / package_name / sourcefile.get("name", "")
            exclusions = generated_accessor_ranges(source)
            if exclusions:
                files_with_exclusions += 1
            for line in sourcefile.findall("line"):
                number = int(line.get("nr", "0"))
                missed_instructions = int(line.get("mi", "0"))
                covered_instructions = int(line.get("ci", "0"))
                missed_branches = int(line.get("mb", "0"))
                covered_branches = int(line.get("cb", "0"))
                covered_line = int(covered_instructions > 0)
                missed_line = int(covered_instructions == 0 and missed_instructions > 0)
                if number in exclusions:
                    excluded_line_covered += covered_line
                    excluded_line_missed += missed_line
                    excluded_branch_covered += covered_branches
                    excluded_branch_missed += missed_branches
                    continue
                line_covered += covered_line
                line_missed += missed_line
                branch_covered += covered_branches
                branch_missed += missed_branches
    return {
        "policy": (
            "Exclude only source lines inside explicitly marked generated defensive "
            "accessor blocks. Record constructors, validation, schema, canonical JSON, "
            "and all handwritten behavior remain included."
        ),
        "files_with_explicit_generated_blocks": files_with_exclusions,
        "excluded": {
            "line": metric(excluded_line_covered, excluded_line_missed),
            "branch": metric(excluded_branch_covered, excluded_branch_missed),
        },
        "adjusted": {
            "line": metric(line_covered, line_missed),
            "branch": metric(branch_covered, branch_missed),
        },
    }


def invariant_metrics(root: ET.Element) -> dict[str, object]:
    classes = {
        item.get("name", ""): item
        for package in root.findall("package")
        for item in package.findall("class")
    }
    missing = [name for name in INVARIANT_CLASSES if name not in classes]
    if missing:
        raise ValueError(f"missing audited invariant classes: {missing}")
    line_covered = line_missed = branch_covered = branch_missed = 0
    details = []
    for name in INVARIANT_CLASSES:
        item = classes[name]
        lc, lm = counter(item, "LINE")
        bc, bm = counter(item, "BRANCH")
        line_covered += lc
        line_missed += lm
        branch_covered += bc
        branch_missed += bm
        details.append(
            {
                "class": name,
                "line": metric(lc, lm),
                "branch": metric(bc, bm),
            }
        )
    return {
        "selection_policy": (
            "Exact audited class set spanning admission/routing, computation, memory, "
            "checkpoint and lease coordination, proof control, graph propagation, and "
            "claim verification. Inner classes are included only when named explicitly."
        ),
        "class_count": len(details),
        "aggregate": {
            "line": metric(line_covered, line_missed),
            "branch": metric(branch_covered, branch_missed),
        },
        "classes": details,
    }


def find_suite(fqcn: str) -> Path | None:
    filename = f"TEST-{fqcn}.xml"
    for module in MODULES.values():
        for report_kind in ("surefire-reports", "failsafe-reports"):
            candidate = ROOT / "target" / "modules" / module / report_kind / filename
            if filesystem_path(candidate).is_file():
                return candidate
    return None


def critical_scenario_results() -> dict[str, object]:
    scenarios: dict[str, object] = {}
    for scenario, suites in CRITICAL_SCENARIOS.items():
        suite_results = []
        passed = True
        for suite in suites:
            path = find_suite(suite)
            if path is None:
                suite_results.append({"suite": suite, "result": "MISSING"})
                passed = False
                continue
            root = ET.parse(filesystem_path(path)).getroot()
            tests = int(root.get("tests", "0"))
            failures = int(root.get("failures", "0"))
            errors = int(root.get("errors", "0"))
            skipped = int(root.get("skipped", "0"))
            suite_passed = tests > 0 and failures == 0 and errors == 0 and skipped == 0
            passed &= suite_passed
            suite_results.append(
                {
                    "suite": suite,
                    "tests": tests,
                    "failures": failures,
                    "errors": errors,
                    "skipped": skipped,
                    "result": "PASS" if suite_passed else "FAIL",
                    "report": path.relative_to(ROOT).as_posix(),
                }
            )
        scenarios[scenario] = {
            "result": "PASS" if passed else "FAIL",
            "suites": suite_results,
        }
    passed_count = sum(value["result"] == "PASS" for value in scenarios.values())
    return {
        "policy": "Every selected critical suite must execute with zero failure, error, or skip.",
        "passed": passed_count,
        "total": len(scenarios),
        "percent": round(passed_count * 100.0 / len(scenarios), 6),
        "scenarios": scenarios,
    }


def passes(value: dict[str, int | float], minimum: float) -> bool:
    return float(value["percent"]) + 1e-9 >= minimum


def main() -> int:
    failures: list[str] = []
    try:
        roots = {name: module_report(module) for name, module in MODULES.items()}
        raw = {name: raw_metrics(root) for name, root in roots.items()}
        contracts = contracts_adjusted(roots["contracts"])
        invariants = invariant_metrics(roots["core"])
        critical = critical_scenario_results()

        gates = {
            "contracts_adjusted_line_ge_90": passes(
                contracts["adjusted"]["line"], 90.0
            ),
            "contracts_adjusted_branch_ge_85": passes(
                contracts["adjusted"]["branch"], 85.0
            ),
            "core_overall_line_ge_85": passes(raw["core"]["line"], 85.0),
            "core_overall_branch_ge_75": passes(raw["core"]["branch"], 75.0),
            "core_invariant_line_ge_90": passes(
                invariants["aggregate"]["line"], 90.0
            ),
            "core_invariant_branch_ge_85": passes(
                invariants["aggregate"]["branch"], 85.0
            ),
            "server_testable_business_line_ge_70": passes(
                raw["server"]["line"], 70.0
            ),
            "desktop_testable_business_line_ge_70": passes(
                raw["desktop"]["line"], 70.0
            ),
            "critical_scenarios_100_percent": critical["passed"] == critical["total"],
        }
        failures.extend(name for name, passed in gates.items() if not passed)
        payload = {
            "schema_version": "1.0",
            "phase": "17",
            "generated_at_utc": datetime.now(timezone.utc).isoformat(),
            "result": "PASS" if not failures else "FAIL",
            "raw_module_coverage": raw,
            "contracts_generated_accessor_adjustment": contracts,
            "core_audited_invariant_classes": invariants,
            "server_desktop_policy": (
                "Raw module line coverage is used as the conservative testable-business "
                "coverage measure; no generated-code or framework exclusions are applied."
            ),
            "critical_scenarios": critical,
            "gates": {
                name: "PASS" if passed else "FAIL" for name, passed in gates.items()
            },
            "failures": failures,
        }
    except (FileNotFoundError, OSError, ET.ParseError, ValueError) as error:
        failures.append(str(error))
        payload = {
            "schema_version": "1.0",
            "phase": "17",
            "generated_at_utc": datetime.now(timezone.utc).isoformat(),
            "result": "FAIL",
            "failures": failures,
        }

    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"PHASE 17 COVERAGE: {payload['result']}")
    if failures:
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
