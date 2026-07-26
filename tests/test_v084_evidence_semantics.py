from __future__ import annotations

import subprocess
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from mathproofmesh.computation.broker import ToolBroker
from mathproofmesh.computation.handlers.graph import run_graph_certificate
from mathproofmesh.computation.sandbox import build_lean_docker_command
from mathproofmesh.schemas import (
    EvidenceStrength,
    ExperimentOutcome,
    ExperimentSpec,
    ProofStep,
)
from mathproofmesh.verification.formal_microcert import formalization_coverage


def _spec(method: str, arguments: dict[str, Any]) -> ExperimentSpec:
    return ExperimentSpec(
        purpose="falsify_claim",
        target_claim="The declared certificate-level claim holds.",
        assumptions=["All objects lie in the declared finite scope."],
        reasoning_basis=(
            "The typed check decides whether the supplied evidence certifies the claim."
        ),
        why_computation_is_needed=(
            "The deterministic checker is faster and less error-prone than manual review."
        ),
        decision_if_confirmed="Keep the claim together with the produced certificate.",
        decision_if_refuted="Repair the affected route before reusing the claim.",
        noncomputational_alternative="A reviewer can recheck the object by hand.",
        method=method,
        domains={},
        arguments=arguments,
        exact_arithmetic=True,
        max_cases=10,
        seed=20260726,
    )


def _lean_broker(
    demo_config,
    artifact_store,
    monkeypatch: pytest.MonkeyPatch,
    *,
    returncode: int,
    stdout: str = "",
    stderr: str = "",
) -> ToolBroker:
    config = demo_config.model_copy(deep=True)
    config.computation.enabled = True
    config.verification.enable_lean = True
    config.verification.lean_sandbox_image = (
        "ghcr.io/mathlib4/mathlib@sha256:" + "1" * 64
    )
    monkeypatch.setattr("shutil.which", lambda name: f"/fake/{name}")

    def fake_run(command, **_kwargs):
        return subprocess.CompletedProcess(
            args=command, returncode=returncode, stdout=stdout, stderr=stderr
        )

    monkeypatch.setattr(subprocess, "run", fake_run)
    return ToolBroker(config, artifact_store)


def test_lean_command_is_networkless_read_only_and_digest_pinned(
    demo_config,
) -> None:
    config = demo_config.model_copy(deep=True)
    config.verification.lean_sandbox_image = (
        "ghcr.io/mathlib4/mathlib@sha256:" + "2" * 64
    )

    command = build_lean_docker_command(
        config.verification,
        Path("C:/tmp/lean-proof"),
    )

    assert command[:3] == ["docker", "run", "--rm"]
    assert ["--network", "none"] == command[
        command.index("--network") : command.index("--network") + 2
    ]
    assert "--read-only" in command
    assert ["--cap-drop", "ALL"] == command[
        command.index("--cap-drop") : command.index("--cap-drop") + 2
    ]
    assert config.verification.lean_sandbox_image in command
    assert command[-1] == "/work/Main.lean"


def test_formalization_coverage_counts_only_bound_formal_certificates() -> None:
    steps = [
        ProofStep(step_id="s1", statement="A.", justification="Given."),
        ProofStep(step_id="s2", statement="B.", justification="From A."),
    ]
    results = [
        SimpleNamespace(
            target_claim_id="s1",
            outcome=ExperimentOutcome.CERTIFIED,
            evidence_strength=EvidenceStrength.FORMAL_CERTIFICATE,
            independently_verified=True,
        ),
        SimpleNamespace(
            target_claim_id="s2",
            outcome=ExperimentOutcome.CERTIFIED,
            evidence_strength=EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
            independently_verified=True,
        ),
    ]

    report = formalization_coverage(steps, results)

    assert report.total_step_count == 2
    assert report.formally_certified_step_ids == ["s1"]
    assert report.coverage == 0.5


def test_lean_without_pinned_sandbox_fails_closed_without_host_execution(
    demo_config,
    artifact_store,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    config = demo_config.model_copy(deep=True)
    config.computation.enabled = True
    config.verification.enable_lean = True

    def forbidden_host_run(*_args, **_kwargs):
        raise AssertionError("Lean must not run on the host")

    monkeypatch.setattr(subprocess, "run", forbidden_host_run)
    evidence = ToolBroker(config, artifact_store)._dispatch(
        _spec("lean_check", {"source": "theorem t : True := trivial"}),
        None,
    )

    assert evidence.outcome == ExperimentOutcome.INCONCLUSIVE
    assert evidence.raw_output["lean_rejection"]["reason"] == (
        "lean_sandbox_unconfigured"
    )


@pytest.mark.parametrize(
    ("source", "stdout", "expected_marker"),
    [
        ("theorem t : 1 = 1 := by sorry", "", "sorry"),
        ("theorem t : 1 = 1 := by admit", "", "admit"),
        ("theorem t : 1 = 1 := rfl", "warning: declaration uses 'sorry'", "sorry"),
        ("axiom magic : 1 = 0\ntheorem t : 1 = 1 := rfl", "", "axiom"),
    ],
)
def test_lean_incomplete_proof_marker_never_gets_formal_certificate(
    demo_config,
    artifact_store,
    monkeypatch: pytest.MonkeyPatch,
    source: str,
    stdout: str,
    expected_marker: str,
) -> None:
    broker = _lean_broker(
        demo_config, artifact_store, monkeypatch, returncode=0, stdout=stdout
    )
    evidence = broker._dispatch(_spec("lean_check", {"source": source}), None)

    assert evidence.outcome == ExperimentOutcome.INCONCLUSIVE
    assert evidence.evidence_strength == EvidenceStrength.HEURISTIC
    assert evidence.independently_verified is False
    payload = evidence.raw_output["lean_rejection"]
    assert payload["accepted"] is False
    assert payload["reason"] == "lean_incomplete_proof_marker"
    assert payload["marker"] == expected_marker


def test_lean_clean_acceptance_still_yields_formal_certificate(
    demo_config, artifact_store, monkeypatch: pytest.MonkeyPatch
) -> None:
    # Word-boundary scanning must not misfire on identifiers that merely
    # contain a marker substring.
    source = "theorem sorry_free_axioms_ok : True := trivial"
    broker = _lean_broker(demo_config, artifact_store, monkeypatch, returncode=0)
    evidence = broker._dispatch(_spec("lean_check", {"source": source}), None)

    assert evidence.outcome == ExperimentOutcome.CERTIFIED
    assert evidence.evidence_strength == EvidenceStrength.FORMAL_CERTIFICATE
    assert evidence.certificate["accepted"] is True


def test_lean_compile_failure_is_inconclusive_not_a_counterexample(
    demo_config, artifact_store, monkeypatch: pytest.MonkeyPatch
) -> None:
    broker = _lean_broker(
        demo_config,
        artifact_store,
        monkeypatch,
        returncode=1,
        stderr="error: unknown identifier 'frobnicate'",
    )
    evidence = broker._dispatch(
        _spec("lean_check", {"source": "theorem t : 1 = 1 := frobnicate"}), None
    )

    assert evidence.outcome == ExperimentOutcome.INCONCLUSIVE
    assert evidence.evidence_strength == EvidenceStrength.HEURISTIC
    assert evidence.independently_verified is False
    assert evidence.counterexample is None
    assert any("does not refute" in note for note in evidence.verification_notes)
    assert evidence.raw_output["lean_rejection"]["returncode"] == 1


def test_invalid_graph_coloring_certificate_is_inconclusive() -> None:
    spec = _spec(
        "graph_certificate",
        {
            "graph": {
                "nodes": ["a", "b"],
                "edges": [["a", "b"]],
                "directed": False,
            },
            "property": "proper_coloring",
            "certificate": {"colors": {"a": 0, "b": 0}},
        },
    )
    evidence = run_graph_certificate(spec)

    assert evidence.outcome == ExperimentOutcome.INCONCLUSIVE
    assert evidence.evidence_strength == EvidenceStrength.HEURISTIC
    assert evidence.independently_verified is False
    assert evidence.counterexample is None
    assert any(
        "does not refute the existence claim" in note
        for note in evidence.verification_notes
    )


def test_disconnected_graph_is_still_a_genuine_connectivity_counterexample() -> None:
    spec = _spec(
        "graph_certificate",
        {
            "graph": {
                "nodes": ["a", "b", "c"],
                "edges": [["a", "b"]],
                "directed": False,
            },
            "property": "connected",
            "certificate": {},
        },
    )
    evidence = run_graph_certificate(spec)

    assert evidence.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
    assert evidence.evidence_strength == EvidenceStrength.COUNTEREXAMPLE
    assert evidence.independently_verified is True
