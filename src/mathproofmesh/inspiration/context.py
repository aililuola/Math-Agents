from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from ..context_policy import ContextPurpose, select_typed_fact_context
from ..schemas import (
    EvidenceType,
    InspirationContextMode,
    InspirationMechanism,
    InspirationProposal,
    InspirationTask,
    MessageEnvelope,
)
from ..topology import jaccard_similarity
from .trigger_policy import InspirationSnapshot


@dataclass(frozen=True, slots=True)
class _MechanismContextProfile:
    fact_limit: int
    negative_limit: int
    include_graph: bool
    include_route_signatures: bool


_PROFILES: dict[InspirationMechanism, _MechanismContextProfile] = {
    InspirationMechanism.REPRESENTATION_SWITCH: _MechanismContextProfile(
        2, 2, False, True
    ),
    InspirationMechanism.STRUCTURAL_ANALOGY: _MechanismContextProfile(3, 2, True, True),
    InspirationMechanism.AUXILIARY_CONSTRUCTION: _MechanismContextProfile(
        5, 5, True, True
    ),
    InspirationMechanism.INVARIANT_HYPOTHESIS: _MechanismContextProfile(
        4, 4, True, True
    ),
    InspirationMechanism.REVERSE_GOAL_ANALYSIS: _MechanismContextProfile(
        5, 3, True, False
    ),
    InspirationMechanism.BRIDGE_LEMMA: _MechanismContextProfile(5, 3, True, False),
    InspirationMechanism.META_REPLAN: _MechanismContextProfile(0, 0, False, False),
    InspirationMechanism.SURPRISE_EXPLORATION: _MechanismContextProfile(
        2, 2, False, True
    ),
}


_SLOT_DIRECTIVES = (
    "mechanism_first: expose a structural transformation and its reversible mapping",
    "independent_reconstruction: avoid route-specific proof text and rebuild from the target obligation",
    "failure_driven: attack the repeated gap with a mechanism absent from the forbidden list",
)


def _compact_signature(signature: Any) -> dict[str, Any]:
    return {
        "representation_tags": list(signature.representation_tags),
        "mechanism_tags": list(signature.mechanism_tags),
        "core_objects": list(signature.core_objects),
        "key_transformations": list(signature.key_transformations),
        "proof_principles": list(signature.proof_principles),
        "targeted_obligation_ids": list(signature.targeted_obligation_ids),
        "normalizer_version": signature.normalizer_version,
        "normalization_confidence": signature.normalization_confidence,
    }


def _negative_packet(
    item: MessageEnvelope | InspirationProposal,
) -> dict[str, Any]:
    if isinstance(item, MessageEnvelope):
        return {
            "item_id": item.message_id,
            "statement": item.statement,
            "conclusion": item.conclusion,
            "evidence_type": item.evidence_type.value,
            "scope_limitations": list(item.scope_limitations),
        }
    return {
        "item_id": item.proposal_id,
        "statement": item.statement,
        "mechanism": item.mechanism.value,
        "generated_obligations": list(item.generated_obligations),
        "evidence_type": item.evidence_type.value,
    }


def _negative_text(item: MessageEnvelope | InspirationProposal) -> str:
    if isinstance(item, MessageEnvelope):
        return " ".join(
            [
                item.statement,
                item.conclusion,
                *item.assumptions,
                *item.scope_limitations,
            ]
        )
    return " ".join(
        [
            item.statement,
            item.rationale_summary,
            *item.generated_obligations,
            *item.novelty_signature.mechanism_tags,
        ]
    )


def _select_negatives(
    values: list[MessageEnvelope | InspirationProposal],
    *,
    query: str,
    target_obligation_ids: set[str],
    max_items: int,
    max_chars: int,
) -> list[dict[str, Any]]:
    if max_items <= 0 or max_chars <= 0:
        return []

    def rank(item: MessageEnvelope | InspirationProposal) -> tuple[float, str]:
        counterexample = (
            isinstance(item, MessageEnvelope)
            and item.evidence_type == EvidenceType.COUNTEREXAMPLE
        )
        targets = (
            set(item.generated_obligations)
            if isinstance(item, InspirationProposal)
            else set()
        )
        relevance = jaccard_similarity(query, _negative_text(item))
        score = relevance + (1.0 if counterexample else 0.0)
        score += 0.5 if targets & target_obligation_ids else 0.0
        identifier = (
            item.message_id if isinstance(item, MessageEnvelope) else item.proposal_id
        )
        return score, identifier

    selected: list[dict[str, Any]] = []
    used = 0
    for item in sorted(values, key=lambda value: (-rank(value)[0], rank(value)[1])):
        score, _identifier = rank(item)
        if score <= 0:
            continue
        packet = _negative_packet(item)
        size = len(json.dumps(packet, ensure_ascii=False, separators=(",", ":")))
        if used + size > max_chars:
            continue
        selected.append(packet)
        used += size
        if len(selected) >= max_items:
            break
    return selected


def _context_chars(value: dict[str, Any]) -> int:
    return len(json.dumps(value, ensure_ascii=False, separators=(",", ":")))


def _enforce_context_budget(
    context: dict[str, Any], *, max_chars: int
) -> dict[str, Any]:
    if _context_chars(context) <= max_chars:
        return context
    context["context_truncated"] = True
    for key in (
        "route_novelty_signatures",
        "negative_memory",
        "verified_facts",
    ):
        values = context.get(key)
        while (
            isinstance(values, list) and values and _context_chars(context) > max_chars
        ):
            values.pop()
    graph = context.get("proof_graph")
    if isinstance(graph, dict):
        for key in ("edges", "obligations"):
            values = graph.get(key)
            while (
                isinstance(values, list)
                and values
                and _context_chars(context) > max_chars
            ):
                values.pop()
    targets = context.get("target_obligations")
    while (
        isinstance(targets, list)
        and len(targets) > 1
        and _context_chars(context) > max_chars
    ):
        targets.pop()
    contract = context.get("generation_contract")
    forbidden = (
        contract.get("forbidden_existing_mechanisms")
        if isinstance(contract, dict)
        else None
    )
    while (
        isinstance(forbidden, list)
        and forbidden
        and _context_chars(context) > max_chars
    ):
        forbidden.pop()
    if _context_chars(context) > max_chars:
        context["proof_graph"] = {}
        context["target_obligations"] = []
        metrics = context.get("search_metrics", {})
        context["search_metrics"] = {
            "round_index": metrics.get("round_index", 0),
            "remaining_calls": metrics.get("remaining_calls", 0),
            "finalization_reserve_calls": metrics.get("finalization_reserve_calls", 0),
        }
    target_ids = context.get("target_obligation_ids")
    while (
        isinstance(target_ids, list)
        and len(target_ids) > 1
        and _context_chars(context) > max_chars
    ):
        target_ids.pop()
    if _context_chars(context) > max_chars:
        context.pop("cold_context_notice", None)
    return context


def build_inspiration_prompt_context(
    engine: Any,
    task: InspirationTask,
    *,
    snapshot: InspirationSnapshot,
    context_mode: InspirationContextMode,
    proposal_slot: int,
) -> dict[str, Any]:
    """Build a bounded mechanism-specific context without leaking route prose."""

    config = engine.inspiration_config
    profile = _PROFILES[task.mechanism]
    max_chars = min(
        config.inspiration_context_max_chars,
        engine.config.topology.max_context_chars,
    )
    target_ids = set(task.target_obligation_ids)
    targets = []
    for obligation_id in task.target_obligation_ids:
        try:
            targets.append(
                engine.proof_graph.get_obligation(obligation_id).model_dump(mode="json")
            )
        except KeyError:
            continue
    query = " ".join(
        [
            engine.problem.normalized_statement,
            task.reason,
            *(str(item.get("statement", "")) for item in targets),
        ]
    )
    normalized_signatures = [
        engine.mechanism_normalizer.normalize_signature(item)
        for item in snapshot.route_signatures
    ]
    forbidden = list(
        dict.fromkeys(
            tag
            for signature in normalized_signatures
            for tag in (
                *signature.representation_tags,
                *signature.mechanism_tags,
                *signature.key_transformations,
                *signature.proof_principles,
            )
        )
    )[:32]
    contract = {
        "proposal_slot": proposal_slot,
        "context_mode": context_mode.value,
        "diversity_axis": _SLOT_DIRECTIVES[proposal_slot % len(_SLOT_DIRECTIVES)],
        "must_target_open_obligation": True,
        "must_include_fast_failure_test": True,
        "forbidden_existing_mechanisms": forbidden,
    }
    compact_metrics = {
        "round_index": snapshot.round_index,
        "trigger_reason": task.reason,
        "target_route_ids": list(task.target_route_ids),
        "stagnation_rounds": {
            route_id: snapshot.stagnation_rounds_by_route.get(route_id, 0)
            for route_id in task.target_route_ids
        },
        "proof_debt": {
            route_id: snapshot.proof_debt_by_route.get(route_id, 0.0)
            for route_id in task.target_route_ids
        },
        "repeated_first_errors": list(dict.fromkeys(snapshot.first_error_fingerprints))[
            :8
        ],
        "shared_bottleneck_ids": list(snapshot.shared_bottleneck_ids)[:8],
        "remaining_calls": snapshot.remaining_calls,
        "finalization_reserve_calls": snapshot.finalization_reserve_calls,
    }
    base = {
        "context_mode": context_mode.value,
        "generation_contract": contract,
        "target_obligations": targets,
        "target_obligation_ids": list(task.target_obligation_ids),
        "search_metrics": compact_metrics,
        "proof_graph": {},
        "verified_facts": [],
        "negative_memory": [],
        "route_novelty_signatures": [],
    }
    if context_mode == InspirationContextMode.COLD:
        base["cold_context_notice"] = (
            "Existing route proof prose is intentionally withheld to reduce anchoring. "
            "The forbidden list prevents mechanism duplication but is not a suggested route."
        )
        return _enforce_context_budget(base, max_chars=max_chars)

    fact_limit = min(profile.fact_limit, config.warm_context_max_facts)
    negative_limit = min(profile.negative_limit, config.warm_context_max_negatives)
    if engine.broker is not None and fact_limit:
        base["verified_facts"] = select_typed_fact_context(
            engine.broker.admitted_facts(),
            broker=engine.broker,
            query=query,
            max_chars=max(1000, int(max_chars * 0.65)),
            max_items=fact_limit,
            purpose=ContextPurpose.INSPIRATION,
            artifact_store=engine.store,
        )
    base["negative_memory"] = _select_negatives(
        list(engine.typed_memory.negatives),
        query=query,
        target_obligation_ids=target_ids,
        max_items=negative_limit,
        max_chars=max(500, int(max_chars * 0.25)),
    )
    if profile.include_graph:
        base["proof_graph"] = engine.proof_graph.minimal_subgraph(
            task.target_obligation_ids
        )
    if profile.include_route_signatures:
        relevant = [
            signature
            for signature in normalized_signatures
            if not target_ids
            or target_ids.intersection(signature.targeted_obligation_ids)
        ]
        base["route_novelty_signatures"] = [
            _compact_signature(item) for item in (relevant or normalized_signatures)[:8]
        ]
    return _enforce_context_budget(base, max_chars=max_chars)


__all__ = ["build_inspiration_prompt_context"]
