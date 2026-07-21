from __future__ import annotations

from typing import Any

from mathproofmesh.activity import ActivityStream
from mathproofmesh.agents import StructuredAgentRunner
from mathproofmesh.continuation import make_genesis_checkpoint
from mathproofmesh.llm.base import Message
from mathproofmesh.llm.pool import AgentPool
from mathproofmesh.mock_demo import demo_responder
from mathproofmesh.orchestrator import ProofMeshOrchestrator, SolveState
from mathproofmesh.prompts import PromptFactory
from mathproofmesh.schemas import (
    AttemptStatus,
    ClaimCard,
    ClaimStatus,
    ProblemContract,
    ProofAttempt,
    ProofDelta,
    ProofStep,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)
from mathproofmesh.tools import ToolBroker

from v07_helpers import make_broker_runtime, make_strategy, make_v07_config


MARKER = "REJECTED_ROUTE_LOCAL_CLAIM_DO_NOT_SHARE"


async def test_hierarchical_delta_and_attempt_verifiers_never_see_legacy_claim(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs", inspiration_mode="off")
    config.continuation.delta_verifier_replicas = 1
    store, registry, typed_memory, graph, broker = make_broker_runtime(config, tmp_path)
    legacy_memory = typed_memory.lemma_memory
    assert legacy_memory is not None
    legacy_memory.add_many(
        [
            ClaimCard(
                claim_id="rejected-route-local-claim",
                statement=MARKER,
                conclusion=MARKER,
                status=ClaimStatus.VERIFIED,
                verification_confidence=0.99,
            )
        ]
    )
    assert MARKER in {claim.statement for claim in legacy_memory.verified()}
    assert not typed_memory.facts

    prompts_seen: dict[str, list[str]] = {"checkpoint": [], "detailed": []}
    call_counts: dict[str, int] = {"checkpoint": 0, "detailed": 0}

    def responder(
        schema_name: str | None,
        messages: list[Message],
        schema: dict[str, Any] | None,
    ) -> dict[str, Any]:
        payload = demo_responder(schema_name, messages, schema)
        if schema_name != "VerificationReport":
            return payload
        text = "\n".join(message["content"] for message in messages)
        stage = (
            "checkpoint" if "[STAGE:checkpoint_verification]" in text else "detailed"
        )
        prompts_seen[stage].append(text)
        call_counts[stage] += 1
        if call_counts[stage] == 1:
            payload["tool_requests"] = [
                {
                    "kind": "sympy_simplify",
                    "arguments": {"expression": "x-x"},
                    "purpose": "Check the displayed cancellation exactly.",
                }
            ]
        return payload

    pool = AgentPool(
        config,
        mock_responders={agent.id: responder for agent in config.agents},
    )
    activity = ActivityStream(store, persist=False)
    runner = StructuredAgentRunner(config, pool, store, activity=activity)
    prompt_factory = PromptFactory(computation_enabled=config.computation.enabled)
    tools = ToolBroker(config, store, activity)
    orchestrator = ProofMeshOrchestrator(config)
    strategy = make_strategy(0, tag="telescoping")
    problem = ProblemContract(
        exact_statement="Prove that the first n odd integers sum to n squared.",
        normalized_statement="first n odd integers sum to n squared",
    )
    author = pool.select("explorer")
    state = SolveState(
        triage=None,
        strategies=[strategy],
        attempts=[],
        reports=[],
        aggregate_reports={},
        meta_reviews=[],
        checkpoints=[],
        route_registry=registry,
        message_broker=broker,
        proof_graph=graph,
        typed_memory=typed_memory,
    )
    checkpoint = make_genesis_checkpoint(
        problem,
        strategy,
        source_agent_id=author.id,
    )
    delta = ProofDelta(
        problem_hash=problem.integrity_hash,
        path_id=checkpoint.path_id,
        strategy_id=strategy.strategy_id,
        parent_checkpoint_id=checkpoint.checkpoint_id,
        agent_id=author.id,
        round_index=0,
        segment_index=1,
        completed_subgoal="Establish the telescoping identity.",
        new_steps=[
            ProofStep(
                step_id="delta-step",
                statement="The consecutive square differences telescope.",
                justification="Expand and sum.",
            )
        ],
        remaining_subgoals=["Finish the boundary calculation."],
    )
    attempt = ProofAttempt(
        problem_hash=problem.integrity_hash,
        strategy_id=strategy.strategy_id,
        agent_id=author.id,
        round_index=0,
        status=AttemptStatus.COMPLETE,
        final_answer="The sum is n squared.",
        proof_steps=[
            ProofStep(
                step_id="attempt-step",
                statement="Sum consecutive square differences.",
                justification="The middle terms cancel.",
            )
        ],
        path_id=checkpoint.path_id,
    )
    structural = VerificationReport(
        target_id=attempt.attempt_id,
        target_type="attempt",
        agent_id="structural-reviewer",
        stage=VerificationStage.STRUCTURAL,
        verdict=VerificationVerdict.PASS,
        confidence=0.95,
        concise_feedback="The structure is complete.",
    )
    detailed_reviewer = pool.select(
        "detailed_verifier",
        exclude={author.id},
    )

    try:
        await orchestrator._verify_proof_delta(
            problem,
            strategy,
            checkpoint,
            delta,
            author,
            runner,
            prompt_factory,
            legacy_memory,
            tools,
            store,
            state=state,
        )
        await orchestrator._call_detailed_reviewers(
            problem,
            attempt,
            structural,
            [detailed_reviewer],
            runner,
            prompt_factory,
            legacy_memory,
            tools,
            store,
            stage="detailed",
            state=state,
        )
    finally:
        await pool.aclose()

    assert len(prompts_seen["checkpoint"]) >= 2
    assert len(prompts_seen["detailed"]) >= 2
    assert all(
        MARKER not in prompt for prompts in prompts_seen.values() for prompt in prompts
    )
