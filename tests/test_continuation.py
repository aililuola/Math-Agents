from __future__ import annotations

import json
import re
from pathlib import Path

import httpx
import pytest

from mathproofmesh.agents import StructuredAgentRunner
from mathproofmesh.config import (
    AgentConfig,
    BudgetConfig,
    ContinuationConfig,
    RuntimeConfig,
    SystemConfig,
)
from mathproofmesh.continuation import (
    attempt_from_checkpoint,
    local_delta_verification,
    make_genesis_checkpoint,
    merge_verified_delta,
)
from mathproofmesh.llm.pool import AgentPool
from mathproofmesh.mock_demo import build_demo_config, demo_responder, demo_responders
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.prompts import PromptBundle, PromptFactory
from mathproofmesh.schemas import (
    CheckpointStatus,
    ClaimCard,
    ProblemContract,
    ProofCheckpoint,
    ProofDelta,
    ProofStep,
    StrategyCard,
    TriageResult,
    VerificationReport,
    VerificationVerdict,
)
from mathproofmesh.store import ArtifactStore


def _problem_and_strategy() -> tuple[ProblemContract, StrategyCard]:
    problem = ProblemContract(
        exact_statement="Prove P(n) for every positive integer n.",
        normalized_statement="Prove P(n) for every positive integer n.",
    )
    strategy = StrategyCard(
        title="Induction",
        core_idea="Prove a base case and an inductive step.",
        independence_basis="Recursive argument",
        expected_lemmas=["Base case", "Inductive step"],
        bottleneck="Preserve the exact induction hypothesis.",
        falsification_test="Check n=1 and the n to n+1 transition.",
        estimated_success=0.8,
    )
    return problem, strategy


def test_continuation_prompt_requires_external_dependency_prefix() -> None:
    problem, strategy = _problem_and_strategy()
    checkpoint = make_genesis_checkpoint(problem, strategy)

    bundle = PromptFactory().continue_proof(
        problem,
        strategy.model_dump(mode="json"),
        checkpoint,
        agent_id="explorer-a",
        round_index=0,
        segment_index=1,
        verified_claims=[],
    )

    assert "external:<exact theorem name>" in bundle.user
    assert "bare theorem title is not a valid dependency ID" in bundle.user


def test_verifier_does_not_require_bibliography_for_standard_theorems() -> None:
    problem, _strategy = _problem_and_strategy()

    bundle = PromptFactory().structural_verify(
        problem,
        {"problem_hash": problem.integrity_hash, "status": "complete"},
        verifier_id="verifier-a",
    )

    assert (
        "Do not demand a bibliographic source for a standard named theorem"
        in bundle.user
    )
    assert "flag any missing hypothesis" in bundle.user
    assert "every hypothesis is explicitly verified" in bundle.system


def test_checkpoint_commit_round_trip_and_hash(tmp_path: Path) -> None:
    problem, strategy = _problem_and_strategy()
    store = ArtifactStore(tmp_path / "runs", "checkpoint-test")
    checkpoint = make_genesis_checkpoint(
        problem, strategy, source_agent_id="explorer-a"
    )
    ref = store.commit_proof_checkpoint(checkpoint)

    assert ref.startswith("artifact://checkpoints/proof/")
    restored = store.load_latest_proof_checkpoint(checkpoint.path_id)
    assert restored is not None
    assert restored.checkpoint_id == checkpoint.checkpoint_id
    assert restored.content_hash == checkpoint.content_hash
    assert restored.status == CheckpointStatus.COMMITTED
    assert store.list_proof_checkpoints(checkpoint.path_id) == [checkpoint]


def test_store_rejects_non_linear_checkpoint_advance(tmp_path: Path) -> None:
    problem, strategy = _problem_and_strategy()
    store = ArtifactStore(tmp_path / "runs", "checkpoint-lineage")
    genesis = make_genesis_checkpoint(problem, strategy)
    store.commit_proof_checkpoint(genesis)
    invalid = ProofCheckpoint(
        parent_checkpoint_id="not-the-latest-checkpoint",
        problem_hash=problem.integrity_hash,
        path_id=genesis.path_id,
        strategy_id=strategy.strategy_id,
        segment_index=1,
        verified_steps=[
            ProofStep(
                step_id="s1",
                statement="A candidate step.",
                justification="A candidate justification.",
            )
        ],
        remaining_subgoals=["Inductive step"],
        current_goal="Inductive step",
    )

    with pytest.raises(ValueError, match="current latest checkpoint"):
        store.commit_proof_checkpoint(invalid)


def test_delta_must_extend_latest_checkpoint() -> None:
    problem, strategy = _problem_and_strategy()
    checkpoint = make_genesis_checkpoint(problem, strategy)
    delta = ProofDelta(
        problem_hash=problem.integrity_hash,
        path_id=checkpoint.path_id,
        strategy_id=strategy.strategy_id,
        parent_checkpoint_id="wrong-parent",
        agent_id="explorer-a",
        round_index=0,
        segment_index=1,
        new_steps=[
            ProofStep(
                step_id="s1",
                statement="The base case holds.",
                justification="Direct substitution.",
            )
        ],
        remaining_subgoals=["Inductive step"],
        current_goal="Inductive step",
    )

    report = local_delta_verification(problem, checkpoint, delta)
    assert report.verdict == VerificationVerdict.FAIL
    assert any(
        "latest committed checkpoint" in issue.description for issue in report.issues
    )


def test_merge_verified_delta_builds_resumable_attempt() -> None:
    problem, strategy = _problem_and_strategy()
    checkpoint = make_genesis_checkpoint(problem, strategy)
    delta = ProofDelta(
        problem_hash=problem.integrity_hash,
        path_id=checkpoint.path_id,
        strategy_id=strategy.strategy_id,
        parent_checkpoint_id=checkpoint.checkpoint_id,
        agent_id="explorer-b",
        round_index=0,
        segment_index=1,
        new_steps=[
            ProofStep(
                step_id="s1",
                statement="The result follows by induction.",
                justification="The base case and transition have both been established.",
            )
        ],
        remaining_subgoals=[],
        current_goal=None,
        candidate_final_answer="P(n) holds for every positive integer n.",
        proof_complete=True,
    )
    report = VerificationReport(
        target_id=delta.delta_id,
        target_type="proof_delta",
        agent_id="verifier-a",
        stage="detailed",
        verdict="pass",
        confidence=0.95,
        concise_feedback="Valid.",
    )
    merged = merge_verified_delta(
        checkpoint, delta, [report], failover_chain=["explorer-a", "explorer-b"]
    )
    attempt = attempt_from_checkpoint(
        merged,
        strategy,
        agent_id="explorer-b",
        round_index=0,
        resumed_from_checkpoint_id=checkpoint.checkpoint_id,
        failover_chain=["explorer-a", "explorer-b"],
    )

    assert merged.parent_checkpoint_id == checkpoint.checkpoint_id
    assert merged.proof_complete is True
    assert attempt.status.value == "complete"
    assert attempt.latest_checkpoint_id == merged.checkpoint_id
    assert attempt.resumed_from_checkpoint_id == checkpoint.checkpoint_id
    assert attempt.failover_chain == ["explorer-a", "explorer-b"]


@pytest.mark.asyncio
async def test_runner_switches_to_backup_key_after_retry_exhaustion(
    tmp_path: Path,
) -> None:
    def broken_responder(schema_name, messages, schema):
        raise httpx.NetworkError("simulated disconnect")

    agents = [
        AgentConfig(id="primary", provider="mock", model="mock", roles=["planner"]),
        AgentConfig(id="backup", provider="mock", model="mock", roles=["planner"]),
    ]
    config = SystemConfig(
        agents=agents,
        budget=BudgetConfig(
            max_total_calls=8, initial_paths=1, max_paths=1, strategies_to_generate=1
        ),
        continuation=ContinuationConfig(enabled=True, max_failover_agents=1),
        runtime=RuntimeConfig(
            run_root=str(tmp_path / "runs"), request_retries=0, parse_retries=0
        ),
    )
    pool = AgentPool(
        config,
        mock_responders={"primary": broken_responder, "backup": demo_responder},
    )
    store = ArtifactStore(config.runtime.run_root, "failover")
    runner = StructuredAgentRunner(config, pool, store)
    schema = TriageResult.model_json_schema()

    def factory(agent):
        return PromptBundle(
            stage="triage",
            system="Return one JSON object.",
            user=f"agent={agent.id}; schema={json.dumps(schema)}",
            response_model=TriageResult,
            temperature=0.0,
        )

    try:
        result, tried = await runner.call_with_failover(
            "planner",
            factory,
            primary_agent=pool.get("primary"),
            max_failover_agents=1,
            allow_failover=True,
        )
    finally:
        await pool.aclose()

    assert result.agent.id == "backup"
    assert tried == ["primary", "backup"]
    assert any(
        "agent_failover_succeeded" in line
        for line in store.events_path.read_text().splitlines()
    )


@pytest.mark.asyncio
async def test_continuation_end_to_end_and_process_resume(tmp_path: Path) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.continuation = ContinuationConfig(
        enabled=True,
        segments_per_explore_call=1,
        max_segments_per_path=4,
        max_failover_agents=1,
        process_resume_enabled=True,
    )
    config.budget.max_total_calls = 80
    responders = demo_responders(config)
    orchestrator = ProofMeshOrchestrator(config, mock_responders=responders)

    first = await orchestrator.solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="continuation-e2e",
    )
    resumed = await ProofMeshOrchestrator(config, mock_responders=responders).resume(
        "continuation-e2e"
    )

    assert first.status.value == "verified"
    assert first.proof_checkpoints
    assert all(attempt.latest_checkpoint_id for attempt in first.attempts)
    assert resumed.resumed is True
    assert resumed.resumed_from_checkpoint_id is not None
    assert resumed.status.value == "verified"
    assert resumed.total_calls == first.total_calls
    assert resumed.total_usage.total_tokens == first.total_usage.total_tokens
    root = Path(resumed.run_directory)
    assert list((root / "checkpoints" / "proof").glob("*/latest.json"))
    assert "run_resumed" in (root / "events.jsonl").read_text(encoding="utf-8")
    runtime_ledger = json.loads(
        (root / "checkpoints" / "runtime_ledger.json").read_text(encoding="utf-8")
    )
    assert runtime_ledger["calls_started"] == resumed.total_calls
    assert runtime_ledger["agent_metrics"]
    activity = [
        json.loads(line)
        for line in (root / "activity.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    sequences = [item["sequence"] for item in activity]
    assert sequences == sorted(set(sequences))


@pytest.mark.asyncio
async def test_oversized_delta_is_rejected_without_validation_crash(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.continuation.enabled = True
    config.continuation.max_new_steps_per_call = 1
    config.continuation.segments_per_explore_call = 1
    config.continuation.verify_each_delta = False
    config.budget.initial_paths = 1
    config.budget.max_paths = 1
    config.budget.strategies_to_generate = 1
    config.budget.candidates_to_verify = 1
    config.budget.max_rounds = 1
    config.budget.max_total_calls = 24

    result = await ProofMeshOrchestrator(
        config,
        mock_responders=demo_responders(config),
    ).solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="oversized-delta-rejection",
    )

    assert "ValidationError" not in result.summary
    root = Path(result.run_directory)
    local_reports = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in (root / "structured").glob("checkpoint_local_verification_*.json")
    ]
    assert local_reports
    assert any(
        report["verdict"] == "fail"
        and any(
            "exceeding the configured limit" in issue["description"]
            for issue in report["issues"]
        )
        for report in local_reports
    )
    assert "proof_checkpoint_rejected" in (root / "events.jsonl").read_text(
        encoding="utf-8"
    )


@pytest.mark.asyncio
async def test_resume_after_budget_interruption_uses_committed_checkpoint(
    tmp_path: Path,
) -> None:
    run_root = str(tmp_path / "runs")
    config = build_demo_config(run_root)
    config.continuation = ContinuationConfig(
        enabled=True,
        segments_per_explore_call=1,
        max_segments_per_path=4,
        max_failover_agents=1,
        process_resume_enabled=True,
    )
    config.budget.initial_paths = 1
    config.budget.max_paths = 1
    config.budget.strategies_to_generate = 1
    config.budget.candidates_to_verify = 1
    config.budget.max_rounds = 1
    config.budget.max_total_calls = 4
    config.runtime.checkpoint_every_stage = False

    def staged_responder(schema_name, messages, schema):
        payload = demo_responder(schema_name, messages, schema)
        if schema_name != "ProofDelta":
            return payload
        text = "\n".join(message["content"] for message in messages)
        match = re.search(r"segment_index=(\d+)", text)
        segment = int(match.group(1)) if match else 1
        if segment == 1:
            payload["completed_subgoal"] = (
                "Establish the consecutive-square difference identity."
            )
            payload["new_steps"] = payload["new_steps"][:1]
            payload["remaining_subgoals"] = ["Telescope the identity from k=1 to n."]
            payload["current_goal"] = "Telescope the identity from k=1 to n."
            payload["candidate_final_answer"] = None
            payload["proof_complete"] = False
        return payload

    responders = {agent.id: staged_responder for agent in config.agents}
    interrupted = await ProofMeshOrchestrator(config, mock_responders=responders).solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="resume-after-budget-interruption",
    )

    assert interrupted.status.value == "budget_exhausted"
    assert interrupted.proof_checkpoints
    interrupted_calls = interrupted.total_calls
    latest_before = max(
        interrupted.proof_checkpoints,
        key=lambda item: item.segment_index,
    )
    assert latest_before.segment_index == 1
    assert latest_before.proof_complete is False

    resumed_config = config.model_copy(deep=True)
    resumed_config.budget.max_total_calls = 40
    resumed = await ProofMeshOrchestrator(
        resumed_config,
        mock_responders={agent.id: staged_responder for agent in resumed_config.agents},
    ).resume("resume-after-budget-interruption")

    assert resumed.resumed is True
    assert resumed.status.value == "verified"
    assert resumed.total_calls > interrupted_calls
    assert resumed.resumed_from_checkpoint_id == latest_before.checkpoint_id
    assert any(
        checkpoint.checkpoint_id == latest_before.checkpoint_id
        for checkpoint in resumed.proof_checkpoints
    )
    assert max(
        resumed.proof_checkpoints,
        key=lambda item: item.segment_index,
    ).proof_complete
    assert any(
        attempt.resumed_from_checkpoint_id == latest_before.checkpoint_id
        for attempt in resumed.attempts
    )


@pytest.mark.asyncio
async def test_orchestrator_failover_commits_backup_agent_checkpoint(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.continuation = ContinuationConfig(
        enabled=True,
        segments_per_explore_call=1,
        max_segments_per_path=2,
        max_failover_agents=1,
        process_resume_enabled=True,
    )
    config.budget.initial_paths = 1
    config.budget.max_paths = 1
    config.budget.strategies_to_generate = 1
    config.budget.candidates_to_verify = 1
    config.budget.max_rounds = 1
    config.budget.max_total_calls = 32
    for agent in config.agents:
        if agent.id == "explorer-a":
            agent.trust_prior = 1.0
        elif agent.id == "explorer-b":
            agent.trust_prior = 0.0

    def disconnected_explorer(schema_name, messages, schema):
        if schema_name == "ProofDelta":
            raise httpx.NetworkError("simulated provider disconnect")
        return demo_responder(schema_name, messages, schema)

    responders = demo_responders(config)
    responders["explorer-a"] = disconnected_explorer
    result = await ProofMeshOrchestrator(config, mock_responders=responders).solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="checkpoint-failover",
    )

    assert result.status.value == "verified"
    latest = max(result.proof_checkpoints, key=lambda item: item.segment_index)
    assert latest.source_agent_id == "explorer-b"
    assert latest.failover_chain == ["explorer-a", "explorer-b"]
    assert any(
        attempt.failover_chain == ["explorer-a", "explorer-b"]
        for attempt in result.attempts
    )
    events = Path(result.run_directory, "events.jsonl").read_text(encoding="utf-8")
    assert "agent_failover_started" in events
    assert "agent_failover_succeeded" in events


@pytest.mark.asyncio
async def test_synthesis_switches_to_backup_after_connect_failure(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.continuation.allow_cross_agent_failover = True
    config.continuation.max_failover_agents = 1
    config.budget.initial_paths = 2
    config.budget.max_paths = 2
    config.budget.strategies_to_generate = 2
    config.budget.max_rounds = 1

    def disconnected_synthesizer(schema_name, messages, schema):
        if schema_name == "FinalProof":
            raise httpx.NetworkError("simulated synthesis disconnect")
        return demo_responder(schema_name, messages, schema)

    responders = demo_responders(config)
    responders["synthesizer"] = disconnected_synthesizer
    result = await ProofMeshOrchestrator(config, mock_responders=responders).solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="synthesis-failover",
    )

    assert result.status.value == "verified"
    events = [
        json.loads(line)
        for line in Path(result.run_directory, "events.jsonl")
        .read_text(encoding="utf-8")
        .splitlines()
        if line.strip()
    ]
    assert any(
        event["event_type"] == "agent_failover_started"
        and event["payload"]["role"] == "synthesizer"
        for event in events
    )
    assert any(
        event["event_type"] == "agent_failover_succeeded"
        and event["payload"]["role"] == "synthesizer"
        for event in events
    )


@pytest.mark.asyncio
async def test_repairable_final_gap_is_revised_and_fully_reaudited(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.budget.initial_paths = 2
    config.budget.max_paths = 2
    config.budget.strategies_to_generate = 2
    config.budget.candidates_to_verify = 2
    config.budget.max_rounds = 1
    config.budget.max_revisions = 1
    config.budget.max_total_calls = 40
    config.scheduler.reserve_revision_cycles = 1
    repair_marker = "explicit_applicability_check"

    def repairable_responder(schema_name, messages, schema):
        text = "\n".join(message["content"] for message in messages)
        payload = demo_responder(schema_name, messages, schema)
        if schema_name == "FinalProof" and "[STAGE:final_revision]" in text:
            payload["proof_steps"].insert(
                0,
                {
                    "step_id": "f0",
                    "statement": repair_marker,
                    "justification": (
                        "The omitted theorem hypothesis is derived explicitly from "
                        "the preceding identities before the theorem is invoked."
                    ),
                    "dependencies": [],
                    "calculations": [],
                    "citations": [],
                    "is_key_step": True,
                    "confidence": 0.99,
                },
            )
        is_final_structural = (
            schema_name == "VerificationReport"
            and "[STAGE:structural_verification]" in text
            and '"answer"' in text
        )
        if is_final_structural and repair_marker not in text:
            payload.update(
                {
                    "verdict": "fail",
                    "first_error_step": "f1",
                    "issues": [
                        {
                            "phase": "theorem_applicability",
                            "severity": "error",
                            "step_id": "f1",
                            "description": (
                                "A required theorem hypothesis is true but not yet "
                                "derived explicitly in the submitted proof."
                            ),
                            "repair_hint": (
                                "Add the short derivation, then run a fresh independent audit."
                            ),
                        }
                    ],
                    "failure_level": "execution",
                    "confidence": 0.99,
                    "concise_feedback": "Repair the explicit applicability check.",
                }
            )
        return payload

    responders = {
        agent.id: repairable_responder for agent in config.agents if agent.enabled
    }
    result = await ProofMeshOrchestrator(config, mock_responders=responders).solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="repairable-final-gap",
    )

    assert result.status.value == "verified"
    assert result.final_verification is not None
    assert result.final_verification.verdict == VerificationVerdict.PASS
    assert result.final_proof is not None
    assert any(
        step.statement == repair_marker for step in result.final_proof.proof_steps
    )
    activity = Path(result.run_directory, "activity.jsonl").read_text(encoding="utf-8")
    assert "final_revision_completed" in activity


@pytest.mark.asyncio
async def test_resume_can_restart_before_first_stage_checkpoint(tmp_path: Path) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.continuation = ContinuationConfig(
        enabled=True,
        process_resume_enabled=True,
    )
    config.budget.max_total_calls = 40
    store = ArtifactStore(config.runtime.run_root, "early-process-crash")
    problem = ProblemContract(
        exact_statement=(
            "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2."
        ),
        normalized_statement=(
            "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2."
        ),
    )
    store.write_json("structured", "problem_contract", problem)
    store.write_json(
        "checkpoints",
        "runtime_ledger",
        {
            "calls_started": 0,
            "stage_calls": {},
            "bucket_calls": {},
            "agent_metrics": [],
        },
    )

    result = await ProofMeshOrchestrator(
        config, mock_responders=demo_responders(config)
    ).resume("early-process-crash")

    assert result.resumed is True
    assert result.status.value == "verified"
    events = Path(result.run_directory, "events.jsonl").read_text(encoding="utf-8")
    assert '"resume_stage": "problem_contract"' in events


@pytest.mark.asyncio
async def test_resume_prefers_persisted_lemma_memory_over_stale_stage_snapshot(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.continuation = ContinuationConfig(
        enabled=True,
        process_resume_enabled=True,
    )
    config.budget.max_total_calls = 40
    store = ArtifactStore(config.runtime.run_root, "lemma-memory-resume")
    problem = ProblemContract(
        exact_statement=(
            "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2."
        ),
        normalized_statement=(
            "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2."
        ),
    )
    persisted_claim = ClaimCard(
        claim_id="claim_persisted_after_stage_snapshot",
        statement="For every k>=1, k^2-(k-1)^2=2k-1.",
        conclusion="Consecutive-square differences are odd integers.",
        status="verified",
        verification_confidence=0.99,
    )
    store.write_json("structured", "problem_contract", problem)
    store.checkpoint(
        "triage",
        {
            "triage": None,
            "strategies": [],
            "attempts": [],
            "reports": [],
            "aggregate_reports": {},
            "meta_reviews": [],
            "claims": [],
            "calls_started": 0,
            "stage_calls": {},
            "bucket_calls": {},
            "agent_metrics": [],
        },
    )
    store.write_json("structured", "lemma_memory", [persisted_claim])

    result = await ProofMeshOrchestrator(
        config, mock_responders=demo_responders(config)
    ).resume("lemma-memory-resume")

    assert result.status.value == "verified"
    assert any(claim.claim_id == persisted_claim.claim_id for claim in result.claims)


def test_delta_rejects_self_dependent_claim() -> None:
    problem, strategy = _problem_and_strategy()
    checkpoint = make_genesis_checkpoint(problem, strategy)
    self_dependent = ClaimCard(
        claim_id="claim_self",
        statement="A circular claim.",
        conclusion="A circular claim.",
        dependencies=["claim_self"],
    )
    delta = ProofDelta(
        problem_hash=problem.integrity_hash,
        path_id=checkpoint.path_id,
        strategy_id=strategy.strategy_id,
        parent_checkpoint_id=checkpoint.checkpoint_id,
        agent_id="explorer-a",
        round_index=0,
        segment_index=1,
        new_steps=[
            ProofStep(
                step_id="s1",
                statement="A placeholder proof step.",
                justification="Used to make the delta structurally non-empty.",
            )
        ],
        new_claims=[self_dependent],
        remaining_subgoals=["Finish the proof."],
        current_goal="Finish the proof.",
    )

    report = local_delta_verification(problem, checkpoint, delta)

    assert report.verdict == VerificationVerdict.FAIL
    assert any("claim_self" in issue.description for issue in report.issues)


def test_delta_allows_claim_supported_by_new_steps() -> None:
    problem, strategy = _problem_and_strategy()
    checkpoint = make_genesis_checkpoint(problem, strategy)
    supporting_step = ProofStep(
        step_id="s1",
        statement="The base case holds.",
        justification="Direct substitution.",
    )
    supported_claim = ClaimCard(
        claim_id="claim_base_case",
        statement="The base case is established.",
        conclusion="P(1) holds.",
        dependencies=[supporting_step.step_id],
    )
    delta = ProofDelta(
        problem_hash=problem.integrity_hash,
        path_id=checkpoint.path_id,
        strategy_id=strategy.strategy_id,
        parent_checkpoint_id=checkpoint.checkpoint_id,
        agent_id="explorer-a",
        round_index=0,
        segment_index=1,
        completed_subgoal="Base case",
        new_steps=[supporting_step],
        new_claims=[supported_claim],
        remaining_subgoals=["Inductive step"],
        current_goal="Inductive step",
    )

    report = local_delta_verification(problem, checkpoint, delta)

    assert report.verdict == VerificationVerdict.PASS
    assert report.issues == []


@pytest.mark.asyncio
async def test_failover_never_selects_excluded_author_agent(tmp_path: Path) -> None:
    def broken_responder(schema_name, messages, schema):
        raise httpx.NetworkError("simulated verifier disconnect")

    agents = [
        AgentConfig(
            id="primary-verifier",
            provider="mock",
            model="mock",
            roles=["detailed_verifier"],
            trust_prior=0.9,
        ),
        AgentConfig(
            id="proof-author",
            provider="mock",
            model="mock",
            roles=["detailed_verifier"],
            trust_prior=1.0,
        ),
        AgentConfig(
            id="backup-verifier",
            provider="mock",
            model="mock",
            roles=["detailed_verifier"],
            trust_prior=0.1,
        ),
    ]
    config = SystemConfig(
        agents=agents,
        budget=BudgetConfig(
            max_total_calls=8,
            initial_paths=1,
            max_paths=1,
            strategies_to_generate=1,
        ),
        continuation=ContinuationConfig(enabled=True, max_failover_agents=2),
        runtime=RuntimeConfig(
            run_root=str(tmp_path / "runs"),
            request_retries=0,
            parse_retries=0,
        ),
    )
    pool = AgentPool(
        config,
        mock_responders={
            "primary-verifier": broken_responder,
            "proof-author": demo_responder,
            "backup-verifier": demo_responder,
        },
    )
    store = ArtifactStore(config.runtime.run_root, "excluded-author")
    runner = StructuredAgentRunner(config, pool, store)
    schema = TriageResult.model_json_schema()

    def factory(agent):
        return PromptBundle(
            stage="triage",
            system="Return one JSON object.",
            user=f"agent={agent.id}; schema={json.dumps(schema)}",
            response_model=TriageResult,
            temperature=0.0,
        )

    try:
        result, tried = await runner.call_with_failover(
            "detailed_verifier",
            factory,
            primary_agent=pool.get("primary-verifier"),
            max_failover_agents=2,
            allow_failover=True,
            exclude_agent_ids={"proof-author"},
        )
    finally:
        await pool.aclose()

    assert result.agent.id == "backup-verifier"
    assert tried == ["primary-verifier", "backup-verifier"]
    assert "proof-author" not in tried


@pytest.mark.asyncio
async def test_auth_failure_skips_same_key_retries_but_fails_over_to_backup(
    tmp_path: Path,
) -> None:
    calls = 0

    def unauthorized_responder(schema_name, messages, schema):
        nonlocal calls
        calls += 1
        request = httpx.Request("POST", "https://api.example.test/chat/completions")
        response = httpx.Response(401, request=request)
        raise httpx.HTTPStatusError(
            "unauthorized",
            request=request,
            response=response,
        )

    agents = [
        AgentConfig(
            id="revoked-key",
            provider="mock",
            model="mock",
            roles=["planner"],
        ),
        AgentConfig(
            id="valid-key",
            provider="mock",
            model="mock",
            roles=["planner"],
        ),
    ]
    config = SystemConfig(
        agents=agents,
        budget=BudgetConfig(
            max_total_calls=8,
            initial_paths=1,
            max_paths=1,
            strategies_to_generate=1,
        ),
        continuation=ContinuationConfig(enabled=True, max_failover_agents=1),
        runtime=RuntimeConfig(
            run_root=str(tmp_path / "runs"),
            request_retries=3,
            parse_retries=0,
        ),
    )
    pool = AgentPool(
        config,
        mock_responders={
            "revoked-key": unauthorized_responder,
            "valid-key": demo_responder,
        },
    )
    runner = StructuredAgentRunner(
        config,
        pool,
        ArtifactStore(config.runtime.run_root, "auth-failover"),
    )
    schema = TriageResult.model_json_schema()

    def factory(agent):
        return PromptBundle(
            stage="triage",
            system="Return one JSON object.",
            user=f"agent={agent.id}; schema={json.dumps(schema)}",
            response_model=TriageResult,
            temperature=0.0,
        )

    try:
        result, tried = await runner.call_with_failover(
            "planner",
            factory,
            primary_agent=pool.get("revoked-key"),
            max_failover_agents=1,
            allow_failover=True,
        )
        next_agent = pool.select("planner")
        revoked_in_cooldown = pool.get("revoked-key").in_cooldown
    finally:
        await pool.aclose()

    assert calls == 1
    assert result.agent.id == "valid-key"
    assert tried == ["revoked-key", "valid-key"]
    assert revoked_in_cooldown is True
    assert next_agent.id == "valid-key"
