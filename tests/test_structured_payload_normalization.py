from __future__ import annotations

import pytest
from pydantic import ValidationError

from mathproofmesh.agents import StructuredAgentRunner
from mathproofmesh.schemas import (
    ClaimBatch,
    ComputationMethod,
    ContinuationTurn,
    InitialExplorationTurn,
    MetaStrategyDecision,
)


def _experiment_payload() -> dict[str, object]:
    return {
        "purpose": "discover_pattern",
        "target_claim": "Identify a candidate relation in a finite sample.",
        "reasoning_basis": "A bounded sample may suggest a route.",
        "why_computation_is_needed": "No candidate relation is known yet.",
        "decision_if_confirmed": "Submit the candidate for proof.",
        "decision_if_refuted": "Discard the candidate.",
        "noncomputational_alternative": "Continue symbolic exploration.",
        "method": ComputationMethod.BOUNDED_INTEGER_SEARCH.value,
        "domains": {"x": {"min": 0, "max": 4}},
        "arguments": {"expression": "x"},
        "exact_arithmetic": True,
        "broad_search": False,
        "max_cases": 5,
    }


def test_request_computation_repairs_only_server_owned_policy_fields() -> None:
    payload: dict[str, object] = {
        "action": "request_computation",
        "experiment_spec": _experiment_payload(),
        "experiment_impact": "execution",
        "reason": "Use a bounded exploratory sample.",
    }

    actions = StructuredAgentRunner._normalize_continuation_payload(
        payload,
        response_model=ContinuationTurn,
    )
    turn = ContinuationTurn.model_validate(payload)

    assert turn.experiment_impact is None
    assert turn.experiment_spec is not None
    assert turn.experiment_spec.broad_search is True
    assert actions == [
        "cleared premature experiment_impact for request_computation",
        "marked discover_pattern computation as broad_search",
    ]


def test_initial_computation_turn_uses_the_same_deterministic_normalization() -> None:
    payload: dict[str, object] = {
        "action": "request_computation",
        "experiment_spec": _experiment_payload(),
        "experiment_impact": "strategy",
        "reason": "Use a bounded exploratory sample.",
    }

    StructuredAgentRunner._normalize_continuation_payload(
        payload,
        response_model=InitialExplorationTurn,
    )
    turn = InitialExplorationTurn.model_validate(payload)

    assert turn.experiment_impact is None
    assert turn.experiment_spec is not None
    assert turn.experiment_spec.broad_search is True


def test_meta_strategy_action_alias_is_canonicalized_without_guessing() -> None:
    payload: dict[str, object] = {
        "round_index": 3,
        "action": "invent_auxiliary_construction",
        "affected_route_ids": ["route-a"],
        "selected_mechanism": "invent_auxiliary_construction",
        "reason": "A new object may expose the missing bridge.",
    }

    actions = StructuredAgentRunner._normalize_continuation_payload(
        payload,
        response_model=MetaStrategyDecision,
    )
    decision = MetaStrategyDecision.model_validate(payload)

    assert decision.selected_mechanism.value == "auxiliary_construction"
    assert actions == [
        "canonicalized selected_mechanism invent_auxiliary_construction "
        "to auxiliary_construction"
    ]


def test_unknown_meta_mechanism_is_not_replaced_by_a_generic_fallback() -> None:
    payload: dict[str, object] = {
        "round_index": 3,
        "action": "invent_auxiliary_construction",
        "affected_route_ids": ["route-a"],
        "selected_mechanism": "invent_untyped_object",
        "reason": "An untyped suggestion.",
    }

    actions = StructuredAgentRunner._normalize_continuation_payload(
        payload,
        response_model=MetaStrategyDecision,
    )

    assert actions == []
    with pytest.raises(ValidationError):
        MetaStrategyDecision.model_validate(payload)


def test_dependency_ref_aliases_use_payload_namespaces_and_are_audited() -> None:
    payload: dict[str, object] = {
        "attempt_id": "attempt-a",
        "claims": [
            {
                "claim_id": "claim-base",
                "statement": "The base relation holds.",
                "conclusion": "The base relation holds.",
                "proof_steps": [
                    {
                        "step_id": "step-base",
                        "statement": "Establish the base relation.",
                        "justification": "By the stated assumptions.",
                    }
                ],
            },
            {
                "claim_id": "claim-derived",
                "statement": "The derived relation holds.",
                "conclusion": "The derived relation holds.",
                "dependencies": ["claim-base"],
                "dependency_refs": [
                    {"kind": "external", "target_id": "claim-base"},
                    {"kind": "external", "target_id": "step-base"},
                    {
                        "kind": "external",
                        "target_id": "artifact://result/certificate.json",
                    },
                ],
            },
        ],
        "summary": "Two reusable relations were extracted.",
    }

    actions = StructuredAgentRunner._normalize_continuation_payload(
        payload,
        response_model=ClaimBatch,
    )
    batch = ClaimBatch.model_validate(payload)
    refs = batch.claims[1].dependency_refs

    assert [ref["kind"] for ref in refs] == [
        "local_claim",
        "local_step",
        "external_result",
    ]
    assert [ref["kind_migration"] for ref in refs] == [
        "legacy_external_to_local_claim",
        "legacy_external_to_local_step",
        "legacy_external_to_external_result",
    ]
    assert len(actions) == 3
    assert all("external to " in action for action in actions)


def test_model_cannot_supply_server_owned_dependency_migration_audit() -> None:
    payload = {
        "dependency_refs": [
            {
                "kind": "local_claim",
                "target_id": "claim-base",
                "kind_migration": "legacy_external_to_local_claim",
            }
        ]
    }

    StructuredAgentRunner._strip_server_owned_hashes(payload)

    assert "kind_migration" not in payload["dependency_refs"][0]
