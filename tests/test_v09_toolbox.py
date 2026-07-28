from __future__ import annotations

from typing import Any

import pytest

from mathproofmesh.computation.broker import ToolBroker
from mathproofmesh.computation.contracts import (
    experiment_tool_catalog,
    validate_experiment_contract,
)
from mathproofmesh.computation.handlers.geometry import run_exact_geometry
from mathproofmesh.computation.handlers.number_theory import run_number_theory
from mathproofmesh.computation.handlers.real_inequality import run_real_inequality
from mathproofmesh.computation.policy import ComputationContext, ComputationGate
from mathproofmesh.schemas import (
    ComputationDecisionStatus,
    ComputationMethod,
    EvidenceStrength,
    ExperimentOutcome,
    ExperimentSpec,
)


def _spec(
    method: str,
    *,
    target: str = "The declared bounded computation checks one precise claim.",
    arguments: dict[str, Any] | None = None,
    domains: dict[str, Any] | None = None,
    purpose: str = "falsify_claim",
    max_cases: int = 1000,
) -> ExperimentSpec:
    return ExperimentSpec(
        purpose=purpose,
        target_claim=target,
        assumptions=["All variables lie in the declared domain."],
        reasoning_basis=(
            "The proposed statement is a precise intermediate lemma whose "
            "failure changes the proof route."
        ),
        why_computation_is_needed=(
            "The declared bounded check is faster and less error-prone than "
            "manual substitution."
        ),
        decision_if_confirmed=(
            "Continue the abstract proof citing only the declared scope."
        ),
        decision_if_refuted="Remove the false lemma and repair dependent steps.",
        noncomputational_alternative=(
            "Derive the same conclusion symbolically if a short argument exists."
        ),
        method=method,
        domains=domains or {},
        arguments=arguments or {},
        exact_arithmetic=True,
        max_cases=max_cases,
        seed=20260719,
    )


def _enabled_broker(demo_config, artifact_store) -> ToolBroker:
    config = demo_config.model_copy(deep=True)
    config.computation.enabled = True
    return ToolBroker(config, artifact_store)


def _context(path_id: str) -> ComputationContext:
    return ComputationContext(
        path_id=path_id,
        stalled_rounds=1,
        meta_review_approved=True,
        remaining_llm_calls=20,
    )


# ---------------------------------------------------------------------------
# real_inequality
# ---------------------------------------------------------------------------


def test_real_inequality_certifies_amgm_square_over_all_reals(
    demo_config, artifact_store
) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    spec = _spec(
        "real_inequality",
        target="For all real x and y, x squared plus y squared is at least 2xy.",
        arguments={"lhs": "x^2 + y^2", "rhs": "2*x*y", "relation": "ge"},
    )

    decision = broker.decide(spec, _context("real-ineq-prove"))
    assert decision.decision == ComputationDecisionStatus.ALLOW
    result = broker.run_experiment(spec, decision)

    assert result.outcome == ExperimentOutcome.CERTIFIED
    assert result.evidence_strength == EvidenceStrength.FORMAL_CERTIFICATE
    assert result.certificate["solver_status"] == "unsat"
    assert result.certificate["logic"] == "QF_NRA"
    assert result.exact_arithmetic is True
    assert any(
        "nonlinear-real-arithmetic" in note for note in result.verification_notes
    )


def test_real_inequality_refutes_on_open_interval_with_exact_confirmation(
    demo_config, artifact_store
) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    spec = _spec(
        "real_inequality",
        target="For every real x strictly between 0 and 1, x squared is at least x.",
        arguments={"lhs": "x^2", "rhs": "x", "relation": "ge"},
        domains={
            "x": {
                "min": 0,
                "max": 1,
                "min_exclusive": True,
                "max_exclusive": True,
            }
        },
    )

    result = broker.run_experiment(spec, broker.decide(spec, _context("real-ineq-cx")))

    assert result.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
    assert result.evidence_strength == EvidenceStrength.COUNTEREXAMPLE
    assert result.independently_verified is True
    witness = result.counterexample["assignment"]["x"]
    from fractions import Fraction

    value = Fraction(witness)
    assert 0 < value < 1
    assert value * value < value
    assert any(
        "exact SymPy rational arithmetic" in note for note in result.verification_notes
    )


def test_real_inequality_timeout_returns_inconclusive() -> None:
    spec = _spec(
        "real_inequality",
        target="A degree-eight four-variable AM-GM inequality holds over the reals.",
        arguments={
            "lhs": "a^8 + b^8 + c^8 + d^8 + 4",
            "rhs": "8*a*b*c*d",
            "relation": "ge",
            "max_runtime_ms": 1,
        },
    )

    evidence = run_real_inequality(spec)

    assert evidence.outcome == ExperimentOutcome.INCONCLUSIVE
    assert evidence.evidence_strength == EvidenceStrength.HEURISTIC
    assert evidence.scope["solver_status"] == "unknown"


def test_real_inequality_rejects_unsupported_operations_cleanly() -> None:
    spec = _spec(
        "real_inequality",
        target="The sine function is bounded below by negative one everywhere.",
        arguments={"lhs": "sin(x)", "rhs": "-1", "relation": "ge"},
    )

    evidence = run_real_inequality(spec)

    assert evidence.outcome == ExperimentOutcome.INCONCLUSIVE
    assert evidence.evidence_strength == EvidenceStrength.HEURISTIC
    assert any("Unsupported operation" in note for note in evidence.verification_notes)


def test_real_inequality_contract_accepts_domains_and_rejects_unknown_args() -> None:
    valid = _spec(
        "real_inequality",
        arguments={
            "lhs": "x^2",
            "rhs": "x",
            "relation": "ge",
            "max_runtime_ms": 2000,
        },
        domains={"x": {"min": "1/2", "max": 2, "positive": True}},
    )
    assert validate_experiment_contract(valid) == []

    invalid = _spec(
        "real_inequality",
        arguments={"lhs": "x^2", "invented_control_field": True},
        domains={"x": {"lower": 0}},
    )
    issues = validate_experiment_contract(invalid)
    assert "unsupported arguments: invented_control_field" in issues
    assert any("unsupported keys: lower" in issue for issue in issues)


# ---------------------------------------------------------------------------
# number_theory_check
# ---------------------------------------------------------------------------


def test_multiplicative_order_is_certified_for_the_finite_assertion(
    demo_config, artifact_store
) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    spec = _spec(
        "number_theory_check",
        target="The multiplicative order of 2 modulo 11 equals exactly ten.",
        arguments={
            "operation": "multiplicative_order",
            "a": 2,
            "n": 11,
            "claimed": 10,
        },
    )

    result = broker.run_experiment(spec, broker.decide(spec, _context("nt-order")))

    assert result.outcome == ExperimentOutcome.CERTIFIED
    assert result.evidence_strength == EvidenceStrength.FORMAL_CERTIFICATE
    assert result.certificate["order"] == 10
    assert result.certificate["statement"] == "ord_11(2) = 10"
    assert result.independently_verified is True
    assert any(
        "cannot certify any infinite generalization" in note
        for note in result.verification_notes
    )


def test_crt_solves_and_independently_verifies_the_system(
    demo_config, artifact_store
) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    spec = _spec(
        "number_theory_check",
        target="The system x = 2 mod 3 and x = 3 mod 5 has solution 8 mod 15.",
        arguments={
            "operation": "crt",
            "residues": [2, 3],
            "moduli": [3, 5],
            "claimed": 8,
        },
    )

    result = broker.run_experiment(spec, broker.decide(spec, _context("nt-crt")))

    assert result.outcome == ExperimentOutcome.CERTIFIED
    assert result.evidence_strength == EvidenceStrength.FORMAL_CERTIFICATE
    assert result.certificate["solution"] == 8
    assert result.certificate["combined_modulus"] == 15
    assert result.independently_verified is True


def test_crt_certifies_unsolvable_systems_with_an_explicit_witness() -> None:
    spec = _spec(
        "number_theory_check",
        target="The congruence system x = 1 mod 4 and x = 2 mod 6 has no solution.",
        arguments={"operation": "crt", "residues": [1, 2], "moduli": [4, 6]},
    )

    evidence = run_number_theory(spec)

    assert evidence.outcome == ExperimentOutcome.CERTIFIED
    assert evidence.certificate["solvable"] is False
    assert evidence.certificate["inconsistency_witness"]["gcd"] == 2


def test_p_adic_valuation_examples_and_claim_refutation() -> None:
    direct = _spec(
        "number_theory_check",
        target="The 2-adic valuation of forty-eight equals exactly four.",
        arguments={"operation": "p_adic_valuation", "p": 2, "expression": "48"},
    )
    evidence = run_number_theory(direct)
    assert evidence.outcome == ExperimentOutcome.CERTIFIED
    assert evidence.certificate["valuation"] == 4

    at_point = _spec(
        "number_theory_check",
        target="The 3-adic valuation of n squared minus one at n=10 equals two.",
        arguments={
            "operation": "p_adic_valuation",
            "p": 3,
            "expression": "n^2 - 1",
            "assignment": {"n": 10},
            "claimed": 2,
        },
    )
    at_point_evidence = run_number_theory(at_point)
    assert at_point_evidence.outcome == ExperimentOutcome.CERTIFIED
    assert at_point_evidence.certificate["value"] == 99
    assert at_point_evidence.certificate["valuation"] == 2

    wrong_claim = _spec(
        "number_theory_check",
        target="The 3-adic valuation of ninety-nine is claimed to equal three.",
        arguments={
            "operation": "p_adic_valuation",
            "p": 3,
            "expression": "99",
            "claimed": 3,
        },
    )
    refuted = run_number_theory(wrong_claim)
    assert refuted.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
    assert refuted.evidence_strength == EvidenceStrength.COUNTEREXAMPLE
    assert refuted.counterexample["valuation"] == 2
    assert refuted.independently_verified is True


def test_primitive_root_existence_and_nonexistence_are_certified() -> None:
    exists = run_number_theory(
        _spec(
            "number_theory_check",
            target="A primitive root exists modulo eleven and one is exhibited.",
            arguments={"operation": "primitive_root", "n": 11},
        )
    )
    assert exists.outcome == ExperimentOutcome.CERTIFIED
    assert exists.certificate["exists"] is True
    assert exists.certificate["primitive_root"] == 2

    missing = run_number_theory(
        _spec(
            "number_theory_check",
            target="No primitive root exists modulo eight by the structure theorem.",
            arguments={"operation": "primitive_root", "n": 8},
        )
    )
    assert missing.outcome == ExperimentOutcome.CERTIFIED
    assert missing.certificate["exists"] is False

    claimed_root = run_number_theory(
        _spec(
            "number_theory_check",
            target="Three is claimed to be a primitive root modulo eleven.",
            arguments={"operation": "primitive_root", "n": 11, "claimed": 3},
        )
    )
    assert claimed_root.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND


def test_is_prime_and_factorization_with_guard_bounds() -> None:
    carmichael = run_number_theory(
        _spec(
            "number_theory_check",
            target="The Carmichael number five hundred sixty-one is not prime.",
            arguments={"operation": "is_prime", "n": 561},
        )
    )
    assert carmichael.outcome == ExperimentOutcome.CERTIFIED
    assert carmichael.certificate["is_prime"] is False

    factored = run_number_theory(
        _spec(
            "number_theory_check",
            target="Three hundred sixty factors exactly as two cubed times nine times five.",
            arguments={
                "operation": "factorization",
                "n": 360,
                "claimed": {"2": 3, "3": 2, "5": 1},
            },
        )
    )
    assert factored.outcome == ExperimentOutcome.CERTIFIED
    assert factored.certificate["factors"] == {"2": 3, "3": 2, "5": 1}

    oversized = run_number_theory(
        _spec(
            "number_theory_check",
            target="Ten to the thirteenth power exceeds the factorization guard bound.",
            arguments={"operation": "factorization", "n": 10**13},
        )
    )
    assert oversized.outcome == ExperimentOutcome.INCONCLUSIVE
    assert oversized.evidence_strength == EvidenceStrength.HEURISTIC
    assert any("guard bound" in note for note in oversized.verification_notes)


# ---------------------------------------------------------------------------
# exact_geometry extensions
# ---------------------------------------------------------------------------


def test_concyclic_holds_for_unit_circle_and_fails_when_perturbed(
    demo_config, artifact_store
) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    cyclic = _spec(
        "exact_geometry",
        target="The four declared rational points lie on one common circle.",
        arguments={
            "points": {
                "a": [1, 0],
                "b": [0, 1],
                "c": [-1, 0],
                "d": ["3/5", "-4/5"],
            },
            "assertion": {
                "kind": "concyclic",
                "points": ["a", "b", "c", "d"],
                "expected": True,
            },
        },
    )
    result = broker.run_experiment(cyclic, broker.decide(cyclic, _context("geom-con")))
    assert result.outcome == ExperimentOutcome.CERTIFIED
    assert result.evidence_strength == EvidenceStrength.FORMAL_CERTIFICATE
    assert result.exact_arithmetic is True

    perturbed = _spec(
        "exact_geometry",
        target="The perturbed fourth point breaks the claimed concyclicity.",
        arguments={
            "points": {
                "a": [1, 0],
                "b": [0, 1],
                "c": [-1, 0],
                "d": ["3/5", "-401/500"],
            },
            "assertion": {
                "kind": "concyclic",
                "points": ["a", "b", "c", "d"],
                "expected": True,
            },
        },
    )
    evidence = run_exact_geometry(perturbed)
    assert evidence.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
    assert evidence.evidence_strength == EvidenceStrength.COUNTEREXAMPLE
    assert evidence.counterexample["determinant"] != "0"


def test_concyclic_rejects_degenerate_collinear_quadruples() -> None:
    collinear = _spec(
        "exact_geometry",
        target="Four collinear points must not be certified as concyclic.",
        arguments={
            "points": {"a": [0, 0], "b": [1, 1], "c": [2, 2], "d": [3, 3]},
            "assertion": {
                "kind": "concyclic",
                "points": ["a", "b", "c", "d"],
                "expected": True,
            },
        },
    )

    evidence = run_exact_geometry(collinear)

    assert evidence.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
    assert evidence.counterexample["all_collinear"] is True


def test_parallel_perpendicular_and_equal_angle_assertions() -> None:
    parallel = run_exact_geometry(
        _spec(
            "exact_geometry",
            target="Segments AB and CD are parallel by exact cross product.",
            arguments={
                "points": {"a": [0, 0], "b": [1, 1], "c": [2, 0], "d": [3, 1]},
                "assertion": {"kind": "parallel", "points": ["a", "b", "c", "d"]},
            },
        )
    )
    assert parallel.outcome == ExperimentOutcome.CERTIFIED
    assert parallel.certificate["cross_product"] == "0"

    not_perpendicular = run_exact_geometry(
        _spec(
            "exact_geometry",
            target="Segments AB and CD are claimed perpendicular but are not.",
            arguments={
                "points": {"a": [0, 0], "b": [1, 1], "c": [2, 0], "d": [3, 1]},
                "assertion": {
                    "kind": "perpendicular",
                    "points": ["a", "b", "c", "d"],
                },
            },
        )
    )
    assert not_perpendicular.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND

    perpendicular = run_exact_geometry(
        _spec(
            "exact_geometry",
            target="A horizontal and a vertical segment are exactly perpendicular.",
            arguments={
                "points": {"a": [0, 0], "b": [1, 0], "c": [5, 5], "d": [5, 6]},
                "assertion": {
                    "kind": "perpendicular",
                    "points": ["a", "b", "c", "d"],
                },
            },
        )
    )
    assert perpendicular.outcome == ExperimentOutcome.CERTIFIED
    assert perpendicular.certificate["dot_product"] == "0"

    equal_angle = run_exact_geometry(
        _spec(
            "exact_geometry",
            target="Two right angles at distinct vertices are exactly equal.",
            arguments={
                "points": {
                    "a": [1, 0],
                    "b": [0, 0],
                    "c": [0, 1],
                    "d": [1, 1],
                    "e": [0, 0],
                    "f": [-1, 1],
                },
                "assertion": {
                    "kind": "equal_angle",
                    "points": ["a", "b", "c", "d", "e", "f"],
                },
            },
        )
    )
    assert equal_angle.outcome == ExperimentOutcome.CERTIFIED

    unequal_angle = run_exact_geometry(
        _spec(
            "exact_geometry",
            target="A right angle is claimed equal to a forty-five degree angle.",
            arguments={
                "points": {
                    "a": [1, 0],
                    "b": [0, 0],
                    "c": [0, 1],
                    "d": [1, 0],
                    "e": [0, 0],
                    "f": [1, 1],
                },
                "assertion": {
                    "kind": "equal_angle",
                    "points": ["a", "b", "c", "d", "e", "f"],
                },
            },
        )
    )
    assert unequal_angle.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND


# ---------------------------------------------------------------------------
# registration: gate, catalog, contracts
# ---------------------------------------------------------------------------


def test_new_methods_are_registered_in_gate_catalog_and_contracts() -> None:
    assert ComputationMethod.REAL_INEQUALITY in ComputationGate._TYPED_METHODS
    assert ComputationMethod.NUMBER_THEORY_CHECK in ComputationGate._TYPED_METHODS
    assert (
        ComputationMethod.REAL_INEQUALITY
        not in ComputationGate._BOUNDED_TYPED_PROBE_METHODS
    )

    catalog = {entry["method"]: entry for entry in experiment_tool_catalog()}
    assert "real_inequality" in catalog
    assert "number_theory_check" in catalog
    assert catalog["real_inequality"]["required_arguments"] == ["lhs"]
    assert catalog["number_theory_check"]["required_arguments"] == ["operation"]
    assert any(
        "equal_angle" in constraint
        for constraint in catalog["exact_geometry"]["constraints"]
    )

    filtered = experiment_tool_catalog(["real_inequality"])
    assert [entry["method"] for entry in filtered] == ["real_inequality"]


@pytest.mark.parametrize(
    ("arguments", "expected_issue_fragment"),
    [
        ({"operation": "unknown_op"}, "operation must be one of"),
        (
            {"operation": "crt", "residues": [1], "moduli": [2, 3]},
            "same length",
        ),
        (
            {"operation": "multiplicative_order", "a": 2, "n": "eleven"},
            "n must be an integer",
        ),
    ],
)
def test_number_theory_contract_rejects_malformed_requests(
    arguments: dict[str, Any],
    expected_issue_fragment: str,
) -> None:
    issues = validate_experiment_contract(
        _spec("number_theory_check", arguments=arguments)
    )
    assert any(expected_issue_fragment in issue for issue in issues)


def test_number_theory_rejects_domains_and_unknown_arguments() -> None:
    with_domains = _spec(
        "number_theory_check",
        arguments={"operation": "is_prime", "n": 7},
        domains={"n": {"min": 0, "max": 10}},
    )
    assert (
        "number_theory_check does not accept domains"
        in validate_experiment_contract(with_domains)
    )

    unknown = _spec(
        "number_theory_check",
        arguments={"operation": "is_prime", "n": 7, "invented_control_field": 1},
    )
    assert validate_experiment_contract(unknown) == [
        "unsupported arguments: invented_control_field"
    ]
