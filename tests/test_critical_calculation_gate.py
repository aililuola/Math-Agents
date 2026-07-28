from __future__ import annotations

from pathlib import Path

from mathproofmesh.computation.broker import ToolBroker
from mathproofmesh.critical_calculations import (
    CriticalCalculationGate,
    calculation_trigger,
)
from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.prompts import PromptFactory
from mathproofmesh.schemas import (
    CalculationGateVerdict,
    ProblemContract,
    ProofStep,
    StrategyCard,
    ToolRequest,
    TriageResult,
)
from mathproofmesh.store import ArtifactStore


def _gate(tmp_path: Path, run_id: str = "critical-calculation"):
    config = build_demo_config(str(tmp_path / "runs"))
    config.computation.enabled = True
    config.computation.typed_tools_enabled = True
    config.computation.critical_calculation_gate_enabled = True
    store = ArtifactStore(config.runtime.run_root, run_id)
    tools = ToolBroker(config, store)
    return config, store, CriticalCalculationGate(config, tools, store)


def _greedy_check(claimed_values: list[int]) -> ToolRequest:
    return ToolRequest(
        kind="bounded_greedy_sequence",
        purpose="The declared first five greedy-sequence values are exact.",
        arguments={
            "initial_values": [6],
            "length": 5,
            "candidate_min": 2,
            "candidate_max": 30,
            "rule": "gcd_overlap_all_prior",
            "claimed_values": claimed_values,
        },
        max_cases=1000,
    )


def test_high_precision_trigger_detects_explicit_computed_values() -> None:
    assert calculation_trigger(["The first terms are 15, 18, 21, 24."]) is not None
    assert calculation_trigger(["Use induction to prove a_n = a_1 + (n-1)d."]) is None
    assert calculation_trigger(["Test a candidate period after deriving it."]) is None
    assert calculation_trigger(["By AM-GM, the minimum is 3."]) is None
    assert (
        calculation_trigger(["The finite classification has cases n=0, n=1, and n=2."])
        is None
    )
    assert calculation_trigger(["Direct computation gives the minimum 3."]) is not None


def test_strategy_with_undeclared_numeric_premise_is_blocked(tmp_path: Path) -> None:
    _config, _store, gate = _gate(tmp_path, "missing-strategy-check")
    strategy = StrategyCard(
        strategy_id="numeric-route",
        title="Computed prefix route",
        core_idea="The first terms are 15, 18, 21, 24, so infer a recurrence.",
        independence_basis="A recurrence mechanism.",
        bottleneck="Prove the recurrence for every index.",
        falsification_test="Recompute the finite prefix.",
        estimated_success=0.4,
    )

    result = gate.evaluate_strategy(
        strategy,
        path_id="path-numeric-route",
        requested_by="planner",
    )

    assert result.passed is False
    assert result.failures[0].verdict == CalculationGateVerdict.MISSING_DECLARATION
    assert strategy.calculation_evidence_refs == []


def test_exact_declared_prefix_receives_certificate_without_model_call(
    tmp_path: Path,
) -> None:
    _config, store, gate = _gate(tmp_path, "passing-prefix-check")
    step = ProofStep(
        step_id="prefix",
        statement="The first five terms are 6, 8, 10, 12, 14.",
        justification="Apply the declared greedy rule successively.",
        calculations=["Generate the exact finite prefix."],
        calculation_checks=[_greedy_check([6, 8, 10, 12, 14])],
        is_key_step=True,
    )

    result = gate.evaluate_steps(
        [step],
        scope_type="proof_step",
        path_id="path-prefix",
        parent_checkpoint_id="checkpoint-parent",
        requested_by="explorer-a",
    )

    assert result.passed is True
    assert result.records[0].verdict == CalculationGateVerdict.PASSED
    assert step.calculation_evidence_refs
    assert store.list_experiment_results()


def test_wrong_declared_prefix_is_refuted_before_checkpoint_review(
    tmp_path: Path,
) -> None:
    _config, _store, gate = _gate(tmp_path, "refuted-prefix-check")
    step = ProofStep(
        step_id="bad-prefix",
        statement="The first five terms are 6, 8, 9, 12, 14.",
        justification="This was calculated by hand.",
        calculations=["Generate the exact finite prefix."],
        calculation_checks=[_greedy_check([6, 8, 9, 12, 14])],
        is_key_step=True,
    )

    result = gate.evaluate_steps(
        [step],
        scope_type="proof_step",
        path_id="path-bad-prefix",
        parent_checkpoint_id="checkpoint-parent",
        requested_by="explorer-a",
    )

    assert result.passed is False
    assert result.failures[0].verdict == CalculationGateVerdict.REFUTED
    assert "generated" in result.failures[0].reason
    assert step.calculation_evidence_refs == []


def test_generated_request_id_does_not_duplicate_the_calculation_node(
    tmp_path: Path,
) -> None:
    _config, _store, gate = _gate(tmp_path, "stable-calculation-node")
    first = ProofStep(
        step_id="prefix",
        statement="The first five terms are 6, 8, 10, 12, 14.",
        justification="Apply the declared greedy rule successively.",
        calculation_checks=[_greedy_check([6, 8, 10, 12, 14])],
    )
    second = ProofStep(
        step_id="prefix",
        statement=first.statement,
        justification=first.justification,
        calculation_checks=[_greedy_check([6, 8, 10, 12, 14])],
    )

    first_result = gate.evaluate_steps(
        [first],
        scope_type="proof_step",
        path_id="path-prefix",
        parent_checkpoint_id="checkpoint-parent",
        requested_by="explorer-a",
    )
    second_result = gate.evaluate_steps(
        [second],
        scope_type="proof_step",
        path_id="path-prefix",
        parent_checkpoint_id="checkpoint-parent",
        requested_by="explorer-a",
    )

    assert (
        first.calculation_checks[0].request_id
        != second.calculation_checks[0].request_id
    )
    assert (
        first_result.records[0].experiment_id == second_result.records[0].experiment_id
    )
    assert first_result.records[0].request_hash == second_result.records[0].request_hash
    assert (
        gate.tools.cache.resolve_identifier(first.calculation_checks[0].request_id)
        == first_result.records[0].request_hash
    )


def test_discover_pattern_request_uses_discovery_contract(
    tmp_path: Path,
) -> None:
    _config, store, gate = _gate(tmp_path, "discovery-contract-check")
    strategy = StrategyCard(
        strategy_id="strategy-candidate-period",
        title="Candidate period",
        core_idea="Use a finite exact check to propose a candidate period.",
        independence_basis="A finite periodicity probe.",
        bottleneck="Turn the finite observation into a universal argument.",
        falsification_test="Check the proposed period against the finite values.",
        estimated_success=0.4,
        calculation_checks=[
            ToolRequest(
                request_id="check-candidate-period",
                kind="candidate_period_check",
                purpose="discover_pattern",
                arguments={
                    "values": [2, 5, 2, 5],
                    "candidate_period": 2,
                    "start_index": 0,
                },
                max_cases=100,
            )
        ],
    )

    result = gate.evaluate_strategy(
        strategy,
        path_id="path-strategy-candidate-period",
        requested_by="explorer-a",
    )

    assert result.passed is True
    assert result.records[0].verdict == CalculationGateVerdict.PASSED
    assert store.list_experiment_results()


def test_result_only_symbolic_method_is_not_admitted_as_assertion_check(
    tmp_path: Path,
) -> None:
    _config, _store, gate = _gate(tmp_path, "result-only-method")
    step = ProofStep(
        step_id="factor",
        statement="The factorization is (x-1)(x+1).",
        justification="Direct factorization.",
        calculations=["Factor x^2-1."],
        calculation_checks=[
            ToolRequest(
                kind="polynomial_factor",
                purpose="Factor the polynomial x^2-1.",
                arguments={"expression": "x^2-1"},
            )
        ],
    )

    result = gate.evaluate_steps(
        [step],
        scope_type="proof_step",
        path_id="path-factor",
        parent_checkpoint_id=None,
        requested_by="explorer-a",
    )

    assert result.passed is False
    assert result.failures[0].verdict == CalculationGateVerdict.INVALID_CONTRACT
    assert "assertion-checking" in result.failures[0].reason


def test_prompts_require_checks_in_the_existing_model_call() -> None:
    problem = ProblemContract(
        exact_statement="Prove a finite identity.",
        normalized_statement="prove a finite identity",
        allowed_tools=["sympy_equivalent", "bounded_greedy_sequence"],
    )
    triage = TriageResult(
        problem_kind="proof",
        difficulty="medium",
        rationale="A short exact proof is expected.",
        confidence=0.8,
    )
    prompts = PromptFactory(computation_enabled=True)

    strategy_prompt = prompts.strategies(problem, triage, 2)
    exploration_prompt = prompts.explore(
        problem,
        strategy={},
        agent_id="explorer-a",
        round_index=0,
        verified_claims=[],
    )

    assert "strategy.calculation_checks" in strategy_prompt.user
    assert "REGISTERED TYPED CALCULATION CONTRACTS" in strategy_prompt.user
    assert "ProofStep.calculation_checks" in exploration_prompt.user
    assert "adds no model call" in exploration_prompt.user
