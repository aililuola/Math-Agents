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


def joined(directory: str, *files: str) -> str:
    return "; ".join(f"{directory}/{name}" for name in files)


def main() -> None:
    core = (
        "mathproofmesh-core/src/main/java/"
        "io/github/aililuola/mathproofmesh/verification"
    )
    sources = {
        "src/mathproofmesh/context_policy.py": joined(
            core,
            "ContextPurpose.java",
            "ContextPurposePolicy.java",
            "FactContextSelection.java",
            "ContextSelectionPolicy.java",
        ),
        "src/mathproofmesh/verification/__init__.py": joined(
            core,
            "package-info.java",
            "VerificationServiceRegistry.java",
            "VerificationPipeline.java",
            "ReviewIsolationPolicy.java",
            "BlindReviewPolicy.java",
            "BlindReviewPacketFactory.java",
            "FeedbackDirective.java",
            "ClaimVerificationState.java",
            "ClaimVerificationLedgerEntry.java",
            "ClaimVerificationLedger.java",
            "ScopedClaimPromotionGate.java",
            "LegacyClaimQuarantine.java",
        ),
        "src/mathproofmesh/verification/capability_profile.py": joined(
            core,
            "CapabilityObservationKind.java",
            "CapabilityCell.java",
            "AgentCapabilityProfile.java",
        ),
        "src/mathproofmesh/verification/escalation.py": joined(
            core,
            "ValidationLevel.java",
            "EscalationPlan.java",
            "ValidationStepResult.java",
            "ValidationExecution.java",
            "ValidationEscalationPolicy.java",
            "ValidationEscalator.java",
            "ValidationEscalationExecutor.java",
        ),
        "src/mathproofmesh/verification/formal_microcert.py": joined(
            core,
            "FormalVerifierBackend.java",
            "FormalizationCandidateSelector.java",
            "CompilerFeedbackInterpreter.java",
            "FormalizationCoverage.java",
        ),
        "src/mathproofmesh/verification/mutation.py": joined(
            core,
            "MutationKind.java",
            "ProofMutation.java",
            "MutationResult.java",
            "ProofMutationHarness.java",
        ),
    }
    source_evidence = (
        "AgentCapabilityProfileParityTest; BlindFinalReviewParityTest; "
        "ClaimVerificationMonotonicityParityTest; "
        "ContextSelectionPolicyParityTest; FeedbackAuthorityParityTest; "
        "FormalMicrocertParityTest; "
        "NoLegacyClaimBlindReviewBypassParityTest; "
        "NoLegacyClaimVerifierBypassParityTest; "
        "ScopedClaimPromotionParityTest; ValidationEscalationParityTest; "
        "TypedPromptSerializationParityTest; Maven verify"
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
                    "Deterministic structural-first validation, isolated blind "
                    "review, risk-aware escalation, formal microcertificate "
                    "feedback, mutation calibration, and monotonic claim "
                    "authority pass phase-09 parity and leakage gates"
                ),
            }
            for source, target in sources.items()
        },
    )

    tests = (
        "mathproofmesh-core/src/test/java/"
        "io/github/aililuola/mathproofmesh/verification"
    )
    server_test = (
        "mathproofmesh-server/src/test/java/"
        "io/github/aililuola/mathproofmesh/agent/"
        "TypedPromptSerializationParityTest.java"
    )
    test_targets = {
        "tests/test_agent_capability_profile.py": (
            f"{tests}/AgentCapabilityProfileParityTest.java"
        ),
        "tests/test_blind_final_review.py": (
            f"{tests}/BlindFinalReviewParityTest.java; {server_test}"
        ),
        "tests/test_claim_verification_monotonicity.py": (
            f"{tests}/ClaimVerificationMonotonicityParityTest.java"
        ),
        "tests/test_context_selection_policy.py": (
            f"{tests}/ContextSelectionPolicyParityTest.java"
        ),
        "tests/test_feedback_authority.py": (
            f"{tests}/FeedbackAuthorityParityTest.java"
        ),
        "tests/test_formal_microcert.py": (
            f"{tests}/FormalMicrocertParityTest.java"
        ),
        "tests/test_no_legacy_claim_blind_review_bypass.py": (
            f"{tests}/NoLegacyClaimBlindReviewBypassParityTest.java"
        ),
        "tests/test_no_legacy_claim_verifier_bypass.py": (
            f"{tests}/NoLegacyClaimVerifierBypassParityTest.java"
        ),
        "tests/test_scoped_claim_promotion.py": (
            f"{tests}/ScopedClaimPromotionParityTest.java"
        ),
        "tests/test_validation_escalation.py": (
            f"{tests}/ValidationEscalationParityTest.java"
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
                    "Phase-09 same-semantic JUnit tests, blind-prompt leakage "
                    "guard, mutation calibration, and online/offline Maven verify"
                ),
                "notes": (
                    "All 26 declared Python test functions have same-semantic "
                    "JUnit coverage; prompt construction adds an executable "
                    "zero-leakage guard for both blind review stages"
                ),
            }
            for source, target in test_targets.items()
        },
    )

    auxiliary = {
        "docs/AGENT_CAPABILITY_PROFILE.md": (
            "docs/legacy/python-baseline/AGENT_CAPABILITY_PROFILE.md; "
            "docs/verification.md"
        ),
        "docs/VALIDATION_ESCALATION.md": (
            "docs/legacy/python-baseline/VALIDATION_ESCALATION.md; "
            "docs/verification.md"
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
                    "AuxiliaryFixtureIntegrityTest; phase-09 terminology "
                    "review; online and offline Maven verify"
                ),
                "notes": (
                    "Byte-exact authority document retained and its active "
                    "requirements consolidated into the Java verification guide"
                ),
            }
            for source, target in auxiliary.items()
        },
    )


if __name__ == "__main__":
    main()
