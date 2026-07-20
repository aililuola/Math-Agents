from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from mathproofmesh.computation.broker import ToolBroker
from mathproofmesh.computation.policy import ComputationContext
from mathproofmesh.computation.sandbox import (
    UnsafeProgramError,
    _find_docker_executable,
    _validate_json_object,
    _validate_program_schemas,
    build_docker_command,
    validate_program_source,
)
from mathproofmesh.computation.handlers.symbolic import (
    parse_expression,
    relation_holds_exact,
)
from mathproofmesh.config import ComputationConfig, ContinuationConfig
from mathproofmesh.mock_demo import build_demo_config, demo_responder
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.schemas import (
    AttemptStatus,
    ComputationDecisionStatus,
    ContinuationTurn,
    EvidenceStrength,
    ExperimentOutcome,
    ExperimentProgram,
    ExperimentResult,
    ExperimentSpec,
    ProofAttempt,
    ProofStep,
    VerificationReport,
    stable_hash,
)


def _spec(
    method: str,
    *,
    target: str = "For every integer x in the declared domain, x squared equals x.",
    arguments: dict[str, Any] | None = None,
    domains: dict[str, Any] | None = None,
    purpose: str = "falsify_claim",
    broad_search: bool = False,
    exact_arithmetic: bool = True,
    max_cases: int = 1000,
) -> ExperimentSpec:
    return ExperimentSpec(
        purpose=purpose,
        target_claim=target,
        assumptions=["All variables lie in the declared domain."],
        reasoning_basis=(
            "The proposed universal statement is a precise intermediate lemma whose failure changes the proof route."
        ),
        why_computation_is_needed=(
            "The declared finite check is faster and less error-prone than manual substitution."
        ),
        decision_if_confirmed="Continue the abstract proof without treating the check as proof.",
        decision_if_refuted="Remove the false lemma and repair every dependent step.",
        noncomputational_alternative="Derive the same conclusion symbolically if a short argument is found.",
        method=method,
        domains=domains or {},
        arguments=arguments or {},
        exact_arithmetic=exact_arithmetic,
        broad_search=broad_search,
        max_cases=max_cases,
        seed=20260719,
    )


def _enabled_broker(demo_config, artifact_store) -> ToolBroker:
    config = demo_config.model_copy(deep=True)
    config.computation.enabled = True
    return ToolBroker(config, artifact_store)


def test_exact_relation_and_impact_classification_fail_closed() -> None:
    with pytest.raises(ValueError, match="undecidable"):
        relation_holds_exact(parse_expression("x"), parse_expression("0"), "eq")
    with pytest.raises(ValueError, match="execution, plan, or strategy"):
        ContinuationTurn(action="abandon", experiment_impact="none")


def test_gate_rejects_vague_initial_search_and_fast_tracks_precise_refutation(
    demo_config, artifact_store
) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    context = ComputationContext(path_id="path-a", remaining_llm_calls=5)
    vague = _spec(
        "modular_exhaustive",
        target="枚举看看规律",
        purpose="discover_pattern",
        broad_search=True,
        arguments={"lhs": "x", "rhs": "x", "modulus": 5},
    )
    precise = _spec(
        "modular_exhaustive",
        arguments={"lhs": "x^2", "rhs": "x", "modulus": 5},
        domains={"x": {"min": 0, "max": 4}},
    )

    vague_decision = broker.decide(vague, context)
    precise_decision = broker.decide(precise, context)

    assert vague_decision.decision == ComputationDecisionStatus.REJECT
    assert vague_decision.rule_id == "request.vague_target"
    assert precise_decision.decision == ComputationDecisionStatus.ALLOW
    assert precise_decision.rule_id == "fast_path.targeted_falsification"


def test_gate_rejects_missing_decision_use_and_false_precision_claim(
    demo_config, artifact_store
) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    context = ComputationContext(path_id="gate-details", remaining_llm_calls=5)
    no_decision_use = _spec(
        "modular_exhaustive",
        arguments={"lhs": "x", "rhs": "x", "modulus": 5},
    )
    no_decision_payload = no_decision_use.model_dump(
        mode="json", exclude={"request_hash"}
    )
    no_decision_payload["decision_if_refuted"] = "stop"
    no_decision_use = ExperimentSpec.model_validate(no_decision_payload)
    false_precision = _spec(
        "numeric_counterexample",
        arguments={
            "lhs": "x^2",
            "rhs": "x",
            "variables": ["x"],
            "ranges": {"x": [0, 2]},
        },
        exact_arithmetic=True,
    )

    assert broker.decide(no_decision_use, context).rule_id == "request.no_decision_use"
    assert (
        broker.decide(false_precision, context).rule_id
        == "request.invalid_precision_claim"
    )


def test_broad_search_requires_stall_and_meta_review(
    demo_config, artifact_store
) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    spec = _spec(
        "bounded_integer_search",
        target="Determine whether a structured family contains a violating integer tuple.",
        purpose="discover_pattern",
        broad_search=True,
        domains={"x": {"min": 0, "max": 10}},
        arguments={"target": {"lhs": "x", "rhs": "x", "relation": "eq"}},
    )

    initial = broker.decide(
        spec, ComputationContext(path_id="path-a", remaining_llm_calls=5)
    )
    stalled = broker.decide(
        spec,
        ComputationContext(path_id="path-a", stalled_rounds=1, remaining_llm_calls=5),
    )
    approved = broker.decide(
        spec,
        ComputationContext(
            path_id="path-a",
            stalled_rounds=1,
            meta_review_approved=True,
            remaining_llm_calls=5,
        ),
    )

    assert initial.decision == ComputationDecisionStatus.DEFER
    assert initial.rule_id == "reasoning_first.not_stalled"
    assert stalled.decision == ComputationDecisionStatus.DEFER
    assert stalled.rule_id == "reasoning_first.meta_required"
    assert approved.decision == ComputationDecisionStatus.ALLOW


def test_not_refuted_is_never_promoted_to_verified(demo_config, artifact_store) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    spec = _spec(
        "numeric_counterexample",
        target="For every sampled real x, x squared is nonnegative.",
        arguments={
            "lhs": "x^2",
            "rhs": "0",
            "relation": "ge",
            "variables": ["x"],
            "ranges": {"x": [-5, 5]},
            "samples": 20,
        },
        exact_arithmetic=False,
    )
    decision = broker.decide(
        spec, ComputationContext(path_id="path-a", remaining_llm_calls=5)
    )
    result = broker.run_experiment(spec, decision)

    assert result.outcome == ExperimentOutcome.NOT_REFUTED
    assert result.evidence_strength == EvidenceStrength.HEURISTIC
    assert result.independently_verified is False


def test_counterexample_is_rechecked_cached_and_reused_after_resume(
    demo_config, artifact_store
) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    spec = _spec(
        "numeric_counterexample",
        arguments={
            "lhs": "x^2",
            "rhs": "x",
            "relation": "eq",
            "variables": ["x"],
            "ranges": {"x": [10, 10]},
            "samples": 3,
        },
        exact_arithmetic=False,
    )
    context = ComputationContext(path_id="path-a", remaining_llm_calls=5)
    decision = broker.decide(spec, context)
    result = broker.run_experiment(spec, decision)

    assert result.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
    assert result.independently_verified is True
    assert result.counterexample["assignment"]["x"] == "10"
    assert broker.ledger.count_for_path("path-a") == 1
    assert broker.audit_key_results()[0]["valid"] is True

    resumed = ToolBroker(broker.config, artifact_store)
    cached_decision = resumed.decide(spec, context)
    cached = resumed.run_experiment(spec, cached_decision)
    assert cached_decision.cache_hit is True
    assert cached.cached is True
    assert cached.result_hash == result.result_hash
    assert resumed.ledger.count_for_path("path-a") == 1

    experiment_dir = artifact_store.root / "experiments" / spec.request_hash
    assert (experiment_dir / "spec.json").exists()
    assert (experiment_dir / "decision.json").exists()
    assert (experiment_dir / "execution.json").exists()
    assert (experiment_dir / "result.json").exists()
    assert (experiment_dir / "evidence.json").exists()
    execution = broker.cache.load_execution(spec.request_hash)
    assert execution["input_hash"] == stable_hash(execution["input"])
    assert execution["output_hash"] == stable_hash(execution["output"])
    assert execution["environment_hash"] == stable_hash(execution["environment"])
    assert execution["result_hash"] == result.result_hash


def test_semantic_cache_preserves_canonical_artifacts_and_reuses_across_paths(
    demo_config, artifact_store
) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    arguments = {
        "lhs": "x^2",
        "rhs": "x",
        "relation": "eq",
        "variables": ["x"],
        "ranges": {"x": [2, 2]},
        "samples": 1,
    }
    first_spec = _spec(
        "numeric_counterexample", arguments=arguments, exact_arithmetic=False
    )
    first_context = ComputationContext(path_id="cache-a", remaining_llm_calls=5)
    first = broker.run_experiment(first_spec, broker.decide(first_spec, first_context))

    no_budget_spec = _spec(
        "numeric_counterexample", arguments=arguments, exact_arithmetic=False
    )
    no_budget = broker.decide(
        no_budget_spec,
        ComputationContext(path_id="cache-no-budget", remaining_llm_calls=0),
    )
    assert no_budget.decision == ComputationDecisionStatus.DEFER
    assert no_budget.rule_id == "budget.no_interpretation_call"

    equivalent_spec = _spec(
        "numeric_counterexample", arguments=arguments, exact_arithmetic=False
    )
    second_context = ComputationContext(path_id="cache-b", remaining_llm_calls=5)
    cached_decision = broker.decide(equivalent_spec, second_context)
    reused = broker.run_experiment(equivalent_spec, cached_decision)

    assert equivalent_spec.experiment_id != first_spec.experiment_id
    assert equivalent_spec.request_hash == first_spec.request_hash
    assert cached_decision.cache_hit is True
    assert reused.cached is True
    assert reused.path_id == "cache-b"
    assert reused.result_hash == first.result_hash
    assert (
        broker.cache.load_spec(first_spec.request_hash).experiment_id
        == first_spec.experiment_id
    )
    assert broker.results_for_path("cache-b")[0].request_hash == first_spec.request_hash
    assert broker.audit_key_results()[0]["valid"] is True


def test_final_audit_rejects_tampered_execution_record(
    demo_config, artifact_store
) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    spec = _spec(
        "numeric_counterexample",
        arguments={
            "lhs": "x^2",
            "rhs": "x",
            "relation": "eq",
            "variables": ["x"],
            "ranges": {"x": [2, 2]},
            "samples": 1,
        },
        exact_arithmetic=False,
    )
    context = ComputationContext(path_id="tamper-audit", remaining_llm_calls=5)
    broker.run_experiment(spec, broker.decide(spec, context))
    execution = broker.cache.load_execution(spec.request_hash)
    execution["input"]["seed"] += 1
    artifact_store.write_experiment_artifact(spec.request_hash, "execution", execution)

    audit = broker.audit_key_results()

    assert audit[0]["valid"] is False
    assert audit[0]["execution_record_valid"] is False


def test_typed_modular_integer_graph_recurrence_and_geometry_tools(
    demo_config, artifact_store
) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    context = ComputationContext(
        path_id="typed-tools",
        stalled_rounds=1,
        meta_review_approved=True,
        remaining_llm_calls=20,
    )

    modular = _spec(
        "modular_exhaustive",
        target="Every integer x satisfies x to the fifth congruent to x modulo five.",
        arguments={
            "lhs": "x^5",
            "rhs": "x",
            "modulus": 5,
            "finite_reduction": True,
            "reduction_justification": "The expression depends only on the residue class of x modulo five.",
        },
        domains={"x": {"min": 0, "max": 4}},
    )
    modular_result = broker.run_experiment(modular, broker.decide(modular, context))
    assert modular_result.outcome == ExperimentOutcome.CERTIFIED
    assert modular_result.evidence_strength == EvidenceStrength.EXHAUSTIVE_CERTIFICATE

    integer = _spec(
        "bounded_integer_search",
        domains={"x": {"min": 0, "max": 3}},
        arguments={"target": {"lhs": "x*x", "rhs": "x", "relation": "eq"}},
    )
    integer_result = broker.run_experiment(integer, broker.decide(integer, context))
    assert integer_result.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
    assert integer_result.counterexample["assignment"]["x"] in {2, 3}

    graph = _spec(
        "graph_certificate",
        target="The declared triangle has the supplied proper three-coloring.",
        purpose="validate_constructed_example",
        arguments={
            "graph": {
                "nodes": ["a", "b", "c"],
                "edges": [["a", "b"], ["b", "c"], ["c", "a"]],
                "directed": False,
            },
            "property": "proper_coloring",
            "certificate": {"colors": {"a": 0, "b": 1, "c": 2}},
        },
    )
    graph_result = broker.run_experiment(graph, broker.decide(graph, context))
    assert graph_result.outcome == ExperimentOutcome.CERTIFIED
    assert graph_result.independently_verified is True

    recurrence = _spec(
        "recurrence_check",
        target="The Fibonacci recurrence equals n throughout the declared interval.",
        arguments={
            "initial_values": [0, 1],
            "coefficients": [1, 1],
            "start_n": 0,
            "end_n": 8,
            "claimed_expression": "n",
        },
    )
    recurrence_result = broker.run_experiment(
        recurrence, broker.decide(recurrence, context)
    )
    assert recurrence_result.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
    assert recurrence_result.counterexample["n"] == 2

    bounded_recurrence = _spec(
        "recurrence_check",
        target="The declared initial values equal n only through the requested interval.",
        arguments={
            "initial_values": [0, 1, 999],
            "coefficients": [1],
            "start_n": 0,
            "end_n": 1,
            "claimed_expression": "n",
        },
    )
    bounded_recurrence_result = broker.run_experiment(
        bounded_recurrence,
        broker.decide(
            bounded_recurrence,
            ComputationContext(path_id="recurrence-scope", remaining_llm_calls=5),
        ),
    )
    assert bounded_recurrence_result.outcome == ExperimentOutcome.NOT_REFUTED
    assert bounded_recurrence_result.cases_checked == 2

    geometry = _spec(
        "exact_geometry",
        target="The three declared rational points are collinear.",
        arguments={
            "points": {"a": [0, 0], "b": [1, 1], "c": [2, 3]},
            "assertion": {
                "kind": "collinear",
                "points": ["a", "b", "c"],
                "expected": True,
            },
        },
    )
    geometry_result = broker.run_experiment(geometry, broker.decide(geometry, context))
    assert geometry_result.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
    assert geometry_result.exact_arithmetic is True


def test_tool_exception_is_inconclusive_not_a_math_failure(
    demo_config, artifact_store
) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    malformed = _spec(
        "recurrence_check",
        target="The malformed recurrence request should not decide a theorem.",
        arguments={},
    )
    decision = broker.decide(
        malformed, ComputationContext(path_id="error-path", remaining_llm_calls=5)
    )
    result = broker.run_experiment(malformed, decision)

    assert result.outcome == ExperimentOutcome.INCONCLUSIVE
    assert result.evidence_strength == EvidenceStrength.HEURISTIC
    assert "not a mathematical refutation" in result.verification_notes[0]

    limited_config = demo_config.model_copy(deep=True)
    limited_config.computation.enabled = True
    limited_config.computation.max_output_chars = 256
    limited_broker = ToolBroker(limited_config, artifact_store)
    oversized = _spec(
        "recurrence_check",
        target="The declared recurrence values are generated over a long finite interval.",
        arguments={
            "initial_values": [0, 1],
            "coefficients": [1, 1],
            "start_n": 0,
            "end_n": 80,
        },
    )
    oversized_result = limited_broker.run_experiment(
        oversized,
        limited_broker.decide(
            oversized,
            ComputationContext(path_id="output-limit", remaining_llm_calls=5),
        ),
    )
    assert oversized_result.outcome == ExperimentOutcome.INCONCLUSIVE
    assert "max_output_chars" in (oversized_result.error or "")


def test_exact_handlers_reject_ambiguous_integer_and_geometry_inputs(
    demo_config, artifact_store
) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    division = _spec(
        "bounded_integer_search",
        target="Integer division by a variable divisor satisfies the declared relation.",
        domains={"x": {"min": 0, "max": 2}, "y": {"min": 1, "max": 2}},
        arguments={"target": {"lhs": "x // y", "rhs": "0", "relation": "ge"}},
    )
    division_result = broker.run_experiment(
        division,
        broker.decide(
            division,
            ComputationContext(path_id="ambiguous-division", remaining_llm_calls=5),
        ),
    )
    assert division_result.outcome == ExperimentOutcome.INCONCLUSIVE
    assert "positive constant divisor" in (division_result.error or "")

    geometry = _spec(
        "exact_geometry",
        target="The orientation has a valid declared sign.",
        arguments={
            "points": {"a": [0, 0], "b": [1, 0], "c": [0, 1]},
            "assertion": {
                "kind": "orientation",
                "points": ["a", "b", "c"],
                "expected_sign": 2,
            },
        },
    )
    geometry_result = broker.run_experiment(
        geometry,
        broker.decide(
            geometry,
            ComputationContext(path_id="invalid-sign", remaining_llm_calls=5),
        ),
    )
    assert geometry_result.outcome == ExperimentOutcome.INCONCLUSIVE
    assert "-1, 0, or 1" in (geometry_result.error or "")


def test_partial_modular_domain_is_bounded_and_invalid_graph_certificate_refutes(
    demo_config, artifact_store
) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    context = ComputationContext(path_id="certificate-scope", remaining_llm_calls=10)
    partial = _spec(
        "modular_exhaustive",
        target="Every integer x satisfies x squared congruent to x modulo five.",
        arguments={
            "lhs": "x^2",
            "rhs": "x",
            "modulus": 5,
            "finite_reduction": True,
            "reduction_justification": "The expression depends only on x modulo five.",
        },
        domains={"x": {"min": 0, "max": 1}},
    )
    partial_result = broker.run_experiment(partial, broker.decide(partial, context))
    assert partial_result.outcome == ExperimentOutcome.NOT_REFUTED
    assert partial_result.evidence_strength == EvidenceStrength.BOUNDED_EVIDENCE
    assert partial_result.scope["full_residue_coverage"] is False

    invalid_graph = _spec(
        "graph_certificate",
        target="The declared edge has the supplied proper coloring.",
        purpose="validate_constructed_example",
        arguments={
            "graph": {
                "nodes": ["a", "b"],
                "edges": [["a", "b"]],
                "directed": False,
            },
            "property": "proper_coloring",
            "certificate": {"colors": {"a": 0, "b": 0}},
        },
    )
    invalid_result = broker.run_experiment(
        invalid_graph, broker.decide(invalid_graph, context)
    )
    assert invalid_result.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
    assert invalid_result.independently_verified is True


def test_sandbox_policy_rejects_dangerous_code_and_builds_isolated_docker_command(
    tmp_path: Path,
) -> None:
    with pytest.raises(UnsafeProgramError):
        validate_program_source(
            "import subprocess\ndef run(data):\n    return subprocess.run(['whoami'])\n"
        )
    with pytest.raises(UnsafeProgramError):
        validate_program_source("def run(data):\n    return open('secret').read()\n")
    assert validate_program_source(
        "from math import sqrt\ndef run(data):\n    return {'value': sqrt(data['x'])}\n"
    ) == {"math"}
    with pytest.raises(ValueError, match="wrong type"):
        _validate_json_object(
            {"nested": {"count": True}},
            {
                "type": "object",
                "properties": {
                    "nested": {
                        "type": "object",
                        "properties": {"count": {"type": "integer"}},
                        "required": ["count"],
                    }
                },
                "required": ["nested"],
            },
            label="output",
        )
    valid_program = ExperimentProgram(
        experiment_id="experiment-schema",
        source="def run(data):\n    return {'outcome': 'inconclusive', 'cases_checked': 0, 'scope': {}, 'exact_arithmetic': True}\n",
        input_schema={
            "type": "object",
            "properties": {"seed": {"type": "integer"}},
            "required": ["seed"],
        },
        output_schema={
            "type": "object",
            "properties": {
                "outcome": {"type": "string"},
                "cases_checked": {"type": "integer"},
                "scope": {"type": "object"},
                "exact_arithmetic": {"type": "boolean"},
            },
            "required": [
                "outcome",
                "cases_checked",
                "scope",
                "exact_arithmetic",
            ],
        },
    )
    _validate_program_schemas(valid_program)
    invalid_program = valid_program.model_copy(
        update={"input_schema": {"type": "object"}}
    )
    with pytest.raises(ValueError, match="injected integer seed"):
        _validate_program_schemas(invalid_program)

    config = ComputationConfig(
        enabled=True,
        sandboxed_python_enabled=True,
        sandbox_image=f"registry.example/mathproofmesh@sha256:{'a' * 64}",
    )
    command = build_docker_command(config, tmp_path.resolve())
    joined = " ".join(command)
    assert "--network none" in joined
    assert "--interactive" in command
    assert "--read-only" in command
    assert "--cap-drop ALL" in joined
    assert "no-new-privileges" in command
    assert "--pids-limit" in command
    assert "--memory" in command
    assert "--cpus" in command
    assert "--entrypoint python" in joined
    assert "readonly" in joined
    assert "@sha256:" in joined
    assert "--env" not in command
    assert str(Path.cwd().resolve()) not in joined


def test_docker_discovery_supports_per_user_windows_install(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    docker = (
        tmp_path / "Programs" / "DockerDesktop" / "resources" / "bin" / "docker.exe"
    )
    docker.parent.mkdir(parents=True)
    docker.touch()
    monkeypatch.setattr("mathproofmesh.computation.sandbox._IS_WINDOWS", True)
    monkeypatch.setattr(
        "mathproofmesh.computation.sandbox.shutil.which", lambda _: None
    )
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))

    assert _find_docker_executable() == str(docker)


def test_program_source_artifact_is_hash_checked(demo_config, artifact_store) -> None:
    broker = _enabled_broker(demo_config, artifact_store)
    spec = _spec(
        "sandboxed_python",
        target="The isolated program checks the declared finite predicate.",
        arguments={"input": {}},
    )
    program = ExperimentProgram(
        experiment_id=spec.experiment_id,
        source="def run(data):\n    return {'outcome': 'inconclusive'}\n",
        input_schema={"type": "object"},
        output_schema={"type": "object"},
    )
    broker.cache.save_program(spec.request_hash, program)
    program_path = (
        artifact_store.root / "experiments" / spec.request_hash / "program.py"
    )
    program_path.write_text("def run(data):\n    return {}\n", encoding="utf-8")

    with pytest.raises(ValueError, match="does not match"):
        broker.cache.load_program(spec.request_hash)


def test_confirmed_counterexample_overrides_model_pass(demo_config) -> None:
    orchestrator = ProofMeshOrchestrator(demo_config)
    target_claim = "For every integer x, x squared equals x."
    attempt = ProofAttempt(
        problem_hash="0" * 64,
        strategy_id="strategy-a",
        agent_id="explorer-a",
        round_index=0,
        status=AttemptStatus.COMPLETE,
        final_answer=target_claim,
        proof_steps=[
            ProofStep(
                step_id="s1",
                statement=target_claim,
                justification="Unsupported assertion.",
            )
        ],
        path_id="path-a",
    )
    report = VerificationReport(
        target_id=attempt.attempt_id,
        target_type="attempt",
        agent_id="verifier-a",
        stage="detailed",
        verdict="pass",
        confidence=0.99,
        concise_feedback="Model claimed the proof passed.",
    )
    counterexample = ExperimentResult(
        experiment_id="experiment-a",
        request_hash="a" * 64,
        path_id="path-a",
        target_claim=target_claim,
        method="bounded_integer_search",
        outcome="counterexample_found",
        evidence_strength="counterexample",
        counterexample={"assignment": {"x": 2}},
        exact_arithmetic=True,
        cases_checked=1,
        tool_name="bounded_integer_search",
        tool_version="test",
        independently_verified=True,
    )

    orchestrator._apply_experiment_counterexample_guard(
        attempt, report, [counterexample]
    )

    assert report.verdict.value == "fail"
    assert report.first_error_step == "experiment_counterexample"


@pytest.mark.asyncio
async def test_computation_round_trip_does_not_advance_checkpoint(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.computation.enabled = True
    config.continuation = ContinuationConfig(
        enabled=True,
        segments_per_explore_call=1,
        max_segments_per_path=2,
        process_resume_enabled=True,
    )
    config.budget.initial_paths = 1
    config.budget.max_paths = 1
    config.budget.strategies_to_generate = 1
    config.budget.candidates_to_verify = 1
    config.budget.max_rounds = 1
    config.budget.max_total_calls = 40
    requested = False

    def responder(schema_name, messages, schema):
        nonlocal requested
        if schema_name == "ContinuationTurn" and not requested:
            requested = True
            return {
                "action": "request_computation",
                "experiment_spec": _spec(
                    "numeric_counterexample",
                    target="For every sampled rational x, x squared equals x.",
                    arguments={
                        "lhs": "x^2",
                        "rhs": "x",
                        "relation": "eq",
                        "variables": ["x"],
                        "ranges": {"x": [10, 10]},
                        "samples": 3,
                    },
                    exact_arithmetic=False,
                ).model_dump(mode="json"),
                "reason": "A cheap refutation prevents pursuing a false lemma.",
            }
        response = demo_responder(schema_name, messages, schema)
        if schema_name == "ContinuationTurn" and requested:
            response["experiment_impact"] = "execution"
            response["reason"] = (
                "The refuted auxiliary claim was removed; the independent telescoping proof does not use it."
            )
        return response

    result = await ProofMeshOrchestrator(
        config,
        mock_responders={agent.id: responder for agent in config.agents},
    ).solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="computation-round-trip",
    )

    assert result.status.value == "verified"
    assert len(result.experiments) == 1
    assert result.experiments[0].outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
    path_checkpoints = {
        checkpoint.segment_index
        for checkpoint in result.proof_checkpoints
        if checkpoint.path_id == result.attempts[0].path_id
    }
    assert path_checkpoints == {0, 1}
    assert result.attempts[0].segment_count == 1
    assert result.experiments[0].path_id == result.attempts[0].path_id
    assert result.experiments[0].parent_checkpoint_id is not None


@pytest.mark.asyncio
async def test_simple_proof_does_not_trigger_computation(tmp_path: Path) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.computation.enabled = True
    config.budget.initial_paths = 1
    config.budget.max_paths = 1
    config.budget.strategies_to_generate = 1
    result = await ProofMeshOrchestrator(
        config,
        mock_responders={agent.id: demo_responder for agent in config.agents},
    ).solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="reasoning-only-simple-proof",
    )

    assert result.status.value == "verified"
    assert result.experiments == []
