from __future__ import annotations

from collections import defaultdict, deque
from typing import Any, Iterable

from ..config import SchedulerConfig, SystemConfig
from ..proof_identity import (
    canonical_obligation_statement,
    is_feedback_only_statement,
    normalize_text,
)
from ..schemas import (
    ClaimStatus,
    EvidenceType,
    GraphEdgeType,
    MemoryTier,
    MessageEnvelope,
    MessageType,
    ObligationKind,
    ProofGraphEdge,
    ProofObligation,
)
from ..store import ArtifactStore
from .matching import statement_similarity


class ProofGraphStore:
    """Bounded, cycle-safe proof-obligation graph with freeze semantics."""

    def __init__(
        self,
        config: SystemConfig | None = None,
        store: ArtifactStore | None = None,
        *,
        problem_hash: str = "",
    ) -> None:
        self.config = config
        self.store = store
        self.problem_hash = problem_hash
        self._obligations: dict[str, ProofObligation] = {}
        self._obligation_content_index: dict[str, list[str]] = {}
        self._obligation_aliases: dict[str, str] = {}
        self._claim_nodes: dict[str, MessageEnvelope] = {}
        self._edges: dict[str, ProofGraphEdge] = {}
        self._frozen = False
        self._events: list[dict[str, Any]] = []

    @property
    def frozen(self) -> bool:
        return self._frozen

    @property
    def obligations(self) -> list[ProofObligation]:
        return list(self._obligations.values())

    @property
    def edges(self) -> list[ProofGraphEdge]:
        return list(self._edges.values())

    @property
    def claim_nodes(self) -> list[MessageEnvelope]:
        return list(self._claim_nodes.values())

    def _ensure_mutable(self) -> None:
        if self._frozen:
            raise RuntimeError("proof graph is frozen")

    def _limits(self) -> tuple[int, int]:
        if self.config is None:
            return 5000, 20000
        graph = self.config.topology.proof_graph
        return graph.max_nodes, graph.max_edges

    def add_obligation(
        self,
        obligation: ProofObligation | None = None,
        **fields: Any,
    ) -> ProofObligation:
        self._ensure_mutable()
        if obligation is None:
            if "problem_hash" not in fields:
                fields["problem_hash"] = self.problem_hash
            obligation = ProofObligation.model_validate(fields)
        canonical_statement = canonical_obligation_statement(
            obligation.normalized_statement or obligation.statement
        )
        if not canonical_statement:
            raise ValueError("obligation statement is empty after canonicalization")
        normalized_assumptions = sorted(
            {
                normalize_text(item)
                for item in obligation.assumptions
                if normalize_text(item)
            }
        )
        if (
            obligation.statement != canonical_statement
            or obligation.normalized_statement != canonical_statement
            or obligation.assumptions != normalized_assumptions
        ):
            payload = obligation.model_dump(mode="python")
            payload.update(
                {
                    "statement": canonical_statement,
                    "normalized_statement": canonical_statement,
                    "assumptions": normalized_assumptions,
                    "content_hash": "",
                }
            )
            obligation = ProofObligation.model_validate(payload)
        if self.problem_hash and obligation.problem_hash != self.problem_hash:
            raise ValueError("obligation problem_hash mismatch")
        if not self.problem_hash:
            self.problem_hash = obligation.problem_hash
        existing = self._obligations.get(obligation.obligation_id)
        if existing is not None:
            if existing.content_hash != obligation.content_hash:
                raise ValueError("obligation ID collision")
            return existing
        obligation_routes = set(obligation.route_ids)
        canonical_id = next(
            (
                candidate_id
                for candidate_id in self._obligation_content_index.get(
                    obligation.content_hash, []
                )
                if obligation_routes & set(self._obligations[candidate_id].route_ids)
            ),
            None,
        )
        if canonical_id is not None:
            canonical = self._obligations[canonical_id]
            canonical.route_ids = list(
                dict.fromkeys([*canonical.route_ids, *obligation.route_ids])
            )
            canonical.dependency_ids = list(
                dict.fromkeys(
                    [
                        *canonical.dependency_ids,
                        *(
                            self._obligation_aliases.get(item, item)
                            for item in obligation.dependency_ids
                        ),
                    ]
                )
            )
            canonical.evidence_message_ids = list(
                dict.fromkeys(
                    [
                        *canonical.evidence_message_ids,
                        *obligation.evidence_message_ids,
                    ]
                )
            )
            canonical.priority = max(canonical.priority, obligation.priority)
            canonical.centrality = max(canonical.centrality, obligation.centrality)
            canonical.first_error_fingerprint = (
                canonical.first_error_fingerprint or obligation.first_error_fingerprint
            )
            status_order = {
                "open": 0,
                "tentative": 1,
                "blocked": 2,
                "closed": 3,
                "refuted": 4,
            }
            merged_status = max(
                (canonical.status, obligation.status),
                key=lambda item: status_order[item],
            )
            canonical.status = merged_status
            self._obligation_aliases[obligation.obligation_id] = canonical_id
            self._record(
                "obligation_duplicate_collapsed",
                {
                    "duplicate_obligation_id": obligation.obligation_id,
                    "canonical_obligation_id": canonical_id,
                    "content_hash": obligation.content_hash,
                },
            )
            return canonical
        max_nodes, _ = self._limits()
        if len(self._obligations) + len(self._claim_nodes) >= max_nodes:
            raise RuntimeError("proof graph node limit reached")
        self._obligations[obligation.obligation_id] = obligation
        self._obligation_content_index.setdefault(obligation.content_hash, []).append(
            obligation.obligation_id
        )
        self._record("obligation_opened", obligation)
        for dependency_id in obligation.dependency_ids:
            if dependency_id in self._obligations or dependency_id in self._claim_nodes:
                self.add_edge(
                    ProofGraphEdge(
                        source_id=obligation.obligation_id,
                        target_id=dependency_id,
                        edge_type=GraphEdgeType.DEPENDS_ON,
                    )
                )
        return obligation

    def add_claim_node(self, message: MessageEnvelope) -> MessageEnvelope:
        self._ensure_mutable()
        if self.problem_hash and message.problem_hash != self.problem_hash:
            raise ValueError("claim problem_hash mismatch")
        if not self.problem_hash:
            self.problem_hash = message.problem_hash
        existing = self._claim_nodes.get(message.message_id)
        if existing is not None:
            return existing
        max_nodes, _ = self._limits()
        if len(self._obligations) + len(self._claim_nodes) >= max_nodes:
            raise RuntimeError("proof graph node limit reached")
        self._claim_nodes[message.message_id] = message
        self._record("proof_claim_node_added", message)
        return message

    def add_edge(
        self,
        edge: ProofGraphEdge | None = None,
        **fields: Any,
    ) -> ProofGraphEdge:
        self._ensure_mutable()
        edge = edge or ProofGraphEdge.model_validate(fields)
        source_id = self._obligation_aliases.get(edge.source_id, edge.source_id)
        target_id = self._obligation_aliases.get(edge.target_id, edge.target_id)
        if source_id != edge.source_id or target_id != edge.target_id:
            edge = edge.model_copy(
                update={"source_id": source_id, "target_id": target_id}
            )
        if edge.source_id == edge.target_id:
            raise ValueError("self edges are not permitted")
        valid_nodes = set(self._obligations) | set(self._claim_nodes)
        if edge.source_id not in valid_nodes or edge.target_id not in valid_nodes:
            raise KeyError("proof graph edge references an unknown node")
        _, max_edges = self._limits()
        if len(self._edges) >= max_edges:
            raise RuntimeError("proof graph edge limit reached")
        allow_cycles = (
            self.config.topology.proof_graph.allow_cycles
            if self.config is not None
            else False
        )
        if not allow_cycles and edge.edge_type in {
            GraphEdgeType.DEPENDS_ON,
            GraphEdgeType.IMPLIES,
            GraphEdgeType.CLOSES,
        }:
            if self._path_exists(edge.target_id, edge.source_id):
                raise ValueError("proof graph dependency cycle detected")
        self._edges[edge.edge_id] = edge
        self._record("proof_graph_edge_added", edge)
        return edge

    def _path_exists(self, source_id: str, target_id: str) -> bool:
        outgoing: dict[str, list[str]] = defaultdict(list)
        for edge in self._edges.values():
            if edge.edge_type in {
                GraphEdgeType.DEPENDS_ON,
                GraphEdgeType.IMPLIES,
                GraphEdgeType.CLOSES,
            }:
                outgoing[edge.source_id].append(edge.target_id)
        queue = deque([source_id])
        seen: set[str] = set()
        while queue:
            current = queue.popleft()
            if current == target_id:
                return True
            if current in seen:
                continue
            seen.add(current)
            queue.extend(outgoing.get(current, []))
        return False

    def close_obligation(
        self,
        obligation_id: str,
        evidence_message_id: str,
        *,
        confidence: float = 1.0,
    ) -> ProofObligation:
        self._ensure_mutable()
        obligation_id = self._obligation_aliases.get(obligation_id, obligation_id)
        obligation = self._obligations[obligation_id]
        threshold = (
            self.config.topology.proof_graph.close_obligation_threshold
            if self.config is not None
            else 0.8
        )
        if confidence < threshold:
            obligation.status = "tentative"
            return obligation
        evidence = self._claim_nodes.get(evidence_message_id)
        if evidence is None:
            raise ValueError("closed obligation requires a graph evidence message")
        if (
            evidence.memory_tier != MemoryTier.FACT
            or evidence.verification_status != ClaimStatus.VERIFIED
        ):
            raise ValueError("only a verified fact can close an obligation")
        obligation.evidence_message_ids = list(
            dict.fromkeys(obligation.evidence_message_ids + [evidence_message_id])
        )
        obligation.status = "closed"
        self.add_edge(
            ProofGraphEdge(
                source_id=evidence_message_id,
                target_id=obligation_id,
                edge_type=GraphEdgeType.CLOSES,
                evidence_message_id=evidence_message_id,
            )
        )
        self._record("obligation_closed", obligation)
        return obligation

    def refute_obligation(
        self, obligation_id: str, *, evidence_message_id: str | None = None
    ) -> ProofObligation:
        self._ensure_mutable()
        obligation_id = self._obligation_aliases.get(obligation_id, obligation_id)
        obligation = self._obligations[obligation_id]
        obligation.status = "refuted"
        if evidence_message_id:
            obligation.evidence_message_ids = list(
                dict.fromkeys(obligation.evidence_message_ids + [evidence_message_id])
            )
        self._record("obligation_refuted", obligation)
        for dependent in self.find_dependents(obligation_id):
            if dependent.status == "closed":
                self.reopen_obligation(dependent.obligation_id)
        return obligation

    def reopen_obligation(self, obligation_id: str) -> ProofObligation:
        self._ensure_mutable()
        obligation_id = self._obligation_aliases.get(obligation_id, obligation_id)
        obligation = self._obligations[obligation_id]
        obligation.status = "open"
        obligation.evidence_message_ids = []
        self._record("obligation_reopened", obligation)
        return obligation

    def get_obligation(self, obligation_id: str) -> ProofObligation:
        obligation_id = self._obligation_aliases.get(obligation_id, obligation_id)
        return self._obligations[obligation_id]

    def record_event(self, event_type: str, payload: Any) -> None:
        self._record(event_type, payload)

    def find_equivalent_obligations(
        self,
        obligation: ProofObligation | str,
        *,
        threshold: float | None = None,
        open_only: bool = True,
    ) -> list[ProofObligation]:
        source = (
            self._obligations[obligation] if isinstance(obligation, str) else obligation
        )
        if threshold is None:
            threshold = (
                self.config.topology.broker.bridge_similarity_threshold
                if self.config is not None
                else 0.78
            )
        return [
            item
            for item in self._obligations.values()
            if item.obligation_id != source.obligation_id
            and (not open_only or item.status in {"open", "tentative", "blocked"})
            and item.problem_hash == source.problem_hash
            and item.assumptions == source.assumptions
            and [q.model_dump(mode="json") for q in item.quantifiers]
            == [q.model_dump(mode="json") for q in source.quantifiers]
            and statement_similarity(
                item.normalized_statement, source.normalized_statement
            )
            >= threshold
        ]

    def find_dependents(self, node_id: str) -> list[ProofObligation]:
        node_id = self._obligation_aliases.get(node_id, node_id)
        dependent_ids = {
            edge.source_id
            for edge in self._edges.values()
            if edge.target_id == node_id and edge.edge_type == GraphEdgeType.DEPENDS_ON
        }
        dependent_ids.update(
            item.obligation_id
            for item in self._obligations.values()
            if node_id in item.dependency_ids
        )
        return [
            self._obligations[item]
            for item in dependent_ids
            if item in self._obligations
        ]

    def find_shared_bottlenecks(
        self, *, min_routes: int | None = None
    ) -> list[list[ProofObligation]]:
        minimum = min_routes or (
            self.config.topology.proof_graph.shared_bottleneck_min_routes
            if self.config is not None
            else 2
        )
        open_items = [
            item
            for item in self._obligations.values()
            if item.status in {"open", "tentative", "blocked"}
        ]
        groups: list[list[ProofObligation]] = []
        consumed: set[str] = set()
        for item in open_items:
            if item.obligation_id in consumed:
                continue
            group = [item, *self.find_equivalent_obligations(item)]
            route_ids = {route for member in group for route in member.route_ids}
            if len(route_ids) >= minimum:
                unique = {member.obligation_id: member for member in group}
                ordered = list(unique.values())
                groups.append(ordered)
                consumed.update(unique)
        return groups

    def proof_debt(self, route_id: str) -> float:
        scheduler = (
            self.config.scheduler if self.config is not None else SchedulerConfig()
        )
        dependent_counts: dict[str, int] = defaultdict(int)
        for edge in self._edges.values():
            if edge.edge_type == GraphEdgeType.DEPENDS_ON:
                dependent_counts[edge.target_id] += 1
        debt = 0.0
        for item in self._obligations.values():
            if route_id not in item.route_ids or item.status == "closed":
                continue
            weight = scheduler.obligation_base_weight
            if item.kind == ObligationKind.MAIN_GOAL:
                weight += scheduler.obligation_main_goal_weight
            weight += item.centrality * scheduler.obligation_centrality_weight
            weight += (
                dependent_counts[item.obligation_id]
                * scheduler.obligation_dependency_weight
            )
            weight += max(0, len(set(item.route_ids)) - 1) * (
                scheduler.obligation_shared_route_weight
            )
            if item.first_error_fingerprint:
                weight += scheduler.obligation_failure_weight
            if item.kind == ObligationKind.CONTRADICTION or item.status == "blocked":
                weight += scheduler.obligation_conflict_weight
            debt += weight * max(0.01, item.priority)
        return debt

    def ingest_message(self, message: MessageEnvelope) -> None:
        self._ensure_mutable()
        if message.message_type in {
            MessageType.CLAIM_PROPOSAL,
            MessageType.VERIFIED_LEMMA,
            MessageType.COMPUTATION_CERTIFICATE,
            MessageType.FORMAL_CERTIFICATE,
            MessageType.COUNTEREXAMPLE,
            MessageType.CONTRADICTION_NOTICE,
        }:
            self.add_claim_node(message)
        if message.message_type in {
            MessageType.PROOF_OBLIGATION,
            MessageType.BRIDGE_LEMMA_REQUEST,
            MessageType.REPAIR_REQUEST,
        }:
            kind = (
                ObligationKind.LEMMA
                if message.message_type == MessageType.BRIDGE_LEMMA_REQUEST
                else ObligationKind.SUBGOAL
            )
            self.add_obligation(
                ProofObligation(
                    problem_hash=message.problem_hash,
                    route_ids=list(
                        dict.fromkeys(
                            [message.source_route_id, *message.target_route_ids]
                        )
                    ),
                    kind=kind,
                    statement=canonical_obligation_statement(message.statement),
                    normalized_statement=canonical_obligation_statement(
                        message.normalized_statement
                    ),
                    assumptions=message.assumptions,
                    quantifiers=message.quantifiers,
                    dependency_ids=message.dependencies,
                    status="open",
                    priority=max(0.1, message.verification_confidence),
                )
            )
        if message.memory_tier == MemoryTier.FACT:
            for obligation in list(self._obligations.values()):
                if obligation.status == "closed":
                    continue
                if (
                    obligation.assumptions == message.assumptions
                    and obligation.normalized_statement == message.normalized_statement
                ):
                    self.close_obligation(
                        obligation.obligation_id,
                        message.message_id,
                        confidence=message.verification_confidence,
                    )

    def apply_counterexample(self, message: MessageEnvelope) -> list[str]:
        self._ensure_mutable()
        if message.evidence_type != EvidenceType.COUNTEREXAMPLE:
            return []
        if message.message_id not in self._claim_nodes:
            self.add_claim_node(message)
        affected: list[str] = []
        for obligation in list(self._obligations.values()):
            if (
                obligation.normalized_statement == message.normalized_statement
                or message.conclusion in obligation.normalized_statement
            ):
                self.refute_obligation(
                    obligation.obligation_id,
                    evidence_message_id=message.message_id,
                )
                affected.append(obligation.obligation_id)
        return affected

    def freeze(self) -> None:
        self._frozen = True
        self._record("proof_graph_frozen", {"node_count": len(self._obligations)})

    def export_state(self) -> dict[str, Any]:
        return {
            "mode": (
                self.config.topology.proof_graph.mode
                if self.config is not None
                else "active"
            ),
            "problem_hash": self.problem_hash,
            "frozen": self._frozen,
            "obligations": {
                key: value.model_dump(mode="json")
                for key, value in self._obligations.items()
            },
            "claim_nodes": {
                key: value.model_dump(mode="json")
                for key, value in self._claim_nodes.items()
            },
            "edges": {
                key: value.model_dump(mode="json") for key, value in self._edges.items()
            },
            "events": list(self._events),
            "obligation_aliases": dict(self._obligation_aliases),
        }

    @classmethod
    def from_state(
        cls,
        state: dict[str, Any],
        *,
        config: SystemConfig | None = None,
        store: ArtifactStore | None = None,
    ) -> "ProofGraphStore":
        graph = cls(config, None, problem_hash=str(state.get("problem_hash", "")))
        skipped_feedback_ids: list[str] = []
        for key, value in dict(state.get("obligations", {})).items():
            payload = dict(value)
            payload["obligation_id"] = str(key)
            obligation = ProofObligation.model_validate(payload)
            if is_feedback_only_statement(obligation.statement):
                skipped_feedback_ids.append(str(key))
                continue
            graph.add_obligation(obligation)
        graph._claim_nodes = {
            str(key): MessageEnvelope.model_validate(value)
            for key, value in dict(state.get("claim_nodes", {})).items()
        }
        persisted_aliases = {
            str(key): str(value)
            for key, value in dict(state.get("obligation_aliases", {})).items()
        }
        for alias, target in persisted_aliases.items():
            resolved = graph._obligation_aliases.get(target, target)
            if resolved in graph._obligations:
                graph._obligation_aliases[alias] = resolved
        graph._edges = {}
        valid_nodes = set(graph._obligations) | set(graph._claim_nodes)
        edge_keys: set[tuple[str, str, GraphEdgeType, str | None]] = set()
        for key, value in dict(state.get("edges", {})).items():
            edge = ProofGraphEdge.model_validate(value)
            source_id = graph._obligation_aliases.get(edge.source_id, edge.source_id)
            target_id = graph._obligation_aliases.get(edge.target_id, edge.target_id)
            if (
                source_id == target_id
                or source_id not in valid_nodes
                or target_id not in valid_nodes
            ):
                continue
            edge_key = (
                source_id,
                target_id,
                edge.edge_type,
                edge.evidence_message_id,
            )
            if edge_key in edge_keys:
                continue
            edge_keys.add(edge_key)
            graph._edges[str(key)] = edge.model_copy(
                update={"source_id": source_id, "target_id": target_id}
            )
        graph._events = [dict(item) for item in state.get("events", [])]
        if skipped_feedback_ids:
            graph._events.append(
                {
                    "event_type": "feedback_only_obligations_removed_on_resume",
                    "payload": {
                        "count": len(skipped_feedback_ids),
                        "obligation_ids": skipped_feedback_ids[:200],
                    },
                }
            )
        graph._frozen = bool(state.get("frozen", False))
        graph.store = store
        return graph

    def minimal_subgraph(self, obligation_ids: Iterable[str]) -> dict[str, Any]:
        selected = {self._obligation_aliases.get(item, item) for item in obligation_ids}
        for item_id in list(selected):
            selected.update(
                edge.target_id
                for edge in self._edges.values()
                if edge.source_id == item_id
            )
        return {
            "obligations": [
                item.model_dump(mode="json")
                for item_id, item in self._obligations.items()
                if item_id in selected
            ],
            "edges": [
                edge.model_dump(mode="json")
                for edge in self._edges.values()
                if edge.source_id in selected and edge.target_id in selected
            ],
        }

    def _record(self, event_type: str, payload: Any) -> None:
        event_payload = (
            payload.model_dump(mode="json")
            if hasattr(payload, "model_dump")
            else dict(payload)
        )
        self._events.append({"event_type": event_type, "payload": event_payload})
        if self.store is not None:
            self.store.append_event(event_type, event_payload)
