from __future__ import annotations

import pytest

from mathproofmesh.proof_control.models import (
    ControlActionStatus,
    ControlActionType,
    ObligationDomain,
)
from mathproofmesh.proof_control.semantic_quality import ObligationSemanticGate
from mathproofmesh.schemas import ObligationKind, ProofObligation

from v07_helpers import PROBLEM_HASH
from v082_helpers import make_control_runtime, make_main_goal


def _subgoal(identifier: str, statement: str) -> ProofObligation:
    return ProofObligation(
        obligation_id=identifier,
        problem_hash=PROBLEM_HASH,
        route_ids=["route-a"],
        kind=ObligationKind.LEMMA,
        statement=statement,
        normalized_statement=statement.casefold(),
    )


def test_self_implication_obligation_rejected() -> None:
    goal = make_main_goal()
    obligation = _subgoal("bridge-self", goal.statement)

    quality = ObligationSemanticGate().assess(
        obligation,
        source_kind="strategy_blueprint",
        main_goal=goal,
        source_statement=goal.statement,
    )

    assert quality.is_self_implication
    assert not quality.accepted
    assert "self_implication" in quality.rejection_reasons


def test_internal_self_implication_obligation_rejected() -> None:
    obligation = _subgoal(
        "bridge-internal-self",
        (
            "If every canonical decomposition satisfies the compatibility relation, "
            "then every canonical decomposition satisfies the compatibility relation."
        ),
    )

    quality = ObligationSemanticGate().assess(
        obligation,
        source_kind="generated_bridge",
        main_goal=make_main_goal(),
    )

    assert quality.is_self_implication
    assert not quality.accepted


@pytest.mark.parametrize(
    "statement",
    [
        "P ⇒ P",
        "P ⇔ P",
        "P ↔ P",
        r"P \implies P",
        r"P \iff P",
        r"P \rightarrow P",
        r"P \leftrightarrow P",
        "P implies P",
        "P iff P",
        "P蕴含P",
        "P当且仅当P",
    ],
)
def test_symbolic_self_implication_obligation_rejected(statement: str) -> None:
    quality = ObligationSemanticGate().assess_statement(statement)

    assert quality.is_self_implication
    assert not quality.accepted
    assert "self_implication" in quality.rejection_reasons


def test_placeholder_search_text_not_mathematical_obligation() -> None:
    obligation = _subgoal("search-placeholder", "Find a suitable invariant.")

    quality = ObligationSemanticGate().assess(
        obligation,
        source_kind="strategy_blueprint",
        main_goal=make_main_goal(),
    )

    assert quality.domain == ObligationDomain.SEARCH
    assert quality.is_placeholder
    assert not quality.accepted


def test_truth_apt_subgoal_enters_graph() -> None:
    obligation = _subgoal(
        "lemma-concrete",
        "Every canonical decomposition satisfies the compatibility relation.",
    )

    quality = ObligationSemanticGate().assess(
        obligation,
        source_kind="strategy_blueprint",
        main_goal=make_main_goal(),
        executable_first_step="Fix an arbitrary canonical decomposition.",
    )

    assert quality.domain == ObligationDomain.MATHEMATICAL
    assert quality.truth_apt
    assert quality.has_explicit_objects
    assert quality.has_executable_first_step
    assert quality.accepted


def test_main_goal_copy_not_counted_as_bridge() -> None:
    goal = make_main_goal()
    copy = _subgoal("bridge-copy", goal.normalized_statement)

    quality = ObligationSemanticGate().assess(
        copy,
        source_kind="generated_bridge",
        main_goal=goal,
        executable_first_step="Restate the goal.",
    )

    assert quality.duplicates_main_goal
    assert not quality.accepted


def test_invalid_obligation_is_quarantined_not_clusterable() -> None:
    obligation = _subgoal("placeholder-cluster", "Complete the argument.")
    gate = ObligationSemanticGate()

    quality = gate.assess(
        obligation,
        source_kind="generated_bridge",
        main_goal=make_main_goal(),
    )

    assert not quality.accepted
    assert quality.semantic_quarantine
    assert not quality.eligible_for_core_debt
    assert not quality.eligible_for_bottleneck


def test_invalid_generated_obligation_is_quarantined_before_graph_write(
    tmp_path,
) -> None:
    *_runtime, control, main_goal = make_control_runtime(tmp_path)
    obligation = _subgoal("placeholder-action", "Complete the argument.")
    action = control.action_dispatcher.propose(
        ControlActionType.CREATE_SUB_OBLIGATION,
        target_obligation_ids=[main_goal.obligation_id],
        payload={
            "parent_main_goal_id": main_goal.obligation_id,
            "source_kind": "strategy",
            "obligation": obligation.model_dump(mode="json"),
        },
    )

    executed = control.action_dispatcher.execute_sync(
        action.action_id,
        current_round=0,
    )

    assert executed.status == ControlActionStatus.FAILED
    assert obligation.obligation_id in control.state.semantic_quarantine
    assert all(
        item.obligation_id != obligation.obligation_id
        for item in control.proof_graph.obligations
    )


def test_truth_apt_generated_subgoal_enters_graph(tmp_path) -> None:
    *_runtime, control, main_goal = make_control_runtime(tmp_path)
    obligation = _subgoal(
        "concrete-action",
        "Every canonical decomposition satisfies the compatibility relation.",
    )
    action = control.action_dispatcher.propose(
        ControlActionType.CREATE_SUB_OBLIGATION,
        target_obligation_ids=[main_goal.obligation_id],
        payload={
            "parent_main_goal_id": main_goal.obligation_id,
            "source_kind": "strategy",
            "executable_first_step": (
                "Fix an arbitrary canonical decomposition and expand its definition."
            ),
            "obligation": obligation.model_dump(mode="json"),
        },
    )

    executed = control.action_dispatcher.execute_sync(
        action.action_id,
        current_round=0,
    )

    assert executed.status == ControlActionStatus.EXECUTED
    materialized = control.proof_graph.get_obligation(obligation.obligation_id)
    assert materialized.normalized_statement == obligation.normalized_statement
    assert control.state.obligation_semantic_quality[obligation.obligation_id].accepted
