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
    ClaimCard,
    ClaimStatus,
    FailureLevel,
    FinalProof,
    ProblemContract,
    ProofStep,
    Severity,
    VerificationIssue,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)

from v07_helpers import make_broker_runtime, make_v07_config


MARKER = "REJECTED_ROUTE_LOCAL_CLAIM_DO_NOT_SHARE"


async def test_hierarchical_final_revision_never_sees_legacy_claim(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs", inspiration_mode="off")
    store, registry, typed_memory, graph, broker = make_broker_runtime(config, tmp_path)
    legacy_memory = typed_memory.lemma_memory
    assert legacy_memory is not None
    legacy_memory.add_many(
        [
            ClaimCard(
                claim_id="legacy-revision-marker",
                statement=MARKER,
                conclusion=MARKER,
                status=ClaimStatus.VERIFIED,
                verification_confidence=0.99,
            )
        ]
    )
    problem = ProblemContract(
        exact_statement="Prove the target identity.",
        normalized_statement="prove the target identity",
    )
    proof = FinalProof(
        problem_hash=problem.integrity_hash,
        answer="The target identity holds.",
        proof_steps=[
            ProofStep(
                step_id="f1",
                statement="Apply the local identity.",
                justification="The next revision must make its scope explicit.",
            )
        ],
        confidence=0.7,
    )
    verification = VerificationReport(
        target_id="final_proof",
        target_type="final_proof",
        agent_id="independent-final-reviewer",
        stage=VerificationStage.FINAL,
        verdict=VerificationVerdict.FAIL,
        issues=[
            VerificationIssue(
                phase="scope",
                severity=Severity.ERROR,
                step_id="f1",
                description="The local identity's scope was not stated.",
                repair_hint="State and verify the exact scope.",
            )
        ],
        failure_level=FailureLevel.EXECUTION,
        confidence=0.95,
        concise_feedback="Repair the first scope omission.",
    )
    state = SolveState(
        triage=None,
        strategies=[],
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
    revision_prompts: list[str] = []

    def responder(
        schema_name: str | None,
        messages: list[Message],
        schema: dict[str, Any] | None,
    ) -> dict[str, Any]:
        text = "\n".join(item["content"] for item in messages)
        if schema_name == "FinalProof" and "[STAGE:final_revision]" in text:
            revision_prompts.append(text)
        return demo_responder(schema_name, messages, schema)

    pool = AgentPool(
        config,
        mock_responders={agent.id: responder for agent in config.agents},
    )
    activity = ActivityStream(store, persist=False)
    runner = StructuredAgentRunner(config, pool, store, activity=activity)
    synthesizer = pool.select("synthesizer")
    try:
        revised = await ProofMeshOrchestrator(config)._revise_final(
            problem,
            proof,
            verification,
            synthesizer,
            runner,
            PromptFactory(computation_enabled=config.computation.enabled),
            legacy_memory,
            store,
            1,
            state=state,
        )
    finally:
        await pool.aclose()

    assert revised is not None
    assert len(revision_prompts) >= 1
    assert all(MARKER not in prompt for prompt in revision_prompts)
