from __future__ import annotations

import json

from mathproofmesh.inspiration.analogy_agent import AnalogyAgent
from mathproofmesh.inspiration.local_library import LocalAnalogyLibrary
from mathproofmesh.schemas import ProblemContract


def test_analogy_uses_verified_local_record_and_preserves_transfer_limits(
    tmp_path,
) -> None:
    library_path = tmp_path / "analogy.jsonl"
    valid = {
        "record_id": "verified-1",
        "verified": True,
        "problem_summary": "finite sums telescope through consecutive differences",
        "object_tags": ["finite_sum"],
        "operation_tags": ["difference"],
        "mechanism_tags": ["telescoping"],
        "object_correspondence": {"partial sum": "target partial sum"},
        "operation_correspondence": {"difference": "target difference"},
        "transferable_lemmas": ["consecutive differences telescope"],
        "non_transferable_conditions": ["the target difference identity is separate"],
        "transfer_risks": ["boundary indices may differ"],
        "required_bridge_lemmas": ["prove the target difference identity"],
    }
    invalid = {"record_id": "unverified", "verified": False}
    library_path.write_text(
        json.dumps(valid) + "\n" + json.dumps(invalid) + "\n", encoding="utf-8"
    )
    problem = ProblemContract(
        exact_statement="Prove a finite-sum identity by differences.",
        normalized_statement="finite sum identity differences",
    )
    mappings = AnalogyAgent(LocalAnalogyLibrary(library_path)).search(
        problem,
        target_obligation_ids=["target"],
        object_tags=["finite_sum"],
        operation_tags=["difference"],
    )
    assert len(mappings) == 1
    assert mappings[0].source_record_id == "verified-1"
    assert mappings[0].non_transferable_conditions
    assert mappings[0].transfer_risks
    assert mappings[0].required_bridge_lemmas


def test_missing_analogy_library_degrades_to_empty_result(tmp_path) -> None:
    library = LocalAnalogyLibrary(tmp_path / "missing.jsonl")
    problem = ProblemContract(exact_statement="Prove P.", normalized_statement="p")
    assert AnalogyAgent(library).search(problem, target_obligation_ids=["target"]) == []
    assert library.diagnostics
