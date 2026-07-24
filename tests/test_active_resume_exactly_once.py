from __future__ import annotations

from mathproofmesh.communication.broker import MessageBroker
from mathproofmesh.communication.receipts import build_receipt
from mathproofmesh.schemas import ReceiptStatus

from v07_helpers import make_broker_runtime, make_fact, make_v07_config


def test_acknowledged_active_delivery_is_exactly_once_across_resume(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    store, registry, memory, graph, broker = make_broker_runtime(config, tmp_path)
    fact = make_fact(message_id="resume-once", target_routes=["route-b"])
    decision = broker.publish(
        fact,
        referee_agent_id="referee-a",
        current_round=1,
    )
    assert decision.selected_targets == ["route-b"]
    delivered = broker.inbox("route-b", current_round=1)
    assert [item.message_id for item in delivered] == ["resume-once"]
    broker.acknowledge(
        build_receipt(
            delivered[0],
            "route-b",
            status=ReceiptStatus.ACCEPTED,
            delivered_round=1,
        )
    )

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
    assert len(restored.receipts) == 1
    assert restored.receipts[0].status == ReceiptStatus.ACCEPTED
    delivery = restored.delivery_record("resume-once", "route-b")
    assert delivery is not None
    assert delivery["prompt_consumed"] is True
    assert delivery["acknowledged"] is True
    assert delivery["status"] == ReceiptStatus.ACCEPTED.value

    restored_again = MessageBroker.from_state(
        restored.export_state(),
        config=config,
        store=store,
        activity=None,
        route_registry=registry,
        proof_graph=graph,
        typed_memory=memory,
    )
    assert restored_again.inbox("route-b", current_round=2) == []
    assert len(restored_again.receipts) == 1
