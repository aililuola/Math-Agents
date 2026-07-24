from __future__ import annotations

from typing import Any

from mathproofmesh.activity import ActivityStream
from mathproofmesh.agents import StructuredAgentRunner
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
    ProofStep,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)
from mathproofmesh.topology import SparseTopologyRouter

from v07_helpers import make_broker_runtime, make_strategy, make_v07_config


MARKER = "REJECTED_ROUTE_LOCAL_CLAIM_DO_NOT_SHARE"


async def test_hierarchical_synthesizer_never_sees_legacy_verified_claim(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs", inspiration_mode="off")
    store, registry, typed_memory, graph, broker = make_broker_runtime(config, tmp_path)
    legacy_memory = typed_memory.lemma_memory
    assert legacy_memory is not None
    legacy_memory.add_many(
        [
            ClaimCard(
                claim_id="route-local-marker",
                statement=MARKER,
                conclusion=MARKER,
                status=ClaimStatus.VERIFIED,
                verification_confidence=0.99,
            )
        ]
    )
    strategy = make_strategy(0, tag="telescoping")
    problem = ProblemContract(
        exact_statement="Prove that the first n odd integers sum to n squared.",
        normalized_statement="first n odd integers sum to n squared",
    )
    attempt = ProofAttempt(
        problem_hash=problem.integrity_hash,
        strategy_id=strategy.strategy_id,
        agent_id="agent-explorer-a",
        round_index=0,
        status=AttemptStatus.COMPLETE,
        final_answer="The sum is n squared.",
        proof_steps=[
            ProofStep(
                step_id="s1",
                statement="Write each odd number as a square difference.",
                justification="Expansion verifies the identity.",
            )
        ],
        self_confidence=0.95,
    )
    report = VerificationReport(
        target_id=attempt.attempt_id,
        target_type="attempt",
        agent_id="agent-verifier-a",
        stage=VerificationStage.DETAILED,
        verdict=VerificationVerdict.PASS,
        confidence=0.95,
        concise_feedback="The proof is valid.",
    )
    state = SolveState(
        triage=None,
        strategies=[strategy],
        attempts=[attempt],
        reports=[report],
        aggregate_reports={attempt.attempt_id: report},
        meta_reviews=[],
        checkpoints=[],
        route_registry=registry,
        message_broker=broker,
        proof_graph=graph,
        typed_memory=typed_memory,
    )
    synthesis_prompts: list[str] = []

    def responder(
        schema_name: str | None,
        messages: list[Message],
        schema: dict[str, Any] | None,
    ) -> dict[str, Any]:
        if schema_name == "FinalProof":
            synthesis_prompts.append("\n".join(item["content"] for item in messages))
        return demo_responder(schema_name, messages, schema)

    pool = AgentPool(
        config,
        mock_responders={agent.id: responder for agent in config.agents},
    )
    activity = ActivityStream(store, persist=False)
    runner = StructuredAgentRunner(config, pool, store, activity=activity)
    router = SparseTopologyRouter(config, pool, store)
    try:
        proof, _ = await ProofMeshOrchestrator(config)._synthesize(
            problem,
            state,
            runner,
            PromptFactory(computation_enabled=config.computation.enabled),
            router,
            legacy_memory,
            store,
        )
    finally:
        await pool.aclose()

    assert proof is not None
    assert synthesis_prompts
    assert MARKER in {claim.statement for claim in legacy_memory.verified()}
    assert MARKER not in {fact.statement for fact in typed_memory.facts}
    assert all(MARKER not in prompt for prompt in synthesis_prompts)
