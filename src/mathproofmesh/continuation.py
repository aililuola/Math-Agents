from __future__ import annotations

from collections.abc import Iterable

from .schemas import (
    AttemptStatus,
    CheckpointStatus,
    ClaimCard,
    ClaimStatus,
    FailureLevel,
    ProblemContract,
    ProofAttempt,
    ProofCheckpoint,
    ProofDelta,
    Severity,
    StrategyCard,
    UsageRecord,
    VerificationIssue,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
    new_id,
)


def make_genesis_checkpoint(
    problem: ProblemContract,
    strategy: StrategyCard,
    *,
    source_agent_id: str | None = None,
) -> ProofCheckpoint:
    """Create the first immutable resume point for one strategy path."""
    path_id = f"path_{strategy.strategy_id}"
    subgoals = list(strategy.expected_lemmas)
    if not subgoals:
        subgoals = [strategy.core_idea]
    return ProofCheckpoint(
        problem_hash=problem.integrity_hash,
        path_id=path_id,
        strategy_id=strategy.strategy_id,
        source_agent_id=source_agent_id,
        segment_index=0,
        verified_steps=[],
        verified_claim_ids=[],
        active_assumptions=[],
        remaining_subgoals=subgoals,
        current_goal=subgoals[0] if subgoals else strategy.core_idea,
        known_risks=[strategy.bottleneck, strategy.falsification_test],
        proof_complete=False,
        status=CheckpointStatus.COMMITTED,
    )


def local_delta_verification(
    problem: ProblemContract,
    checkpoint: ProofCheckpoint,
    delta: ProofDelta,
    *,
    verifier_id: str = "local-integrity-guard",
) -> VerificationReport:
    """Deterministic structural checks before any model may approve a checkpoint."""
    issues: list[VerificationIssue] = []

    def issue(
        description: str, *, step_id: str | None = None, critical: bool = False
    ) -> None:
        issues.append(
            VerificationIssue(
                phase="checkpoint_integrity",
                severity=Severity.CRITICAL if critical else Severity.ERROR,
                step_id=step_id,
                description=description,
                repair_hint="Regenerate the delta from the latest committed checkpoint.",
            )
        )

    if delta.problem_hash != problem.integrity_hash:
        issue(
            "Proof delta changed or omitted the immutable problem hash.", critical=True
        )
    if checkpoint.problem_hash != problem.integrity_hash:
        issue(
            "Parent checkpoint does not belong to the immutable problem.", critical=True
        )
    if delta.parent_checkpoint_id != checkpoint.checkpoint_id:
        issue(
            "Proof delta does not extend the latest committed checkpoint.",
            critical=True,
        )
    if (
        delta.path_id != checkpoint.path_id
        or delta.strategy_id != checkpoint.strategy_id
    ):
        issue("Proof delta changed its path or strategy identity.", critical=True)
    if delta.segment_index != checkpoint.segment_index + 1:
        issue("Proof delta segment index is not the next checkpoint segment.")

    committed_step_ids = {step.step_id for step in checkpoint.verified_steps}
    committed_claim_ids = set(checkpoint.verified_claim_ids)
    new_step_ids: set[str] = set()
    for step in delta.new_steps:
        if step.step_id in committed_step_ids or step.step_id in new_step_ids:
            issue(f"Duplicate proof step ID {step.step_id!r}.", step_id=step.step_id)
            continue
        allowed = committed_step_ids | committed_claim_ids | new_step_ids
        for dependency in step.dependencies:
            if dependency in allowed or dependency.startswith("external:"):
                continue
            issue(
                f"Step {step.step_id!r} uses uncommitted dependency {dependency!r}.",
                step_id=step.step_id,
            )
        new_step_ids.add(step.step_id)

    new_claim_ids: set[str] = set()
    for claim in delta.new_claims:
        if claim.claim_id in committed_claim_ids or claim.claim_id in new_claim_ids:
            issue(f"Duplicate claim ID {claim.claim_id!r}.")
        # Claims may summarize proof steps from the committed checkpoint or this
        # delta, and may depend on earlier claims in topological order. Adding the
        # current ID after validation still rejects self-dependencies and cycles.
        allowed_claim_dependencies = (
            committed_step_ids | new_step_ids | committed_claim_ids | new_claim_ids
        )
        for dependency in claim.dependencies:
            if dependency in allowed_claim_dependencies or dependency.startswith(
                "external:"
            ):
                continue
            issue(
                f"Claim {claim.claim_id!r} uses unverified dependency {dependency!r}."
            )
        new_claim_ids.add(claim.claim_id)

    if delta.proof_complete and (
        not delta.candidate_final_answer or delta.remaining_subgoals
    ):
        issue("A complete proof must contain a final answer and no remaining subgoals.")
    if not delta.ready_for_verification:
        issue("The author marked the delta as not ready for verification.")

    verdict = VerificationVerdict.FAIL if issues else VerificationVerdict.PASS
    return VerificationReport(
        target_id=delta.delta_id,
        target_type="proof_delta",
        agent_id=verifier_id,
        stage=VerificationStage.DETAILED,
        problem_integrity_ok=not any(
            item.severity == Severity.CRITICAL for item in issues
        ),
        verdict=verdict,
        first_error_step=next((item.step_id for item in issues if item.step_id), None),
        issues=issues,
        checked_dependencies=sorted(
            committed_step_ids | committed_claim_ids | new_step_ids
        ),
        failure_level=FailureLevel.EXECUTION if issues else FailureLevel.NONE,
        confidence=1.0,
        concise_feedback=(
            "Local checkpoint integrity checks passed."
            if not issues
            else "The proposed delta cannot be appended safely; repair the first listed integrity issue."
        ),
    )


def merge_verified_delta(
    checkpoint: ProofCheckpoint,
    delta: ProofDelta,
    reports: Iterable[VerificationReport],
    *,
    failover_chain: list[str] | None = None,
) -> ProofCheckpoint:
    reports = list(reports)
    if not reports or any(
        report.verdict != VerificationVerdict.PASS for report in reports
    ):
        raise ValueError("only independently passed proof deltas may be committed")

    claim_ids = list(checkpoint.verified_claim_ids)
    for claim in delta.new_claims:
        if claim.claim_id not in claim_ids:
            claim_ids.append(claim.claim_id)

    assumptions = delta.active_assumptions or list(checkpoint.active_assumptions)
    risks = _deduplicate(
        [*checkpoint.known_risks, *delta.known_risks, *delta.detected_conflicts]
    )
    return ProofCheckpoint(
        parent_checkpoint_id=checkpoint.checkpoint_id,
        problem_hash=checkpoint.problem_hash,
        path_id=checkpoint.path_id,
        strategy_id=checkpoint.strategy_id,
        source_agent_id=delta.agent_id,
        source_delta_id=delta.delta_id,
        segment_index=delta.segment_index,
        verified_steps=[*checkpoint.verified_steps, *delta.new_steps],
        verified_claim_ids=claim_ids,
        active_assumptions=assumptions,
        remaining_subgoals=list(delta.remaining_subgoals),
        current_goal=delta.current_goal,
        known_risks=risks,
        final_answer=delta.candidate_final_answer
        if delta.proof_complete
        else checkpoint.final_answer,
        proof_complete=delta.proof_complete,
        status=CheckpointStatus.COMMITTED,
        verification_report_ids=[report.report_id for report in reports],
        failover_chain=list(failover_chain or []),
    )


def normalize_delta_claims(
    delta: ProofDelta,
    *,
    attempt_id: str,
    raw_ref: str | None,
) -> list[ClaimCard]:
    claims: list[ClaimCard] = []
    for claim in delta.new_claims:
        claim.status = ClaimStatus.VERIFIED
        claim.source_attempt_id = attempt_id
        claim.source_agent_id = delta.agent_id
        claim.verification_confidence = 1.0
        if raw_ref and not any(
            ref.artifact_ref == raw_ref for ref in claim.evidence_refs
        ):
            from .schemas import EvidenceRef

            claim.evidence_refs.append(
                EvidenceRef(
                    artifact_ref=raw_ref,
                    summary="Raw proof-continuation response containing the verified delta claim.",
                )
            )
        claims.append(claim)
    return claims


def attempt_from_checkpoint(
    checkpoint: ProofCheckpoint,
    strategy: StrategyCard,
    *,
    agent_id: str,
    round_index: int,
    previous_attempt: ProofAttempt | None = None,
    attempt_id: str | None = None,
    proposed_lemmas: list[ClaimCard] | None = None,
    raw_artifact_ref: str | None = None,
    usage: UsageRecord | None = None,
    resumed_from_checkpoint_id: str | None = None,
    failover_chain: list[str] | None = None,
) -> ProofAttempt:
    checkpoint_ids = list(previous_attempt.checkpoint_ids) if previous_attempt else []
    if checkpoint.checkpoint_id not in checkpoint_ids:
        checkpoint_ids.append(checkpoint.checkpoint_id)
    status = (
        AttemptStatus.COMPLETE if checkpoint.proof_complete else AttemptStatus.PARTIAL
    )
    unresolved = _deduplicate(
        [
            *checkpoint.remaining_subgoals,
            *([] if checkpoint.proof_complete else checkpoint.known_risks),
        ]
    )
    return ProofAttempt(
        attempt_id=attempt_id or new_id("attempt"),
        problem_hash=checkpoint.problem_hash,
        strategy_id=strategy.strategy_id,
        agent_id=agent_id,
        round_index=round_index,
        status=status,
        final_answer=checkpoint.final_answer,
        proof_steps=list(checkpoint.verified_steps),
        proposed_lemmas=list(proposed_lemmas or []),
        dead_ends=list(previous_attempt.dead_ends) if previous_attempt else [],
        unresolved_gaps=[] if checkpoint.proof_complete else unresolved,
        falsification_checks=[strategy.falsification_test],
        self_confidence=1.0 if checkpoint.proof_complete else 0.7,
        path_id=checkpoint.path_id,
        latest_checkpoint_id=checkpoint.checkpoint_id,
        checkpoint_ids=checkpoint_ids,
        resumed_from_checkpoint_id=resumed_from_checkpoint_id,
        segment_count=checkpoint.segment_index,
        failover_chain=_deduplicate(
            [
                *(previous_attempt.failover_chain if previous_attempt else []),
                *(failover_chain or []),
            ]
        ),
        raw_artifact_ref=raw_artifact_ref,
        usage=usage or UsageRecord(),
    )


def _deduplicate(values: Iterable[str]) -> list[str]:
    output: list[str] = []
    seen: set[str] = set()
    for value in values:
        value = value.strip()
        if not value or value in seen:
            continue
        seen.add(value)
        output.append(value)
    return output
