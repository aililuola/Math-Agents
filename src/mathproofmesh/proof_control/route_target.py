from __future__ import annotations

from collections import defaultdict, deque
from collections.abc import Mapping, Sequence

from ..proof_graph.store import ProofGraphStore
from ..proof_identity import obligation_identity_text
from ..schemas import (
    GraphEdgeType,
    ObligationKind,
    ProofObligation,
    StrategyCard,
    stable_hash,
)
from .models import (
    ClaimGoalLink,
    GoalRelation,
    RouteTargetBinding,
    ScopeRelation,
)


def choose_nearest_target_obligation(
    strategy: StrategyCard,
    proof_graph: ProofGraphStore,
    goal_links: Mapping[str, ClaimGoalLink],
) -> RouteTargetBinding:
    """Bind a route to the nearest auditable open mathematical target."""

    main_ids = proof_graph.main_goal_obligation_ids()
    if not main_ids:
        raise ValueError("route target binding requires a main-goal obligation")
    main_id = main_ids[0]
    open_obligations = [
        item
        for item in proof_graph.obligations
        if item.status in {"open", "tentative", "blocked"}
    ]
    if not open_obligations:
        raise ValueError("route target binding requires an open obligation")

    paths = {
        item.obligation_id: _path_to_main(item.obligation_id, main_id, proof_graph)
        for item in open_obligations
    }
    prior = next(
        (
            link
            for link in goal_links.values()
            if link.subject_id == strategy.strategy_id
            and any(
                item.obligation_id == link.target_obligation_id
                for item in open_obligations
            )
        ),
        None,
    )
    explicit_ids = _explicit_target_ids(strategy, open_obligations)
    statement_matches = _statement_matches(strategy, open_obligations)

    selected: ProofObligation
    source: str
    if prior is not None:
        selected = proof_graph.get_obligation(prior.target_obligation_id)
        source = "prior_goal_link"
    elif explicit_ids:
        selected = _nearest(explicit_ids, open_obligations, paths)
        source = "explicit_target"
    elif statement_matches:
        selected = _nearest(statement_matches, open_obligations, paths)
        source = "critical_claim_match"
    else:
        selected = proof_graph.get_obligation(main_id)
        source = "main_goal_fallback"

    path = paths.get(selected.obligation_id) or [selected.obligation_id]
    path_complete = path[-1] == main_id
    matched_claim_ids = [
        claim.claim_id
        for claim in strategy.critical_claims
        if obligation_identity_text(claim.statement)
        == obligation_identity_text(selected.normalized_statement)
    ]
    direct_relation = (
        prior.relation
        if prior is not None
        else GoalRelation.EQUIVALENT
        if source == "critical_claim_match"
        else GoalRelation.UNKNOWN
        if source == "main_goal_fallback"
        else GoalRelation.SUFFICIENT
    )
    relation_to_main = (
        direct_relation
        if selected.obligation_id == main_id
        else GoalRelation.NECESSARY_ONLY
    )
    confidence = {
        "prior_goal_link": prior.alignment_confidence if prior is not None else 0.5,
        "explicit_target": 0.9,
        "critical_claim_match": 0.98,
        "main_goal_fallback": 0.5,
    }[source]
    identity = {
        "problem_hash": proof_graph.problem_hash,
        "strategy_id": strategy.strategy_id,
        "direct_target": selected.obligation_id,
        "main_goal": main_id,
    }
    return RouteTargetBinding(
        binding_id=f"route_target_{stable_hash(identity)[:16]}",
        strategy_id=strategy.strategy_id,
        route_id=None,
        direct_target_obligation_id=selected.obligation_id,
        ancestor_obligation_ids=path[1:],
        main_goal_obligation_id=main_id,
        direct_claim_ids=matched_claim_ids,
        bridge_obligation_ids=path[1:-1],
        relation_to_direct_target=direct_relation,
        relation_to_main_goal=relation_to_main,
        scope_relation_to_direct_target=(
            prior.scope_relation if prior is not None else ScopeRelation.UNKNOWN
        ),
        blueprint_path_complete=path_complete,
        binding_confidence=confidence,
    )


def _explicit_target_ids(
    strategy: StrategyCard,
    obligations: Sequence[ProofObligation],
) -> set[str]:
    annotations = {
        value.strip()
        for value in [
            *strategy.tags,
            *strategy.prerequisites,
            *strategy.expected_lemmas,
        ]
        if value.strip()
    }
    expanded = set(annotations)
    for value in annotations:
        for prefix in ("target:", "obligation:"):
            if value.casefold().startswith(prefix):
                expanded.add(value[len(prefix) :].strip())
    return {
        item.obligation_id for item in obligations if item.obligation_id in expanded
    }


def _statement_matches(
    strategy: StrategyCard,
    obligations: Sequence[ProofObligation],
) -> set[str]:
    candidate_statements = {
        obligation_identity_text(value)
        for value in [
            strategy.bottleneck,
            *strategy.expected_lemmas,
            *(claim.statement for claim in strategy.critical_claims),
        ]
        if obligation_identity_text(value)
    }
    return {
        item.obligation_id
        for item in obligations
        if obligation_identity_text(item.normalized_statement) in candidate_statements
    }


def _nearest(
    candidate_ids: set[str],
    obligations: Sequence[ProofObligation],
    paths: Mapping[str, list[str]],
) -> ProofObligation:
    candidates = [item for item in obligations if item.obligation_id in candidate_ids]
    return min(
        candidates,
        key=lambda item: (
            len(paths.get(item.obligation_id, [])) or 10**6,
            item.kind == ObligationKind.MAIN_GOAL,
            -item.centrality,
            -item.priority,
            item.obligation_id,
        ),
    )


def _path_to_main(
    start_id: str,
    main_id: str,
    graph: ProofGraphStore,
) -> list[str]:
    if start_id == main_id:
        return [main_id]
    parents: dict[str, set[str]] = defaultdict(set)
    for obligation in graph.obligations:
        for dependency_id in obligation.dependency_ids:
            parents[dependency_id].add(obligation.obligation_id)
    for edge in graph.edges:
        if edge.edge_type == GraphEdgeType.DEPENDS_ON:
            parents[edge.target_id].add(edge.source_id)
        elif edge.edge_type in {GraphEdgeType.IMPLIES, GraphEdgeType.CLOSES}:
            parents[edge.source_id].add(edge.target_id)
    queue: deque[tuple[str, list[str]]] = deque([(start_id, [start_id])])
    seen: set[str] = set()
    while queue:
        current, path = queue.popleft()
        if current == main_id:
            return path
        if current in seen:
            continue
        seen.add(current)
        for parent in sorted(parents.get(current, set())):
            queue.append((parent, [*path, parent]))
    return []
