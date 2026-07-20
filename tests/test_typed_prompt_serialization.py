from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from mathproofmesh.prompts import PromptBundle, PromptFactory, _json
from mathproofmesh.schemas import ProblemContract, RouteRole


@dataclass(frozen=True)
class NestedArtifact:
    label: str
    path: Path


def _problem() -> ProblemContract:
    return ProblemContract(
        exact_statement="Prove the target identity.",
        normalized_statement="prove the target identity",
    )


def _typed_context(bundle: PromptBundle) -> dict[str, object]:
    payload = bundle.user.split("SANITIZED CONTEXT:\n", 1)[1]
    payload = payload.split("\n\nOUTPUT LANGUAGE:", 1)[0]
    return json.loads(payload)


def test_json_recursively_normalizes_nested_typed_values() -> None:
    problem = _problem()
    payload = json.loads(
        _json(
            {
                "nested": {"problem": problem},
                "role": RouteRole.PROVER,
                "artifact": NestedArtifact("certificate", Path("proof/artifact.json")),
                "tags": {"geometry", "algebra"},
            }
        )
    )
    assert payload["nested"]["problem"]["integrity_hash"] == problem.integrity_hash
    assert payload["role"] == "prover"
    assert payload["artifact"]["path"] == str(Path("proof/artifact.json"))
    assert payload["tags"] == ["algebra", "geometry"]


def test_every_v07_typed_prompt_constructs_with_nested_models() -> None:
    problem = _problem()
    prompts = PromptFactory(computation_enabled=True)
    nested = {"contract": problem, "artifact_path": Path("structured/item.json")}
    bundles = [
        prompts.route_prove(problem, nested=nested),
        prompts.route_skeptic(problem=problem, nested=nested),
        prompts.route_referee(problem=problem, nested=nested),
        prompts.bridge_lemma(problem=problem, nested=nested),
        prompts.resolve_contradiction(problem=problem, nested=nested),
        prompts.acknowledge_message(problem=problem, nested=nested),
        prompts.counterexample_search(problem=problem, nested=nested),
        prompts.representation_switchboard(problem, nested=nested),
        prompts.structural_analogy_search(problem=problem, nested=nested),
        prompts.invent_auxiliary_construction(problem=problem, nested=nested),
        prompts.hypothesize_invariant(problem=problem, nested=nested),
        prompts.reverse_goal_analysis(problem=problem, nested=nested),
        prompts.persistent_meta_strategy(problem=problem, nested=nested),
        prompts.surprise_exploration(problem=problem, nested=nested),
        prompts.inspiration_referee(problem=problem, nested=nested),
    ]
    assert len({bundle.stage for bundle in bundles}) == len(bundles)
    for bundle in bundles:
        context = _typed_context(bundle)
        assert context["nested"]["contract"]["integrity_hash"] == problem.integrity_hash
        assert context["nested"]["artifact_path"] == str(Path("structured/item.json"))
        assert "JSON SCHEMA:" in bundle.user
