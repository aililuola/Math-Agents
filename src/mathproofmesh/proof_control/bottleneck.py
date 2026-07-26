from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

from ..config import BottleneckControlConfig
from ..proof_graph.matching import statement_similarity
from ..proof_graph.store import ProofGraphStore
from ..proof_identity import canonical_obligation_statement
from ..schemas import ProofObligation
from .models import (
    BottleneckCluster,
    ObligationDomain,
    ObligationDomainRecord,
    ScopeSignature,
)


class BottleneckCompressor:
    """Build semantic sidecar clusters without mutating graph nodes or edges."""

    def __init__(self, config: BottleneckControlConfig | None = None) -> None:
        self.config = config or BottleneckControlConfig()

    def scan_open_obligations(
        self,
        graph: ProofGraphStore,
        *,
        obligation_domains: Mapping[str, ObligationDomainRecord] | None = None,
    ) -> list[ProofObligation]:
        obligation_domains = obligation_domains or {}
        return sorted(
            (
                item
                for item in graph.obligations
                if item.status in {"open", "tentative", "blocked"}
                and (
                    item.obligation_id not in obligation_domains
                    or obligation_domains[item.obligation_id].domain
                    == ObligationDomain.MATHEMATICAL
                )
            ),
            key=lambda item: (
                -item.centrality,
                -item.priority,
                item.obligation_id,
            ),
        )

    def deterministic_clusters(
        self,
        graph: ProofGraphStore,
        *,
        scope_signatures: Mapping[str, ScopeSignature] | None = None,
        obligation_domains: Mapping[str, ObligationDomainRecord] | None = None,
    ) -> list[list[ProofObligation]]:
        scope_signatures = scope_signatures or {}
        remaining = self.scan_open_obligations(
            graph,
            obligation_domains=obligation_domains,
        )
        groups: list[list[ProofObligation]] = []
        while remaining:
            seed = remaining.pop(0)
            group = [seed]
            changed = True
            while changed:
                changed = False
                for candidate in list(remaining):
                    if any(
                        self._pair_score(
                            graph,
                            member,
                            candidate,
                            scope_signatures,
                        )
                        >= self.config.equivalence_threshold
                        for member in group
                    ):
                        group.append(candidate)
                        remaining.remove(candidate)
                        changed = True
            if len(group) >= 2:
                groups.append(
                    sorted(group, key=lambda item: item.obligation_id)[
                        : self.config.max_cluster_members
                    ]
                )
        return groups

    async def review_ambiguous_clusters(
        self,
        candidates: Sequence[tuple[ProofObligation, ProofObligation]],
        *,
        runner: Any,
        prompt_factory: Any,
    ) -> list[BottleneckCluster]:
        reviewed: list[BottleneckCluster] = []
        for left, right in candidates:
            result = await runner.call(
                "structural_verifier",
                prompt_factory.review_bottleneck_cluster(
                    left=left,
                    right=right,
                ),
            )
            artifact = getattr(result, "artifact", result)
            if isinstance(artifact, BottleneckCluster):
                reviewed.append(artifact)
        return reviewed

    def materialize_clusters(
        self,
        graph: ProofGraphStore,
        groups: Sequence[Sequence[ProofObligation]],
    ) -> list[BottleneckCluster]:
        clusters: list[BottleneckCluster] = []
        for group in groups:
            unique = {item.obligation_id: item for item in group}
            if len(unique) < 2:
                continue
            members = list(unique.values())
            canonical = max(
                members,
                key=lambda item: (
                    item.centrality,
                    item.priority,
                    len(graph.cluster_neighborhood(item.obligation_id)),
                    -len(item.normalized_statement),
                    item.obligation_id,
                ),
            )
            route_ids = sorted(
                {route_id for item in members for route_id in item.route_ids}
            )
            shared_assumptions = sorted(
                set.intersection(*(set(item.assumptions) for item in members))
                if members
                else set()
            )
            debt = sum(graph.proof_debt(route_id) for route_id in route_ids)
            clusters.append(
                BottleneckCluster(
                    member_obligation_ids=sorted(unique),
                    canonical_obligation_id=canonical.obligation_id,
                    canonical_statement=canonical_obligation_statement(
                        canonical.normalized_statement
                    ),
                    shared_assumptions=shared_assumptions,
                    route_ids=route_ids,
                    centrality=max(item.centrality for item in members),
                    proof_debt=debt,
                    alias_map={
                        item.obligation_id: canonical.obligation_id for item in members
                    },
                    member_statuses={
                        item.obligation_id: item.status for item in members
                    },
                    first_error_fingerprints=sorted(
                        {
                            item.first_error_fingerprint
                            for item in members
                            if item.first_error_fingerprint
                        }
                    ),
                )
            )
        return sorted(clusters, key=lambda item: item.cluster_id)

    def refresh_cluster_status(
        self, graph: ProofGraphStore, cluster: BottleneckCluster
    ) -> BottleneckCluster:
        statuses = [
            graph.get_obligation(item).status for item in cluster.member_obligation_ids
        ]
        canonical_status = graph.get_obligation(cluster.canonical_obligation_id).status
        if canonical_status in {"closed", "refuted"}:
            cluster.status = "resolved"
            cluster.member_statuses = {
                member_id: canonical_status
                for member_id in cluster.member_obligation_ids
            }
        else:
            cluster.member_statuses = {
                member_id: status
                for member_id, status in zip(
                    cluster.member_obligation_ids,
                    statuses,
                )
            }
        if statuses and all(item in {"closed", "refuted"} for item in statuses):
            cluster.status = "resolved"
        return cluster

    def _pair_score(
        self,
        graph: ProofGraphStore,
        left: ProofObligation,
        right: ProofObligation,
        scope_signatures: Mapping[str, ScopeSignature],
    ) -> float:
        if left.problem_hash != right.problem_hash:
            return 0.0
        score = 0.48 * statement_similarity(
            canonical_obligation_statement(left.normalized_statement),
            canonical_obligation_statement(right.normalized_statement),
        )
        if left.assumptions == right.assumptions:
            score += 0.16
        left_quantifiers = [item.model_dump(mode="json") for item in left.quantifiers]
        right_quantifiers = [item.model_dump(mode="json") for item in right.quantifiers]
        if left_quantifiers == right_quantifiers:
            score += 0.10
        left_scope = scope_signatures.get(left.obligation_id)
        right_scope = scope_signatures.get(right.obligation_id)
        if self._scope_key(left_scope) == self._scope_key(right_scope):
            score += 0.10
        left_neighbors = graph.cluster_neighborhood(left.obligation_id)
        right_neighbors = graph.cluster_neighborhood(right.obligation_id)
        union = left_neighbors | right_neighbors
        if union:
            score += 0.08 * len(left_neighbors & right_neighbors) / len(union)
        if left.first_error_fingerprint == right.first_error_fingerprint:
            score += 0.04
        left_routes = set(left.route_ids)
        right_routes = set(right.route_ids)
        route_union = left_routes | right_routes
        if route_union:
            score += 0.04 * len(left_routes & right_routes) / len(route_union)
        return min(1.0, score)

    @staticmethod
    def _scope_key(signature: ScopeSignature | None) -> tuple[Any, ...] | None:
        if signature is None:
            return None
        return (
            signature.index_scope,
            signature.uniformity,
            signature.object_scope,
            tuple(
                (
                    item.order,
                    item.kind,
                    item.domain,
                    tuple(item.restrictions),
                )
                for item in signature.quantifiers
            ),
            tuple(signature.domain_constraints),
            tuple(signature.exceptional_cases),
        )
