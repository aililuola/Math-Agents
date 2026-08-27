from __future__ import annotations

import csv
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def update_rows(
    relative_path: str,
    key_column: str,
    updates: dict[str, dict[str, str]],
) -> None:
    path = ROOT / relative_path
    with path.open("r", encoding="utf-8", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames is None:
            raise RuntimeError(f"{relative_path} has no header")
        fieldnames = reader.fieldnames
        rows = list(reader)

    matched: set[str] = set()
    for row in rows:
        replacement = updates.get(row[key_column])
        if replacement is None:
            continue
        row.update(replacement)
        matched.add(row[key_column])
    if matched != set(updates):
        raise RuntimeError(
            f"{relative_path}: missing rows {sorted(set(updates) - matched)}"
        )

    with path.open("w", encoding="utf-8", newline="") as destination:
        writer = csv.DictWriter(
            destination, fieldnames=fieldnames, lineterminator="\r\n"
        )
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    core = (
        "mathproofmesh-core/src/main/java/"
        "io/github/aililuola/mathproofmesh/computation"
    )
    sidecar = (
        "mathproofmesh-server/src/main/java/"
        "io/github/aililuola/mathproofmesh/sidecar"
    )
    python = "python-compute-service/mathproofmesh_compute"
    sources = {
        "src/mathproofmesh/computation/__init__.py": (
            f"{core}/package-info.java; {core}/ComputationServiceRegistry.java"
        ),
        "src/mathproofmesh/computation/broker.py": (
            f"{core}/ComputationBroker.java; {core}/ToolBroker.java"
        ),
        "src/mathproofmesh/computation/cache.py": (
            f"{core}/ComputationCache.java; "
            f"{core}/InMemoryComputationCache.java; "
            f"{core}/ComputationLedger.java"
        ),
        "src/mathproofmesh/computation/contracts.py": (
            f"{core}/ContractsFunctions.java"
        ),
        "src/mathproofmesh/computation/handlers/__init__.py": (
            f"{core}/ComputationHandler.java; "
            f"{core}/ComputationHandlerRegistry.java"
        ),
        "src/mathproofmesh/computation/handlers/base.py": (
            f"{core}/HandlerEvidence.java"
        ),
        "src/mathproofmesh/computation/handlers/geometry.py": (
            f"{core}/GeometryFunctions.java"
        ),
        "src/mathproofmesh/computation/handlers/graph.py": (
            f"{core}/GraphFunctions.java"
        ),
        "src/mathproofmesh/computation/handlers/integer_search.py": (
            f"{core}/IntegerSearchFunctions.java; "
            f"{sidecar}/PythonSidecarComputationHandler.java; "
            f"{python}/handlers.py"
        ),
        "src/mathproofmesh/computation/handlers/modular.py": (
            f"{core}/ModularFunctions.java; {python}/handlers.py"
        ),
        "src/mathproofmesh/computation/handlers/number_theory.py": (
            f"{core}/NumberTheoryFunctions.java; {python}/handlers.py"
        ),
        "src/mathproofmesh/computation/handlers/real_inequality.py": (
            f"{sidecar}/PythonSidecarComputationHandler.java; "
            f"{python}/handlers.py"
        ),
        "src/mathproofmesh/computation/handlers/recurrence.py": (
            f"{core}/RecurrenceFunctions.java; {python}/handlers.py"
        ),
        "src/mathproofmesh/computation/handlers/sequence.py": (
            f"{core}/SequenceFunctions.java"
        ),
        "src/mathproofmesh/computation/handlers/symbolic.py": (
            f"{core}/ExactExpression.java; "
            f"{sidecar}/PythonSidecarComputationHandler.java; "
            f"{python}/handlers.py"
        ),
        "src/mathproofmesh/computation/policy.py": (
            f"{core}/ComputationPolicy.java; "
            f"{core}/ComputationContext.java; "
            f"{core}/ComputationLimits.java; "
            f"{core}/ComputationEvidenceGate.java"
        ),
        "src/mathproofmesh/computation/sandbox.py": (
            f"{core}/SandboxSettings.java; "
            f"{core}/SandboxFunctions.java; "
            f"{core}/UnsafeProgramError.java; "
            f"{core}/SandboxExecutionError.java; "
            f"{sidecar}/PythonSandboxAstValidator.java; "
            f"{sidecar}/SandboxedPythonComputationHandler.java; "
            f"{python}/sandbox_ast.py"
        ),
        "src/mathproofmesh/critical_calculations.py": (
            f"{core}/CalculationGateBatch.java; "
            f"{core}/CriticalCalculationGate.java"
        ),
        "src/mathproofmesh/tools.py": f"{core}/LegacyToolFacade.java",
    }
    source_evidence = (
        "ComputationParityTest; ComputationBenchmarkParityTest; "
        "ComputationContractsParityTest; ComputationEvidenceGateParityTest; "
        "CriticalCalculationGateParityTest; "
        "DirectedComputationSmokeParityTest; "
        "ReasoningFirstSequenceToolsParityTest; JsonAndToolsParityTest; "
        "PythonSidecarProtocolTest; PythonSidecarDifferentialTest; "
        "SandboxSecurityIT; Maven verify"
    )
    update_rows(
        "migration/source-state.csv",
        "source_file",
        {
            source: {
                "status": "migrated",
                "java_path": target,
                "verified_by": source_evidence,
                "notes": (
                    "Deterministic Java handlers, exact certificates, bounded "
                    "contracts, canonical run-isolated cache, evidence gates, "
                    "and the allowlisted versioned stdio SymPy/Z3 sidecar pass "
                    "parity, differential, budget, protocol, and sandbox gates"
                ),
            }
            for source, target in sources.items()
        },
    )

    core_tests = (
        "mathproofmesh-core/src/test/java/"
        "io/github/aililuola/mathproofmesh/computation"
    )
    server_tests = (
        "mathproofmesh-server/src/test/java/"
        "io/github/aililuola/mathproofmesh/sidecar"
    )
    test_targets = {
        "tests/test_computation.py": f"{core_tests}/ComputationParityTest.java",
        "tests/test_computation_benchmark.py": (
            f"{core_tests}/ComputationBenchmarkParityTest.java"
        ),
        "tests/test_computation_contracts.py": (
            f"{core_tests}/ComputationContractsParityTest.java"
        ),
        "tests/test_computation_evidence_gate.py": (
            f"{core_tests}/ComputationEvidenceGateParityTest.java"
        ),
        "tests/test_critical_calculation_gate.py": (
            f"{core_tests}/CriticalCalculationGateParityTest.java"
        ),
        "tests/test_directed_computation_smoke.py": (
            f"{core_tests}/DirectedComputationSmokeParityTest.java"
        ),
        "tests/test_json_and_tools.py": (
            f"{server_tests}/JsonAndToolsParityTest.java"
        ),
        "tests/test_reasoning_first_sequence_tools.py": (
            f"{core_tests}/ReasoningFirstSequenceToolsParityTest.java"
        ),
    }
    update_rows(
        "migration/test-state.csv",
        "python_test_file",
        {
            source: {
                "status": "ported",
                "java_path": target,
                "verified_by": (
                    "Phase-08 parity, sidecar differential, and sandbox tests; "
                    "online and offline Maven verify"
                ),
                "notes": (
                    "All 56 declared Python test functions have same-semantic "
                    "JUnit coverage; sidecar protocol and hostile-input tests "
                    "add bounded process, schema, AST, and transport coverage"
                ),
            }
            for source, target in test_targets.items()
        },
    )

    auxiliary = {
        "benchmarks/reasoning_first_computation.py": {
            "status": "reimplemented_verified",
            "java_path": (
                "migration/baseline/auxiliary/benchmarks/"
                "reasoning_first_computation.py; "
                "mathproofmesh-compatibility/src/test/java/"
                "io/github/aililuola/mathproofmesh/compatibility/benchmark/"
                "ReasoningFirstComputationBenchmarkTest.java"
            ),
            "notes": (
                "Byte-exact reference retained; deterministic Java benchmark "
                "preserves decision/evidence semantics with zero provider calls"
            ),
        },
        "docs/COMPUTATION_POLICY.md": {
            "status": "translated_verified",
            "java_path": (
                "docs/legacy/python-baseline/COMPUTATION_POLICY.md; "
                "docs/computation.md"
            ),
            "notes": (
                "Byte-exact legacy policy retained and active constraints, "
                "evidence semantics, sidecar protocol, and operations translated"
            ),
        },
        "scripts/directed_computation_smoke.py": {
            "status": "reimplemented_verified",
            "java_path": (
                "migration/baseline/scripts/directed_computation_smoke.py; "
                f"{core_tests}/DirectedComputationSmokeParityTest.java; "
                "scripts/directed-computation-smoke.ps1; "
                "scripts/directed-computation-smoke.sh"
            ),
            "notes": (
                "Byte-exact source retained; Java smoke reproduces the exact "
                "bounded sequence and evidence-linked non-proof result"
            ),
        },
        "scripts/verify_sandbox.py": {
            "status": "reimplemented_verified",
            "java_path": (
                "migration/baseline/scripts/verify_sandbox.py; "
                f"{server_tests}/SandboxSecurityIT.java"
            ),
            "notes": (
                "Byte-exact source retained; malicious source, schema, timeout, "
                "resource, network, path, and digest controls are exercised"
            ),
        },
    }
    update_rows(
        "migration/auxiliary-state.csv",
        "source_file",
        {
            source: {
                **values,
                "verified_by": (
                    "Phase-08 fixture integrity, benchmark, smoke, sandbox, "
                    "and full Maven verification"
                ),
            }
            for source, values in auxiliary.items()
        },
    )


if __name__ == "__main__":
    main()
