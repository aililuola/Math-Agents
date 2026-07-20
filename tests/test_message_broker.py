from __future__ import annotations

from mathproofmesh.communication.broker import MessageBroker
from mathproofmesh.schemas import ReceiptStatus

from v07_helpers import make_broker_runtime, make_fact, make_message, make_v07_config


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

    from mathproofmesh.communication.receipts import build_receipt

    receipt = build_receipt(
        fact, "route-b", status=ReceiptStatus.ACCEPTED, delivered_round=1
    )
    restored.acknowledge(receipt)
    assert restored.receipts[0].status == ReceiptStatus.ACCEPTED
