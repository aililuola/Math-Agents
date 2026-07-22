from __future__ import annotations

import re
from collections.abc import Iterable

from ..schemas import NoveltySignature


NORMALIZER_VERSION = "math-mechanism-ontology-v1"


def _slug(value: str) -> str:
    normalized = re.sub(r"[^a-z0-9]+", "_", value.casefold()).strip("_")
    return re.sub(r"_+", "_", normalized)


_ALIASES: dict[str, dict[str, str]] = {
    "representation": {
        "modular": "modular",
        "modular_arithmetic": "modular",
        "modular_congruence": "modular",
        "residue": "modular",
        "p_adic": "valuation",
        "p_adic_valuation": "valuation",
        "valuation": "valuation",
        "graph": "graph",
        "graph_hypergraph": "graph",
        "hypergraph": "graph",
        "polynomial": "polynomial",
        "linear_algebra_polynomial": "polynomial",
        "generating_function": "generating_function",
        "coordinate": "coordinate",
        "coordinate_geometry": "coordinate",
        "complex_plane": "complex",
        "inversion_projective": "inversion",
        "finite_state": "finite_state",
        "recurrence_finite_state": "finite_state",
        "probabilistic": "probabilistic",
        "probabilistic_method": "probabilistic",
        "synthetic_geometry": "synthetic_geometry",
        "direct_algebra": "algebraic",
    },
    "mechanism": {
        "route_strategy": "route_strategy",
        "representation_switch": "representation_switch",
        "structural_analogy": "structural_analogy",
        "auxiliary_construction": "auxiliary_construction",
        "invariant_hypothesis": "invariant_hypothesis",
        "reverse_goal_analysis": "reverse_goal_analysis",
        "bridge_lemma": "bridge_lemma",
        "surprise": "surprise_exploration",
        "surprise_exploration": "surprise_exploration",
        "persistent_meta_strategy": "persistent_meta_strategy",
        "meta_replan": "persistent_meta_strategy",
        "residue_class_split": "residue_class_split",
        "sufficient_condition": "sufficient_condition",
    },
    "object": {
        "sequence": "sequence",
        "auxiliary_sequence": "sequence",
        "graph": "graph",
        "bipartite_graph": "graph",
        "residue_class": "residue_class",
        "residue_classes": "residue_class",
        "valuation_vector": "valuation_vector",
        "valuation_profile": "valuation_vector",
        "auxiliary_circle": "auxiliary_circle",
        "auxiliary_point": "auxiliary_point",
        "polynomial": "polynomial",
        "auxiliary_polynomial": "polynomial",
        "potential": "potential_function",
        "potential_function": "potential_function",
        "state": "state",
        "state_space": "state",
        "transition_relation": "transition_relation",
        "equivalence_classes": "equivalence_class",
        "quotient_object": "quotient_object",
        "extremal_object": "extremal_object",
        "goal": "goal",
        "sufficient_intermediate_claim": "intermediate_claim",
    },
    "transformation": {
        "quotient": "quotient",
        "lift": "lift",
        "encode": "encode",
        "dualize": "dualize",
        "complement": "complement",
        "homogenize": "homogenize",
        "factor": "factor",
        "telescope": "telescope",
        "symmetrize": "symmetrize",
        "localize": "localize",
        "reduce_mod_m": "modular_reduction",
        "modular_reduction": "modular_reduction",
        "compare_before_after": "compare_before_after",
        "goal_to_sufficient_condition": "backward_reduction",
    },
    "principle": {
        "induction": "induction",
        "descent": "descent",
        "extremal": "extremal",
        "extremal_principle": "extremal",
        "minimal_counterexample": "descent",
        "double_counting": "double_counting",
        "pigeonhole": "pigeonhole",
        "invariant": "invariant",
        "invariance": "invariant",
        "monovariant": "monovariant",
        "contradiction": "contradiction",
        "finite_case_partition": "finite_partition",
        "finite_partition": "finite_partition",
        "backward_chaining": "backward_chaining",
        "valuation": "valuation",
        "modular_potential": "monovariant",
        "parity": "parity",
        "directed_angles": "directed_angles",
        "power": "power_of_point",
        "incidence_structure": "incidence_structure",
        "probabilistic_method": "probabilistic_method",
        "observable_search_control": "search_control",
    },
}


class MechanismNormalizer:
    """Map free-form route labels into a conservative, auditable ontology."""

    version = NORMALIZER_VERSION

    def normalize_signature(self, signature: NoveltySignature) -> NoveltySignature:
        if signature.normalizer_version == self.version:
            return signature
        raw = {
            "representation": list(signature.representation_tags),
            "mechanism": list(signature.mechanism_tags),
            "object": list(signature.core_objects),
            "transformation": list(signature.key_transformations),
            "principle": list(signature.proof_principles),
        }
        canonical: dict[str, list[str]] = {}
        extensions = list(signature.extension_tags)
        recognized = 0
        total = 0
        for dimension, values in raw.items():
            mapped: list[str] = []
            for value in values:
                tag = _slug(value)
                if not tag:
                    continue
                total += 1
                resolved = _ALIASES[dimension].get(tag)
                if resolved is None:
                    extensions.append(f"{dimension}:{tag}")
                    continue
                recognized += 1
                mapped.append(resolved)
            canonical[dimension] = list(dict.fromkeys(mapped))
        payload = signature.model_dump(mode="python")
        payload.update(
            {
                "representation_tags": canonical["representation"],
                "mechanism_tags": canonical["mechanism"],
                "core_objects": canonical["object"],
                "key_transformations": canonical["transformation"],
                "proof_principles": canonical["principle"],
                "extension_tags": list(dict.fromkeys(extensions)),
                "raw_tags": raw,
                "normalizer_version": self.version,
                "normalization_confidence": recognized / max(1, total),
                "normalized_hash": "",
            }
        )
        return NoveltySignature.model_validate(payload)

    def signature_from_route_tags(
        self,
        tags: Iterable[str],
        *,
        targeted_obligation_ids: Iterable[str] = (),
    ) -> NoveltySignature:
        raw = [value for value in tags if value.strip()]
        buckets: dict[str, list[str]] = {
            "representation": [],
            "mechanism": ["route_strategy"],
            "object": [],
            "transformation": [],
            "principle": [],
        }
        extensions: list[str] = []
        recognized = 0
        for value in raw:
            tag = _slug(value)
            matched = False
            for dimension, aliases in _ALIASES.items():
                candidates = sorted(aliases, key=len, reverse=True)
                alias = next(
                    (
                        item
                        for item in candidates
                        if tag == item or f"_{item}_" in f"_{tag}_"
                    ),
                    None,
                )
                if alias is None:
                    continue
                buckets[dimension].append(aliases[alias])
                matched = True
            if matched:
                recognized += 1
            elif tag:
                extensions.append(f"route:{tag}")
        return NoveltySignature(
            representation_tags=list(dict.fromkeys(buckets["representation"])),
            mechanism_tags=list(dict.fromkeys(buckets["mechanism"])),
            core_objects=list(dict.fromkeys(buckets["object"])),
            key_transformations=list(dict.fromkeys(buckets["transformation"])),
            proof_principles=list(dict.fromkeys(buckets["principle"])),
            targeted_obligation_ids=list(dict.fromkeys(targeted_obligation_ids)),
            extension_tags=list(dict.fromkeys(extensions)),
            raw_tags={"route": raw},
            normalizer_version=self.version,
            normalization_confidence=recognized / max(1, len(raw)),
        )


__all__ = ["MechanismNormalizer", "NORMALIZER_VERSION"]
