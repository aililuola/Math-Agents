from __future__ import annotations

from typing import Iterable

from ..config import SystemConfig
from ..schemas import BridgeTask, MessageEnvelope, new_id
from .store import ProofGraphStore


class BridgeBroker:
    """Create bounded bridge-lemma tasks for genuinely shared open obligations."""

    def __init__(self, config: SystemConfig, proof_graph: ProofGraphStore) -> None:
        self.config = config
        self.proof_graph = proof_graph
        self._created_keys: set[tuple[str, ...]] = set()
        self.completed_task_ids: set[str] = set()
        self.tasks: list[BridgeTask] = []

    def detect(
        self,
        *,
        current_round: int,
        allowed_fact_ids: Iterable[str] = (),
        forbidden_negative_ids: Iterable[str] = (),
        budget_available: bool = True,
    ) -> list[BridgeTask]:
        del current_round  # retained in the API for round-scoped orchestration.
        if not self.config.topology.broker.bridge_detection or not budget_available:
            return []
        cap = self.config.topology.broker.max_bridge_tasks_per_round
        if cap == 0:
            return []
        created: list[BridgeTask] = []
        groups = self.proof_graph.find_shared_bottlenecks(
            min_routes=self.config.topology.broker.min_routes_for_bridge
        )
        for group in groups:
            obligation_ids = tuple(sorted(item.obligation_id for item in group))
            if len(obligation_ids) < 2:
                # One obligation shared by several routes is a common target,
                # not a bridge between distinct graph nodes.
                continue
            if obligation_ids in self._created_keys:
                continue
            route_ids = sorted(
                {route_id for item in group for route_id in item.route_ids}
            )
            if len(route_ids) < self.config.topology.broker.min_routes_for_bridge:
                continue
            representative = max(
                group, key=lambda item: item.centrality + item.priority
            )
            task = BridgeTask(
                task_id=new_id("bridge"),
                obligation_ids=list(obligation_ids),
                route_ids=route_ids,
                normalized_goal=representative.normalized_statement,
                allowed_fact_ids=list(dict.fromkeys(allowed_fact_ids)),
                forbidden_negative_ids=list(dict.fromkeys(forbidden_negative_ids)),
                priority=min(
                    1.0,
                    max(item.priority for item in group) + 0.1 * (len(route_ids) - 1),
                ),
            )
            self._created_keys.add(obligation_ids)
            self.tasks.append(task)
            created.append(task)
            self.proof_graph.record_event("bridge_task_created", task)
            if len(created) >= cap:
                break
        return created

    def accept_verified_result(
        self, task_id: str, message: MessageEnvelope
    ) -> list[str]:
        task = next(item for item in self.tasks if item.task_id == task_id)
        if task_id in self.completed_task_ids:
            return []
        if message.verification_status.value != "verified":
            raise ValueError("bridge results require independent verification")
        if message.memory_tier.value != "fact":
            raise ValueError("unverified bridge results cannot be broadcast")
        if message.message_id not in {
            node.message_id for node in self.proof_graph.claim_nodes
        }:
            self.proof_graph.add_claim_node(message)
        closed: list[str] = []
        for obligation_id in task.obligation_ids:
            obligation = self.proof_graph.get_obligation(obligation_id)
            if obligation.status == "closed":
                continue
            self.proof_graph.close_obligation(
                obligation_id,
                message.message_id,
                confidence=message.verification_confidence,
            )
            closed.append(obligation_id)
        self.proof_graph.record_event(
            "bridge_task_completed",
            {
                "task_id": task_id,
                "message_id": message.message_id,
                "closed_obligation_ids": closed,
            },
        )
        self.completed_task_ids.add(task_id)
        return closed

    def export_state(self) -> dict[str, object]:
        return {
            "tasks": [item.model_dump(mode="json") for item in self.tasks],
            "created_keys": [list(item) for item in sorted(self._created_keys)],
            "completed_task_ids": sorted(self.completed_task_ids),
        }

    @classmethod
    def from_state(
        cls,
        state: dict[str, object],
        *,
        config: SystemConfig,
        proof_graph: ProofGraphStore,
    ) -> "BridgeBroker":
        broker = cls(config, proof_graph)
        broker.tasks = [
            BridgeTask.model_validate(item)
            for item in state.get("tasks", [])  # type: ignore[arg-type]
        ]
        broker._created_keys = {
            tuple(str(value) for value in item)
            for item in state.get("created_keys", [])  # type: ignore[union-attr]
        }
        broker.completed_task_ids = {
            str(item)
            for item in state.get("completed_task_ids", [])  # type: ignore[union-attr]
        }
        return broker
