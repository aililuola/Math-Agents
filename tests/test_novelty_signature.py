from __future__ import annotations

from mathproofmesh.inspiration.novelty import NoveltyGate
from mathproofmesh.schemas import NoveltySignature

from v07_helpers import make_v07_config


def test_mechanism_signature_detects_reworded_duplicate(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    gate = NoveltyGate(config.topology.inspiration)
    first = NoveltySignature(
        representation_tags=["modular"],
        mechanism_tags=["residue_class_split"],
        core_objects=["residue_classes"],
        key_transformations=["reduce_mod_m"],
        proof_principles=["finite_case_partition"],
        targeted_obligation_ids=["goal"],
    )
    reworded = NoveltySignature(
        representation_tags=["modular"],
        mechanism_tags=["residue_class_split"],
        core_objects=["residue_classes"],
        key_transformations=["reduce_mod_m"],
        proof_principles=["finite_case_partition"],
        targeted_obligation_ids=["goal"],
    )
    assessment = gate.assess(reworded, [first])
    assert assessment.duplicate
    assert assessment.novelty_score == 0.0
