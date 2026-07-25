from __future__ import annotations

from pathlib import Path

from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.schemas import (
    AttemptStatus,
    ClaimCard,
    ObligationKind,
    ProofAttempt,
    ProofObligation,
    ProofStep,
)

from v07_helpers import (
    PROBLEM_HASH,
    make_broker_runtime,
    make_message,
    make_proof_control_config,
    make_strategy,
)


def test_sidecar_registration_preserves_all_hash_critical_payloads(
    tmp_path: Path,
) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    goal = graph.add_obligation(
        ProofObligation(
            obligation_id="main-goal",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-a"],
            kind=ObligationKind.MAIN_GOAL,
            statement="For every n, P(n) holds.",
            normalized_statement="for every n, p(n) holds.",
        )
    )
    step = ProofStep(
        step_id="step-a",
        statement="Establish the induction step.",
        justification="Apply the declared recurrence.",
        is_key_step=True,
        confidence=0.9,
    )
    claim = ClaimCard(
        claim_id="claim-a",
        statement="P(n) holds for every n.",
        conclusion="P(n) holds for every n.",
        proof_steps=[step],
    )
    message = make_message(
        message_id="message-a",
        route_id="route-a",
        agent_id="author-a",
        statement="P(n) holds for every n.",
    )
    strategy = make_strategy(0)
    attempt = ProofAttempt(
        attempt_id="attempt-a",
        problem_hash=PROBLEM_HASH,
        strategy_id=strategy.strategy_id,
        agent_id="author-a",
        round_index=1,
        status=AttemptStatus.PARTIAL,
        proof_steps=[step],
        proposed_lemmas=[claim],
        unresolved_gaps=["Connect the induction step to the main goal."],
    )
    before = {
        "message_hash": message.content_hash,
        "message_payload": message.immutable_payload(),
        "claim_hash": claim.content_hash,
        "obligation_hash": goal.content_hash,
        "step_payload": step.checkpoint_payload(),
    }

    controller = ProofControlLayer(
        config,
        store,
        None,
        graph,
        memory,
        broker,
        registry,
    )
    controller.register_obligation(goal)
    controller.register_strategy(strategy)
    controller.register_claim(claim, route_id="route-a")
    controller.register_message(message)
    controller.register_attempt(attempt)
    controller.update_after_round(strategies=[strategy], current_round=1)

    assert message.content_hash == before["message_hash"]
    assert message.immutable_payload() == before["message_payload"]
    assert claim.content_hash == before["claim_hash"]
    assert goal.content_hash == before["obligation_hash"]
    assert step.checkpoint_payload() == before["step_payload"]
