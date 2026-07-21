from __future__ import annotations

from mathproofmesh.broker_phase import record_verified_message_usage
from mathproofmesh.communication.broker import MessageBroker
from mathproofmesh.communication.receipts import build_receipt
from mathproofmesh.schemas import (
    ProofDelta,
    ProofStep,
    QuantifierSpec,
    ReceiptStatus,
    VariableBinding,
)

from v07_helpers import (
    PROBLEM_HASH,
    make_broker_runtime,
    make_fact,
    make_message,
    make_v07_config,
)


def test_broker_shares_only_gated_artifacts_and_deduplicates(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    _, _, _, _, broker = make_broker_runtime(config, tmp_path)

    insight = make_message(
        message_id="insight",
        route_id="route-a",
        agent_id="author-a",
        target_routes=["route-b"],
    )
    local = broker.publish(insight, referee_agent_id=None, current_round=1)
    assert local.accepted
    assert local.selected_targets == []
    assert local.rejected_targets["route-b"].startswith("cross-route")

    fact = make_fact(
        message_id="fact-a",
        target_routes=["route-b", "route-c"],
    )
    shared = broker.publish(fact, referee_agent_id="referee-a", current_round=1)
    assert shared.accepted
    assert set(shared.selected_targets) == {"route-b", "route-c"}

    duplicate = make_fact(
        message_id="fact-a-copy",
        target_routes=["route-b"],
    )
    decision = broker.publish(duplicate, referee_agent_id="referee-a", current_round=1)
    assert decision.duplicate_of == "fact-a"


def test_broker_resume_preserves_exactly_once_prompt_delivery(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    store, registry, memory, graph, broker = make_broker_runtime(config, tmp_path)
    fact = make_fact(message_id="once", target_routes=["route-b"])
    broker.publish(fact, referee_agent_id="referee-a", current_round=1)
    assert [item.message_id for item in broker.inbox("route-b", current_round=1)] == [
        "once"
    ]

    restored = MessageBroker.from_state(
        broker.export_state(),
        config=config,
        store=store,
        activity=None,
        route_registry=registry,
        proof_graph=graph,
        typed_memory=memory,
    )
    assert restored.inbox("route-b", current_round=1) == []
    delivery = next(iter(restored.export_state()["deliveries"].values()))
    assert delivery["prompt_consumed"] is True
    assert delivery["status"] == "pending"

    receipt = build_receipt(
        fact, "route-b", status=ReceiptStatus.ACCEPTED, delivered_round=1
    )
    restored.acknowledge(receipt)
    assert restored.receipts[0].status == ReceiptStatus.ACCEPTED


def test_receipt_rejects_quantifier_reversal_and_binding_scope_change(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    _, _, _, _, broker = make_broker_runtime(config, tmp_path)
    bindings = [
        VariableBinding(
            variable_id="n",
            display_name="n",
            domain="positive integers",
            owner_scope="claim",
        ),
        VariableBinding(
            variable_id="m",
            display_name="M",
            domain="positive integers",
            owner_scope="depends on n",
        ),
    ]
    message = make_fact(
        message_id="quantified",
        target_routes=["route-b"],
        quantifiers=[
            QuantifierSpec(
                order=0,
                kind="forall",
                variable_id="n",
                display_name="n",
                domain="positive integers",
            ),
            QuantifierSpec(
                order=1,
                kind="exists",
                variable_id="m",
                display_name="M",
                domain="positive integers",
            ),
        ],
        variable_bindings=bindings,
    )
    broker.publish(message, referee_agent_id="referee-a", current_round=1)
    broker.inbox("route-b", current_round=1)
    reversed_quantifiers = [
        item.model_copy(update={"order": 1 - item.order})
        for item in message.quantifiers
    ]
    from mathproofmesh.communication.receipts import build_receipt

    receipt = build_receipt(
        message,
        "route-b",
        delivered_round=1,
        parsed_quantifiers=sorted(reversed_quantifiers, key=lambda item: item.order),
        parsed_variable_bindings=bindings,
    )
    broker.acknowledge(receipt)
    assert receipt.status == ReceiptStatus.REJECTED
    assert receipt.reason == "semantic hash mismatch"


def test_message_utility_requires_verified_mathematical_use(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    _, _, _, _, broker = make_broker_runtime(config, tmp_path)
    message = make_fact(message_id="useful", target_routes=["route-b"])
    broker.publish(message, referee_agent_id="referee-a", current_round=1)
    broker.inbox("route-b", current_round=1)
    broker.acknowledge(build_receipt(message, "route-b", delivered_round=1))
    assert broker.utility_for_route("route-b") == 0.0
    assert not broker.record_utility("useful", "route-b")
    assert not broker.record_utility(
        "useful",
        "route-b",
        proof_debt_before=2.0,
        proof_debt_after=1.0,
    )
    assert broker.record_utility("useful", "route-b", referenced_step_ids=["step-2"])
    assert broker.utility_for_route("route-b") > 0.0


def test_broker_phase_credits_only_a_verified_delta_reference(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    _, _, _, graph, broker = make_broker_runtime(config, tmp_path)
    message = make_fact(message_id="cited", target_routes=["route-b"])
    broker.publish(message, referee_agent_id="referee-a", current_round=1)
    broker.inbox("route-b", current_round=1)
    receipt = build_receipt(
        message,
        "route-b",
        delivered_round=1,
        referenced_in_step_ids=["actual-step", "invented-step"],
    )
    broker.acknowledge(receipt)
    delta = ProofDelta(
        problem_hash=PROBLEM_HASH,
        path_id="path-b",
        strategy_id="strategy-1",
        parent_checkpoint_id="checkpoint-b",
        agent_id="author-b",
        round_index=1,
        segment_index=1,
        new_steps=[
            ProofStep(
                step_id="actual-step",
                statement="Apply the independently audited identity.",
                justification="The cited cross-route result supplies this equality.",
                dependencies=[message.message_id],
            )
        ],
        remaining_subgoals=["Finish the route."],
    )

    used = record_verified_message_usage(
        broker,
        [message],
        [receipt],
        delta,
        route_id="route-b",
        proof_graph=graph,
        proof_debt_before=2.0,
    )

    assert used == [message.message_id]
    state = broker.export_state()
    utility = next(iter(state["utility_records"].values()))
    assert utility["referenced_step_ids"] == ["actual-step"]
    assert "invented-step" not in utility["referenced_step_ids"]
