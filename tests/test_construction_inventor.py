from __future__ import annotations

from mathproofmesh.inspiration.construction_inventor import (
    AuxiliaryConstructionInventor,
)
from mathproofmesh.inspiration.invariant_hypothesis import InvariantHypothesisAgent
from mathproofmesh.inspiration.reverse_goal import ReverseGoalAnalyzer
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import ObligationKind, ProblemContract, ProofObligation


def _problem_and_obligation():
    problem = ProblemContract(
        exact_statement="Prove a combinatorial extremal statement.",
        normalized_statement="combinatorial extremal statement",
    )
    obligation = ProofObligation(
        problem_hash=problem.integrity_hash,
        route_ids=["route-a"],
        kind=ObligationKind.SUBGOAL,
        statement="control all incidences",
        normalized_statement="control all incidences",
    )
    return problem, obligation


def test_construction_has_definition_target_and_falsification_test() -> None:
    problem, obligation = _problem_and_obligation()
    inventor = AuxiliaryConstructionInventor()
    proposals = inventor.propose(
        problem, [obligation], domain="combinatorics", max_proposals=3
    )
    assert len(proposals) == 3
    for proposal in proposals:
        assert inventor.validate(proposal, [obligation.obligation_id]) == []
        assert proposal.expected_proof_debt_reduction
        assert proposal.failure_conditions
        assert proposal.operator_id
        assert proposal.operator_preconditions
        assert proposal.generated_obligations
        assert proposal.reversibility_requirements


def test_invariant_remains_a_falsifiable_hypothesis() -> None:
    problem, obligation = _problem_and_obligation()
    agent = InvariantHypothesisAgent()
    hypotheses = agent.propose(
        problem, [obligation], domain="combinatorics", max_proposals=2
    )
    assert len(hypotheses) == 2
    for hypothesis in hypotheses:
        assert agent.validate(hypothesis) == []
        assert hypothesis.allowed_operations
        assert hypothesis.boundary_case
        assert "skeptic" in hypothesis.falsification_request.casefold()


def test_reverse_goal_materializes_only_explicit_bridge_gaps() -> None:
    problem, obligation = _problem_and_obligation()
    graph = ProofGraphStore(problem_hash=problem.integrity_hash)
    graph.add_obligation(obligation)
    analyzer = ReverseGoalAnalyzer()
    plan = analyzer.analyze(obligation)
    created = analyzer.materialize(plan, graph)
    assert created
    assert len(created) == len(plan.bridge_requests)
    assert all(item.kind == ObligationKind.LEMMA for item in created)
    assert plan.novelty_signature.targeted_obligation_ids == [obligation.obligation_id]
