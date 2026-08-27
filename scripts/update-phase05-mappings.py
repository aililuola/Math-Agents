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
        unknown = set(replacement) - set(fieldnames)
        if unknown:
            raise RuntimeError(f"{relative_path}: unknown columns {sorted(unknown)}")
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
        "io/github/aililuola/mathproofmesh"
    )
    tests = (
        "mathproofmesh-core/src/test/java/"
        "io/github/aililuola/mathproofmesh"
    )
    server = (
        "mathproofmesh-server/src/main/java/"
        "io/github/aililuola/mathproofmesh/persistence/JdbcMessageRepository.java"
    )
    source_targets = {
        "src/mathproofmesh/communication/__init__.py": (
            f"{core}/communication/package-info.java"
        ),
        "src/mathproofmesh/communication/broker.py": (
            f"{core}/communication/MessageBroker.java; "
            f"{core}/communication/MessageRepository.java; {server}"
        ),
        "src/mathproofmesh/communication/messages.py": (
            "mathproofmesh-contracts/src/main/java/"
            "io/github/aililuola/mathproofmesh/contract/MessageEnvelope.java; "
            f"{core}/communication/package-info.java"
        ),
        "src/mathproofmesh/communication/policies.py": (
            f"{core}/communication/MessageAdmissionPolicy.java; "
            f"{core}/communication/AdmissionResult.java"
        ),
        "src/mathproofmesh/communication/receipts.py": (
            f"{core}/communication/MessageReceiptService.java; "
            f"{core}/communication/MessageUtilityVerifier.java"
        ),
        "src/mathproofmesh/communication/route_registry.py": (
            f"{core}/communication/RouteRegistry.java"
        ),
        "src/mathproofmesh/topology.py": (
            f"{core}/topology/CommunicationEdge.java; "
            f"{core}/topology/SparseTopologyRouter.java"
        ),
    }
    update_rows(
        "migration/source-state.csv",
        "source_file",
        {
            source: {
                "status": "migrated",
                "java_path": target,
                "verified_by": (
                    "MessageAdmissionPolicyParityTest; MessageDeliveryParityTest; "
                    "RouteRegistryParityTest; SparseTopologyRouterParityTest; "
                    "JdbcMessageRepositoryIT; TopologyBenchmarkTest; Maven verify"
                ),
                "notes": (
                    "Strict ordered admission, semantic deduplication, sparse routing, "
                    "prompt-consumption exactly once, authenticated receipts, verified "
                    "utility, invalidation archive and republication are covered"
                ),
            }
            for source, target in source_targets.items()
        },
    )

    delivery = f"{tests}/communication/MessageDeliveryParityTest.java"
    admission = f"{tests}/communication/MessageAdmissionPolicyParityTest.java"
    routes = f"{tests}/communication/RouteRegistryParityTest.java"
    topology = f"{tests}/topology/SparseTopologyRouterParityTest.java"
    jdbc = (
        "mathproofmesh-server/src/test/java/"
        "io/github/aililuola/mathproofmesh/persistence/JdbcMessageRepositoryIT.java"
    )
    benchmark = (
        "mathproofmesh-compatibility/src/test/java/"
        "io/github/aililuola/mathproofmesh/compatibility/benchmark/"
        "TopologyBenchmarkTest.java"
    )
    test_targets = {
        "tests/test_active_bridge_conflict.py": f"{admission}; {topology}",
        "tests/test_active_broker_delivery.py": f"{delivery}; {jdbc}",
        "tests/test_bridge_broker.py": f"{admission}; {routes}",
        "tests/test_contradiction_broker.py": f"{admission}; {delivery}",
        "tests/test_hierarchical_legacy_dependency_gate.py": admission,
        "tests/test_message_broker.py": f"{admission}; {delivery}; {jdbc}",
        "tests/test_message_liveness_and_priority.py": delivery,
        "tests/test_message_protocol.py": f"{admission}; {delivery}",
        "tests/test_no_legacy_claim_broker_bypass.py": admission,
        "tests/test_route_registry.py": routes,
        "tests/test_sparse_cross_route.py": f"{delivery}; {routes}",
        "tests/test_topology.py": topology,
        "tests/test_topology_benchmark.py": benchmark,
    }
    update_rows(
        "migration/test-state.csv",
        "python_test_file",
        {
            source: {
                "status": "ported",
                "java_path": target,
                "verified_by": "Phase-05 parity tests; Maven verify",
                "notes": (
                    "Every declared Python test function is represented by a same-"
                    "semantic JUnit case or deterministic benchmark assertion"
                ),
            }
            for source, target in test_targets.items()
        },
    )

    auxiliary_targets = {
        "benchmarks/topology/README.md": (
            "migration/baseline/auxiliary/benchmarks/topology/README.md; "
            "docs/benchmarks/topology/README.md"
        ),
        "benchmarks/topology/computation_scope_case.json": (
            "mathproofmesh-compatibility/src/test/resources/benchmarks/topology/"
            "computation_scope_case.json"
        ),
        "benchmarks/topology/contradiction_case.json": (
            "mathproofmesh-compatibility/src/test/resources/benchmarks/topology/"
            "contradiction_case.json"
        ),
        "benchmarks/topology/duplicate_route_case.json": (
            "mathproofmesh-compatibility/src/test/resources/benchmarks/topology/"
            "duplicate_route_case.json"
        ),
        "benchmarks/topology/mock_benchmark_results.json": (
            "mathproofmesh-compatibility/src/test/resources/benchmarks/topology/"
            "mock_benchmark_results.json"
        ),
        "benchmarks/topology/mutation_review_cases.json": (
            "mathproofmesh-compatibility/src/test/resources/benchmarks/topology/"
            "mutation_review_cases.json"
        ),
        "benchmarks/topology/resume_delivery_case.json": (
            "mathproofmesh-compatibility/src/test/resources/benchmarks/topology/"
            "resume_delivery_case.json"
        ),
        "benchmarks/topology/run_mock_benchmark.py": (
            "migration/baseline/auxiliary/benchmarks/topology/"
            "run_mock_benchmark.py; "
            f"{benchmark}; scripts/benchmark-topology.ps1; "
            "scripts/benchmark-topology.sh"
        ),
        "benchmarks/topology/shared_bridge_case.json": (
            "mathproofmesh-compatibility/src/test/resources/benchmarks/topology/"
            "shared_bridge_case.json"
        ),
        "docs/COMMUNICATION_TOPOLOGY.md": (
            "docs/legacy/python-baseline/COMMUNICATION_TOPOLOGY.md; "
            "docs/communication.md"
        ),
        "docs/TYPED_MESSAGE_PROTOCOL.md": (
            "docs/legacy/python-baseline/TYPED_MESSAGE_PROTOCOL.md; "
            "docs/communication.md"
        ),
    }
    update_rows(
        "migration/auxiliary-state.csv",
        "source_file",
        {
            source: {
                "status": "translated_verified",
                "java_path": target,
                "verified_by": (
                    "AuxiliaryFixtureIntegrityTest; TopologyBenchmarkTest; "
                    "Maven verify"
                ),
                "notes": (
                    "Byte-exact baseline retained; Java documentation or strict "
                    "fixture benchmark verified with zero provider calls"
                ),
            }
            for source, target in auxiliary_targets.items()
        },
    )


if __name__ == "__main__":
    main()
