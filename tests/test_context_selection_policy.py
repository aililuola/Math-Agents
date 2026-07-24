from __future__ import annotations

import hashlib
import json

from mathproofmesh.context_policy import (
    ContextPurpose,
    purpose_context_limits,
    select_typed_fact_context,
    select_typed_fact_context_with_diagnostics,
)
from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    FailureLevel,
    FinalProof,
    MemoryTier,
    MessageType,
    ProblemContract,
    ProofStep,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)
from mathproofmesh.synthesis_phase import (
    apply_blind_context_integrity_guard,
    build_blind_review_packet,
)

from v07_helpers import make_broker_runtime, make_fact, make_message, make_v07_config


def _publish_fact(broker, fact) -> None:
    decision = broker.publish(
        fact,
        referee_agent_id="independent-referee",
        current_round=1,
    )
    assert decision.accepted


def _proof(
    problem: ProblemContract, *, dependencies: list[str] | None = None
) -> FinalProof:
    return FinalProof(
        problem_hash=problem.integrity_hash,
        answer="The claimed parity identity holds.",
        proof_steps=[
            ProofStep(
                step_id="final-step",
                statement="Apply the parity identity.",
                justification="Use the cited global fact.",
                dependencies=dependencies or [],
            )
        ],
        dependencies=dependencies or [],
        confidence=0.9,
    )


def test_explicit_fact_ids_and_hashes_precede_lexical_similarity(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    _, _, _, _, broker = make_broker_runtime(config, tmp_path)
    explicit = make_fact(
        message_id="explicit-fact",
        statement="an orthogonal algebraic certificate",
    )
    dependency = make_fact(
        message_id="dependency-fact",
        statement="a compact prerequisite",
    )
    root = make_fact(
        message_id="root-fact",
        statement="a second orthogonal certificate",
        dependencies=[dependency.message_id],
    )
    lexical = make_fact(
        message_id="lexical-fact",
        statement="telescoping sum telescoping sum target keyword",
    )
    for fact in [explicit, dependency, root, lexical]:
        _publish_fact(broker, fact)

    selection = select_typed_fact_context_with_diagnostics(
        broker.admitted_facts(),
        broker=broker,
        query="telescoping sum target keyword",
        max_chars=50000,
        max_items=3,
        purpose=ContextPurpose.FINAL_VERIFICATION,
        required_refs=[explicit.message_id, root.content_hash],
    )

    assert selection.required_context_complete
    assert selection.selected_message_ids == [
        explicit.message_id,
        dependency.message_id,
        root.message_id,
    ]
    assert lexical.message_id not in selection.selected_message_ids


def test_context_purpose_changes_fields_and_blind_artifacts_are_path_free(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs")
    synthesis_limit = purpose_context_limits(
        config,
        purpose=ContextPurpose.SYNTHESIS,
        requested_max_chars=config.topology.max_context_chars,
        requested_max_items=12,
    )
    blind_limit = purpose_context_limits(
        config,
        purpose=ContextPurpose.BLIND_REVIEW,
        requested_max_chars=config.topology.max_context_chars,
        requested_max_items=12,
    )
    assert synthesis_limit == (3600, 12)
    assert blind_limit == (5400, 12)

    store, _, typed_memory, _, broker = make_broker_runtime(config, tmp_path)
    artifact_ref = store.write_text(
        "raw",
        "private-agent_private-route_certificate",
        "certificate body",
    )
    fact = make_fact(
        message_id="artifact-fact",
        statement="the exact certificate identity",
    ).model_copy(update={"artifact_refs": [artifact_ref]})
    _publish_fact(broker, fact)

    common = {
        "broker": broker,
        "query": fact.statement,
        "max_chars": 50000,
        "max_items": 2,
        "artifact_store": store,
    }
    synthesis = select_typed_fact_context(
        broker.admitted_facts(), purpose=ContextPurpose.SYNTHESIS, **common
    )[0]
    verification = select_typed_fact_context(
        broker.admitted_facts(),
        purpose=ContextPurpose.FINAL_VERIFICATION,
        **common,
    )[0]
    blind = select_typed_fact_context(
        broker.admitted_facts(), purpose=ContextPurpose.BLIND_REVIEW, **common
    )[0]

    assert synthesis["context_purpose"] == "synthesis"
    assert "artifact_refs" not in synthesis
    assert "review_provenance" not in synthesis
    assert verification["artifact_refs"] == [artifact_ref]
    assert blind["context_purpose"] == "blind_review"
    assert "artifact_refs" not in blind
    assert blind["artifact_evidence"] == [
        {
            "artifact_content_hash": hashlib.sha256(b"certificate body").hexdigest(),
            "certificate_type": EvidenceType.NATURAL_PROOF_AUDITED.value,
            "replay_status": "not_applicable",
        }
    ]
    serialized = json.dumps(blind, ensure_ascii=False)
    assert artifact_ref not in serialized
    assert "private-agent" not in serialized
    assert "private-route" not in serialized

    problem = ProblemContract(
        exact_statement="Prove the exact certificate identity.",
        normalized_statement="prove the exact certificate identity",
    )
    blind_packet = build_blind_review_packet(
        problem,
        _proof(problem),
        typed_memory.lemma_memory,
        topology_mode="hierarchical_sparse",
        typed_memory=typed_memory,
        message_broker=broker,
        artifact_store=store,
    )
    serialized_packet = blind_packet.model_dump_json()
    assert artifact_ref not in serialized_packet
    assert "private-agent" not in serialized_packet
    assert "private-route" not in serialized_packet


def test_blind_negative_context_is_bounded_but_keeps_counterexamples(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    config.topology.typed_memory.max_negative_context = 2
    config.topology.max_context_chars = 4000
    _, _, typed_memory, _, broker = make_broker_runtime(config, tmp_path)
    problem = ProblemContract(
        exact_statement="Prove the claimed parity identity.",
        normalized_statement="prove the claimed parity identity",
    )
    optional_statements = [
        "a parity congruence shortcut fails at an even boundary",
        "an unrelated discarded generating function",
        "an unrelated analogy with no transferable lemma",
        "an unrelated invariant candidate",
    ]
    for index, statement in enumerate(optional_statements):
        typed_memory.add_negative(
            make_message(
                message_id=f"optional-negative-{index}",
                route_id="route-a",
                agent_id="author-a",
                statement=statement,
                message_type=MessageType.FAILURE_RECORD,
                evidence_type=EvidenceType.UNVERIFIED_IDEA,
                memory_tier=MemoryTier.NEGATIVE,
                status=ClaimStatus.REJECTED,
            )
        )
    counterexample = make_message(
        message_id="decisive-counterexample",
        route_id="route-b",
        agent_id="author-b",
        statement="the claimed parity identity fails at n=2",
        message_type=MessageType.COUNTEREXAMPLE,
        evidence_type=EvidenceType.COUNTEREXAMPLE,
        memory_tier=MemoryTier.NEGATIVE,
        status=ClaimStatus.REJECTED,
        confidence=1.0,
    )
    typed_memory.add_negative(counterexample)

    packet = build_blind_review_packet(
        problem,
        _proof(problem),
        typed_memory.lemma_memory,
        topology_mode="hierarchical_sparse",
        typed_memory=typed_memory,
        message_broker=broker,
    )

    selected_ids = {item["item_id"] for item in packet.negative_evidence_packets}
    assert counterexample.message_id in selected_ids
    assert "optional-negative-0" in selected_ids
    assert len(selected_ids) == 2
    assert packet.negative_context_truncated
    assert packet.negative_context_complete
    assert packet.negative_evidence_total_count == 5
    assert packet.negative_evidence_omitted_count == 3
    assert packet.negative_context_chars_used <= packet.negative_context_char_budget
    assert counterexample.statement in packet.forbidden_claims
    omitted_statements = set(optional_statements) - set(packet.forbidden_claims)
    assert omitted_statements


def test_missing_fact_or_omitted_mandatory_negative_fails_closed(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    config.topology.typed_memory.max_negative_context = 1
    store, _, typed_memory, _, broker = make_broker_runtime(config, tmp_path)
    problem = ProblemContract(
        exact_statement="Prove the target identity.",
        normalized_statement="prove the target identity",
    )
    unadmitted = make_fact(
        message_id="unadmitted-explicit-fact",
        statement="a route-local candidate not admitted by the Broker",
    )
    typed_memory.add_fact(unadmitted, referee_agent_id="independent-referee")
    for index in range(2):
        typed_memory.add_negative(
            make_message(
                message_id=f"mandatory-counterexample-{index}",
                route_id="route-a",
                agent_id="author-a",
                statement=f"counterexample {index} refutes a decisive shortcut",
                message_type=MessageType.COUNTEREXAMPLE,
                evidence_type=EvidenceType.COUNTEREXAMPLE,
                memory_tier=MemoryTier.NEGATIVE,
                status=ClaimStatus.REJECTED,
                confidence=1.0,
            )
        )
    packet = build_blind_review_packet(
        problem,
        _proof(problem, dependencies=[unadmitted.message_id]),
        typed_memory.lemma_memory,
        topology_mode="hierarchical_sparse",
        typed_memory=typed_memory,
        message_broker=broker,
        artifact_store=store,
    )

    assert not packet.fact_context_complete
    assert packet.missing_cited_fact_refs == [unadmitted.message_id]
    assert not packet.negative_context_complete
    assert packet.negative_mandatory_omitted_count == 1

    text_citation_proof = _proof(problem)
    text_citation_proof.proof_steps[
        0
    ].justification = f"Use the explicit Fact reference {unadmitted.message_id}."
    text_citation_packet = build_blind_review_packet(
        problem,
        text_citation_proof,
        typed_memory.lemma_memory,
        topology_mode="hierarchical_sparse",
        typed_memory=typed_memory,
        message_broker=broker,
    )
    assert text_citation_packet.missing_cited_fact_refs == [unadmitted.message_id]

    report = VerificationReport(
        target_id="final_proof",
        target_type="final_proof",
        agent_id="blind-reviewer",
        stage=VerificationStage.STRUCTURAL,
        verdict=VerificationVerdict.PASS,
        failure_level=FailureLevel.NONE,
        confidence=0.9,
        concise_feedback="The supplied packet appears complete.",
    )
    apply_blind_context_integrity_guard(packet, report)

    assert report.verdict == VerificationVerdict.FAIL
    assert report.confidence == 1.0
    assert report.failure_level == FailureLevel.PLAN
    assert any(
        issue.phase == "blind_context_integrity_guard" for issue in report.issues
    )
