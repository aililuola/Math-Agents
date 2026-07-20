from __future__ import annotations

import pytest
from pydantic import ValidationError

from mathproofmesh.schemas import QuantifierSpec, VariableBinding

from v07_helpers import make_message


def test_mutable_delivery_metadata_does_not_change_content_hash() -> None:
    original = make_message(
        message_id="msg-a",
        route_id="route-a",
        agent_id="author-a",
        target_routes=["route-b"],
    )
    payload = original.model_dump(mode="python")
    payload.update(
        {
            "message_id": "msg-b",
            "target_route_ids": ["route-c"],
            "verification_confidence": 0.77,
            "created_at": "2099-01-01T00:00:00+00:00",
            "content_hash": "",
        }
    )
    mutated = type(original).model_validate(payload)
    assert mutated.content_hash == original.content_hash


def test_quantifier_order_is_hashed() -> None:
    bindings = [
        VariableBinding(
            variable_id="x", display_name="x", domain="Z", owner_scope="claim"
        ),
        VariableBinding(
            variable_id="y", display_name="y", domain="Z", owner_scope="claim"
        ),
    ]
    first = make_message(
        message_id="quantified-a",
        route_id="route-a",
        agent_id="author-a",
        quantifiers=[
            QuantifierSpec(
                order=0, kind="forall", variable_id="x", display_name="x", domain="Z"
            ),
            QuantifierSpec(
                order=1, kind="exists", variable_id="y", display_name="y", domain="Z"
            ),
        ],
        variable_bindings=bindings,
    )
    second = make_message(
        message_id="quantified-b",
        route_id="route-a",
        agent_id="author-a",
        quantifiers=[
            QuantifierSpec(
                order=0, kind="exists", variable_id="y", display_name="y", domain="Z"
            ),
            QuantifierSpec(
                order=1, kind="forall", variable_id="x", display_name="x", domain="Z"
            ),
        ],
        variable_bindings=bindings,
    )
    assert first.content_hash != second.content_hash


def test_quantified_variable_requires_one_consistent_binding() -> None:
    with pytest.raises(ValidationError, match="has no binding"):
        make_message(
            message_id="invalid-scope",
            route_id="route-a",
            agent_id="author-a",
            quantifiers=[
                QuantifierSpec(
                    order=0,
                    kind="forall",
                    variable_id="x",
                    display_name="x",
                    domain="Z",
                )
            ],
        )
