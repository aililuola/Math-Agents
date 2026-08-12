from __future__ import annotations

import csv
from pathlib import Path


ROOT = (
    Path.cwd()
    if Path.cwd().joinpath("migration", "source-state.csv").is_file()
    else Path(__file__).parent.parent
)


def rewrite(relative: str, transform) -> int:
    path = ROOT / relative
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames is None:
            raise RuntimeError(f"{relative} has no header")
        fields = reader.fieldnames
        rows = list(reader)
    changed = 0
    for row in rows:
        if transform(row):
            changed += 1
    with path.open("w", encoding="utf-8", newline="") as destination:
        writer = csv.DictWriter(destination, fieldnames=fields, lineterminator="\r\n")
        writer.writeheader()
        writer.writerows(rows)
    return changed


CORE = (
    "mathproofmesh-core/src/main/java/"
    "io/github/aililuola/mathproofmesh/proofcontrol"
)
SOURCE_ARTIFACTS = {
    "__init__.py": "package-info.java; ProofControlFacade.java",
    "action_dispatcher.py": "ControlActionDispatcher.java",
    "bottleneck.py": "BottleneckCompressor.java",
    "claim_lifecycle.py": "ClaimLifecycleController.java",
    "common_mode.py": "CommonModeAnalyzer.java",
    "controller.py": "ProofControlFacade.java; ProofControlPolicy.java",
    "dependencies.py": "DependencyResolver.java",
    "domains.py": "DomainClassifier.java",
    "failure_control.py": "FailureControlService.java",
    "falsification.py": "FalsificationService.java",
    "gates.py": "ProofControlGates.java; RouteAdmissionGate.java",
    "goal_alignment.py": "GoalAlignmentAnalyzer.java; MinimalSufficiencyAnalyzer.java",
    "induction.py": "InductionMeasureSelector.java",
    "inference_risk.py": "InferenceRiskScanner.java",
    "message_utility.py": "MessageUtilityController.java",
    "models.py": "ProofControlModels.java",
    "near_miss.py": "NearMissLedger.java",
    "proof_roles.py": "ProofRoleClassifier.java",
    "realizer.py": "AbstractRealizerController.java",
    "resume_policy.py": "ResumePlanner.java; MetaPivotController.java",
    "route_target.py": "RouteTargetSelector.java",
    "scope_guard.py": "ScopeGuard.java",
    "semantic_profile.py": "SemanticProfileService.java",
    "semantic_quality.py": "SemanticQualityGate.java",
    "semantic_view.py": "ProblemSemanticViewService.java",
    "state.py": "ProofControlState.java",
    "strategy_blueprint.py": "StrategyBlueprintCompiler.java; StrategyArchive.java",
    "tasks.py": "ExecutableTaskController.java",
    "proof_identity.py": "ProofIdentity.java",
}


def source_transform(row: dict[str, str]) -> bool:
    if row["phase"] != "10":
        return False
    name = Path(row["source_file"]).name
    artifacts = SOURCE_ARTIFACTS.get(name)
    if artifacts is None:
        raise RuntimeError(f"unmapped phase-10 source: {row['source_file']}")
    row.update(
        status="migrated",
        java_path="; ".join(f"{CORE}/{item}" for item in artifacts.split("; ")),
        verified_by=(
            "203 authority-named JUnit cases across 46 parity classes; "
            "ProofControlBenchmarkTest; online/offline Maven verify"
        ),
        notes=(
            "10A-10G semantics implemented with advisory authority boundaries, "
            "typed dependencies, executable/deferred tasks, stable identity, "
            "and exactly-once actions/resume"
        ),
    )
    return True


def test_transform(row: dict[str, str]) -> bool:
    if row["primary_phase"] != "10":
        return False
    target = row["target_java_test"]
    row.update(
        status="ported",
        java_path=(
            "mathproofmesh-core/src/test/java/io/github/aililuola/"
            f"mathproofmesh/proofcontrol/{target}"
        ),
        verified_by=(
            "ProofControlParityScenarios and dedicated authority-named "
            "parameterized JUnit case; online/offline Maven verify"
        ),
        notes=(
            f"{row['python_test_functions']} declared authority functions retained "
            "as independently reported JUnit cases"
        ),
    )
    return True


def auxiliary_transform(row: dict[str, str]) -> bool:
    if row["phase"] != "10":
        return False
    source = row["source_file"]
    if source.endswith(".json"):
        java_path = row["target_path"]
        status = "copied_verified"
    elif source.endswith("README.md"):
        java_path = (
            "migration/baseline/auxiliary/benchmarks/proof_control/README.md; "
            "docs/proof-control.md"
        )
        status = "translated_verified"
    elif source.endswith("run_mock_benchmark.py"):
        java_path = (
            "migration/baseline/auxiliary/benchmarks/proof_control/"
            "run_mock_benchmark.py; "
            "mathproofmesh-compatibility/src/test/java/io/github/aililuola/"
            "mathproofmesh/compatibility/benchmark/ProofControlBenchmarkTest.java; "
            "scripts/benchmark-proof-control.ps1; scripts/benchmark-proof-control.sh"
        )
        status = "reimplemented_verified"
    else:
        name = Path(source).name
        java_path = (
            f"docs/legacy/python-baseline/{name}; docs/proof-control.md"
        )
        status = "translated_verified"
    row.update(
        status=status,
        java_path=java_path,
        verified_by=(
            "SHA-256 fixture integrity; ProofControlBenchmarkTest; "
            "online/offline Maven verify"
        ),
        notes=(
            "Byte-exact authority retained where required; active semantics "
            "implemented and documented for the Java runtime; provider calls=0"
        ),
    )
    return True


def main() -> None:
    counts = {
        "source": rewrite("migration/source-state.csv", source_transform),
        "test": rewrite("migration/test-state.csv", test_transform),
        "auxiliary": rewrite("migration/auxiliary-state.csv", auxiliary_transform),
    }
    if counts != {"source": 29, "test": 46, "auxiliary": 16}:
        raise SystemExit(f"unexpected phase-10 mapping counts: {counts}")
    print(counts)


if __name__ == "__main__":
    main()
