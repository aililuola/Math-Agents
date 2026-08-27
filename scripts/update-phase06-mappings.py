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
        "io/github/aililuola/mathproofmesh"
    )
    server = (
        "mathproofmesh-server/src/main/java/"
        "io/github/aililuola/mathproofmesh/persistence/"
        "JdbcMemoryProofGraphRepository.java"
    )
    source_targets = {
        "src/mathproofmesh/memory.py": (
            f"{core}/memory/LemmaMemory.java; "
            f"{core}/memory/TypedMemory.java; "
            f"{core}/memory/MemoryPromotionPolicy.java; "
            f"{core}/memory/MemoryInvalidationService.java; {server}"
        ),
        "src/mathproofmesh/proof_graph/__init__.py": (
            f"{core}/proofgraph/package-info.java; "
            f"{core}/proofgraph/ProofGraphServices.java"
        ),
        "src/mathproofmesh/proof_graph/bridges.py": (
            f"{core}/proofgraph/BridgeBroker.java"
        ),
        "src/mathproofmesh/proof_graph/contradictions.py": (
            f"{core}/proofgraph/ContradictionRecord.java; "
            f"{core}/proofgraph/ContradictionBroker.java"
        ),
        "src/mathproofmesh/proof_graph/matching.py": (
            f"{core}/proofgraph/DuplicateRouteMatch.java; "
            f"{core}/proofgraph/DuplicateRouteDetector.java"
        ),
        "src/mathproofmesh/proof_graph/models.py": (
            f"{core}/proofgraph/package-info.java; "
            "mathproofmesh-contracts/src/main/java/"
            "io/github/aililuola/mathproofmesh/contract/ProofObligation.java; "
            "mathproofmesh-contracts/src/main/java/"
            "io/github/aililuola/mathproofmesh/contract/ProofGraphEdge.java"
        ),
        "src/mathproofmesh/proof_graph/store.py": (
            f"{core}/proofgraph/ProofGraphStore.java; {server}; "
            "mathproofmesh-server/src/main/resources/db/migration/"
            "V3__memory_and_proof_graph_authority.sql"
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
                    "LemmaMemoryParityTest; TypedMemoryParityTest; "
                    "ProofGraphParityTest; ProofGraphServicesTest; "
                    "ProofGraphConcurrencyTest; MemoryProofGraphPostgresIT; "
                    "Maven verify"
                ),
                "notes": (
                    "Promotion, demotion, dependency closure, cycle rejection, "
                    "proof debt, conflict and duplicate detection, transactional "
                    "counterexample propagation, audit versions, and four-query "
                    "PostgreSQL projection are verified"
                ),
            }
            for source, target in source_targets.items()
        },
    )

    tests = (
        "mathproofmesh-core/src/test/java/"
        "io/github/aililuola/mathproofmesh"
    )
    test_targets = {
        "tests/test_memory.py": f"{tests}/memory/LemmaMemoryParityTest.java",
        "tests/test_proof_graph.py": (
            f"{tests}/proofgraph/ProofGraphParityTest.java; "
            f"{tests}/proofgraph/ProofGraphConcurrencyTest.java"
        ),
        "tests/test_typed_memory.py": f"{tests}/memory/TypedMemoryParityTest.java",
    }
    update_rows(
        "migration/test-state.csv",
        "python_test_file",
        {
            source: {
                "status": "ported",
                "java_path": target,
                "verified_by": "Phase-06 parity tests; Maven verify",
                "notes": (
                    "Every declared Python test function is represented by a "
                    "same-semantic JUnit case, with additional policy, "
                    "concurrency, rollback, and PostgreSQL integration coverage"
                ),
            }
            for source, target in test_targets.items()
        },
    )

    auxiliary_targets = {
        "docs/PROOF_OBLIGATION_GRAPH.md": (
            "docs/legacy/python-baseline/PROOF_OBLIGATION_GRAPH.md; "
            "docs/proof-graph.md"
        ),
        "docs/TYPED_MEMORY.md": (
            "docs/legacy/python-baseline/TYPED_MEMORY.md; docs/memory.md"
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
                    "AuxiliaryFixtureIntegrityTest; phase-06 parity tests; "
                    "Maven verify"
                ),
                "notes": (
                    "Byte-exact Python baseline retained and Java authority, "
                    "transaction, projection, and audit semantics documented"
                ),
            }
            for source, target in auxiliary_targets.items()
        },
    )


if __name__ == "__main__":
    main()
