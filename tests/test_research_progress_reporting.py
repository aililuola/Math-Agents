from __future__ import annotations

from types import SimpleNamespace

from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.orchestrator import ProofMeshOrchestrator, SolveState
from mathproofmesh.report import write_run_report
from mathproofmesh.schemas import (
    ProblemContract,
    ResearchProgressReport,
    RunResult,
    RunStatus,
)
from mathproofmesh.store import ArtifactStore


def test_unverified_report_surfaces_verified_local_claims(tmp_path) -> None:
    store = ArtifactStore(tmp_path / "runs", "verified-local-claim")
    problem = ProblemContract(
        exact_statement="Prove the target statement.",
        normalized_statement="prove the target statement",
    )
    progress = ResearchProgressReport(
        problem_hash=problem.integrity_hash,
        verified_local_claim_ids=["claim-bounded-gap"],
        summary="No complete proof was established.",
    )
    result = RunResult(
        run_id=store.run_id,
        status=RunStatus.UNVERIFIED,
        problem=problem,
        research_progress_report=progress,
        run_directory=str(store.root),
        summary=progress.summary,
    )

    write_run_report(store, result)
    report = (store.root / "reports" / "run_report.md").read_text(encoding="utf-8")

    assert "- 已验证局部结论：1" in report


def test_progress_summary_distinguishes_verified_claims_from_passed_routes(
    tmp_path,
) -> None:
    orchestrator = ProofMeshOrchestrator(build_demo_config(str(tmp_path / "runs")))
    problem = ProblemContract(
        exact_statement="Prove the target statement.",
        normalized_statement="prove the target statement",
    )
    state = SolveState(
        triage=None,
        strategies=[],
        attempts=[],
        reports=[],
        aggregate_reports={},
        meta_reviews=[],
        checkpoints=[],
        typed_memory=SimpleNamespace(
            facts=[SimpleNamespace(message_id="claim-bounded-gap")],
            negatives=[],
        ),
    )

    progress = orchestrator._build_research_progress_report(
        problem,
        state,
        execution_note="Stopped without a complete proof.",
    )

    assert progress.valid_partial_attempt_ids == []
    assert progress.verified_local_claim_ids == ["claim-bounded-gap"]
    assert "1 个已验证局部结论" in progress.summary
