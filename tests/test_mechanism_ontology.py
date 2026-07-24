from __future__ import annotations

from mathproofmesh.inspiration.novelty import NoveltyGate
from mathproofmesh.inspiration.ontology import MechanismNormalizer
from mathproofmesh.schemas import NoveltySignature

from v07_helpers import make_v07_config


def test_mechanism_normalizer_preserves_raw_and_extension_tags(tmp_path) -> None:
    normalizer = MechanismNormalizer()
    normalized = normalizer.normalize_signature(
        NoveltySignature(
            representation_tags=["p-adic valuation", "invented lens"],
            mechanism_tags=["representation switch"],
            core_objects=["valuation profile"],
            key_transformations=["reduce mod m"],
            proof_principles=["minimal counterexample"],
        )
    )

    assert normalized.representation_tags == ["valuation"]
    assert normalized.mechanism_tags == ["representation_switch"]
    assert normalized.core_objects == ["valuation_vector"]
    assert normalized.key_transformations == ["modular_reduction"]
    assert normalized.proof_principles == ["descent"]
    assert "representation:invented_lens" in normalized.extension_tags
    assert normalized.raw_tags["representation"] == [
        "p-adic valuation",
        "invented lens",
    ]
    assert normalized.normalizer_version == normalizer.version


def test_route_tags_are_classified_by_dimension_instead_of_copied(tmp_path) -> None:
    signature = MechanismNormalizer().signature_from_route_tags(
        ["modular arithmetic", "minimal counterexample", "quotient"]
    )

    assert signature.representation_tags == ["modular"]
    assert "descent" in signature.proof_principles
    assert "quotient" in signature.key_transformations
    assert signature.core_objects == []
    assert signature.mechanism_tags == ["route_strategy"]


def test_unknown_labels_cannot_independently_force_a_duplicate(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    gate = NoveltyGate(config.topology.inspiration)
    first = NoveltySignature(extension_tags=["route:unclassified_alpha"])
    second = NoveltySignature(extension_tags=["route:unclassified_alpha"])

    assessment = gate.assess(first, [second])

    assert assessment.maximum_similarity <= 0.5
    assert not assessment.duplicate


def test_known_aliases_are_detected_as_the_same_mechanism(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    gate = NoveltyGate(config.topology.inspiration)
    first = NoveltySignature(
        representation_tags=["p-adic valuation"],
        proof_principles=["minimal counterexample"],
    )
    second = NoveltySignature(
        representation_tags=["valuation"],
        proof_principles=["descent"],
    )

    assert gate.assess(first, [second]).duplicate
