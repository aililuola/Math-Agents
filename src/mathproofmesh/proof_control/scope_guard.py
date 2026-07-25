from __future__ import annotations

import re
from collections.abc import Sequence

from ..config import ScopeGuardControlConfig
from ..schemas import (
    ClaimCard,
    MessageEnvelope,
    ProblemContract,
    ProofObligation,
    QuantifierSpec,
    VariableBinding,
)
from .models import (
    IndexScope,
    ObjectScope,
    ScopeRelation,
    ScopeSignature,
    UniformityScope,
)


class ScopeGuard:
    """Extract and compare quantifier, index, uniformity, and object scope."""

    def __init__(self, config: ScopeGuardControlConfig | None = None) -> None:
        self.config = config or ScopeGuardControlConfig()

    def extract_from_problem(self, problem: ProblemContract) -> ScopeSignature:
        return self._extract(
            subject_id=problem.problem_id,
            text=problem.exact_statement,
            domain_constraints=[
                *problem.definitions,
                *problem.hard_constraints,
            ],
            base_confidence=0.45,
        )

    def extract_from_message(self, message: MessageEnvelope) -> ScopeSignature:
        return self._extract(
            subject_id=message.message_id,
            text=f"{message.statement}\n{message.conclusion}",
            quantifiers=message.quantifiers,
            variable_bindings=message.variable_bindings,
            domain_constraints=message.assumptions,
            exceptional_cases=message.scope_limitations,
            base_confidence=message.normalization_confidence,
        )

    def extract_from_claim(self, claim: ClaimCard) -> ScopeSignature:
        return self._extract(
            subject_id=claim.claim_id,
            text=f"{claim.statement}\n{claim.conclusion}",
            domain_constraints=claim.assumptions,
            exceptional_cases=claim.scope_limitations,
            base_confidence=0.35 if claim.scope_limitations else 0.20,
        )

    def extract_from_obligation(self, obligation: ProofObligation) -> ScopeSignature:
        return self._extract(
            subject_id=obligation.obligation_id,
            text=f"{obligation.statement}\n{obligation.normalized_statement}",
            quantifiers=obligation.quantifiers,
            domain_constraints=obligation.assumptions,
            base_confidence=0.85 if obligation.quantifiers else 0.35,
        )

    def compare(
        self, premise: ScopeSignature, conclusion: ScopeSignature
    ) -> ScopeRelation:
        relations: list[ScopeRelation] = []
        relations.append(
            self._ordered_relation(
                premise.index_scope,
                conclusion.index_scope,
                {
                    IndexScope.SINGLE_INSTANCE: 1,
                    IndexScope.FINITE_PREFIX: 2,
                    IndexScope.BOUNDED_RANGE: 2,
                    IndexScope.EVENTUAL: 3,
                    IndexScope.ALL: 4,
                },
                incomparable={
                    frozenset((IndexScope.EVENTUAL, IndexScope.FINITE_PREFIX)),
                    frozenset((IndexScope.EVENTUAL, IndexScope.BOUNDED_RANGE)),
                    frozenset((IndexScope.FINITE_PREFIX, IndexScope.BOUNDED_RANGE)),
                },
            )
        )
        relations.append(
            self._ordered_relation(
                premise.uniformity,
                conclusion.uniformity,
                {
                    UniformityScope.EXISTS_PER_INSTANCE: 1,
                    UniformityScope.POINTWISE: 2,
                    UniformityScope.UNIFORM: 3,
                },
            )
        )
        relations.append(
            self._object_relation(premise.object_scope, conclusion.object_scope)
        )
        relations.append(self._quantifier_relation(premise, conclusion))

        material = [item for item in relations if item != ScopeRelation.SAME]
        if not material:
            return ScopeRelation.SAME
        if ScopeRelation.INCOMPARABLE in material:
            return ScopeRelation.INCOMPARABLE
        known = [item for item in material if item != ScopeRelation.UNKNOWN]
        if not known:
            return ScopeRelation.UNKNOWN
        if (
            ScopeRelation.CLAIM_STRONGER in known
            and ScopeRelation.CLAIM_WEAKER in known
        ):
            return ScopeRelation.INCOMPARABLE
        if ScopeRelation.UNKNOWN in material:
            return ScopeRelation.UNKNOWN
        return known[0]

    def can_close_obligation(
        self, premise: ScopeSignature, conclusion: ScopeSignature
    ) -> bool:
        relation = self.compare(premise, conclusion)
        if relation not in {ScopeRelation.SAME, ScopeRelation.CLAIM_STRONGER}:
            return False
        conclusion_is_structured = (
            conclusion.index_scope != IndexScope.UNKNOWN
            or conclusion.uniformity != UniformityScope.UNKNOWN
            or conclusion.object_scope != ObjectScope.UNKNOWN
            or bool(conclusion.quantifiers)
        )
        if (
            conclusion_is_structured
            and premise.normalization_confidence < self.config.risk_confidence_threshold
        ):
            return False
        return True

    def can_promote_fact(self, signature: ScopeSignature) -> bool:
        return (
            signature.normalization_confidence >= self.config.risk_confidence_threshold
        )

    def _extract(
        self,
        *,
        subject_id: str,
        text: str,
        quantifiers: Sequence[QuantifierSpec] = (),
        variable_bindings: Sequence[VariableBinding] = (),
        domain_constraints: Sequence[str] = (),
        exceptional_cases: Sequence[str] = (),
        base_confidence: float,
    ) -> ScopeSignature:
        normalized = " ".join(text.casefold().split())
        index_scope, index_detected = self._index_scope(normalized, quantifiers)
        uniformity, uniformity_detected = self._uniformity(normalized, quantifiers)
        object_scope, object_detected = self._object_scope(normalized)
        detection_count = sum(
            (index_detected, uniformity_detected, object_detected, bool(quantifiers))
        )
        confidence = min(1.0, max(base_confidence, 0.45 + detection_count * 0.12))
        return ScopeSignature(
            subject_id=subject_id,
            index_scope=index_scope,
            uniformity=uniformity,
            object_scope=object_scope,
            quantifiers=list(quantifiers),
            variable_bindings=list(variable_bindings),
            domain_constraints=list(domain_constraints),
            exceptional_cases=list(exceptional_cases),
            normalization_confidence=confidence,
        )

    @staticmethod
    def _index_scope(
        text: str, quantifiers: Sequence[QuantifierSpec]
    ) -> tuple[IndexScope, bool]:
        if any(
            marker in text
            for marker in (
                "eventually",
                "sufficiently large",
                "for all large",
                "from some index",
                "最终",
                "充分大",
            )
        ):
            return IndexScope.EVENTUAL, True
        if any(
            marker in text
            for marker in (
                "finite prefix",
                "first n terms",
                "initial segment",
                "有限前缀",
            )
        ):
            return IndexScope.FINITE_PREFIX, True
        if any(
            marker in text
            for marker in ("bounded range", "bounded interval", "有界范围")
        ) or re.search(r"\b\d+\s*(?:<=|≤)\s*[a-z]\s*(?:<=|≤)\s*\d+\b", text):
            return IndexScope.BOUNDED_RANGE, True
        if quantifiers and any(item.kind == "forall" for item in quantifiers):
            return IndexScope.ALL, True
        if any(
            marker in text
            for marker in ("for all", "for every", "each integer", "∀", "任意", "所有")
        ):
            return IndexScope.ALL, True
        if any(
            marker in text
            for marker in ("at n =", "for this instance", "single instance", "单个实例")
        ):
            return IndexScope.SINGLE_INSTANCE, True
        return IndexScope.UNKNOWN, False

    @staticmethod
    def _uniformity(
        text: str, quantifiers: Sequence[QuantifierSpec]
    ) -> tuple[UniformityScope, bool]:
        if "uniform" in text or "一致" in text:
            return UniformityScope.UNIFORM, True
        if "pointwise" in text or "逐点" in text:
            return UniformityScope.POINTWISE, True
        ordered = sorted(quantifiers, key=lambda item: item.order)
        first_forall = next(
            (index for index, item in enumerate(ordered) if item.kind == "forall"),
            None,
        )
        first_exists = next(
            (index for index, item in enumerate(ordered) if item.kind != "forall"),
            None,
        )
        if first_forall is not None and first_exists is not None:
            if first_forall < first_exists:
                return UniformityScope.EXISTS_PER_INSTANCE, True
            return UniformityScope.UNIFORM, True
        if any(
            marker in text
            for marker in ("for each", "depending on", "may depend on", "分别存在")
        ):
            return UniformityScope.EXISTS_PER_INSTANCE, True
        return UniformityScope.UNKNOWN, False

    @staticmethod
    def _object_scope(text: str) -> tuple[ObjectScope, bool]:
        if any(marker in text for marker in ("projection", "projected", "投影")):
            return ObjectScope.PROJECTION, True
        if any(
            marker in text for marker in ("quotient", "modulo equivalence", "商空间")
        ):
            return ObjectScope.QUOTIENT, True
        if any(
            marker in text
            for marker in ("residue class", "congruence class", "剩余类", "同余类")
        ):
            return ObjectScope.RESIDUE_CLASSES, True
        if any(
            marker in text
            for marker in ("substructure", "subgroup", "subspace", "子结构")
        ):
            return ObjectScope.SUBSTRUCTURE, True
        if any(
            marker in text
            for marker in (
                "original object",
                "full object",
                "entire object",
                "原对象",
                "完整对象",
            )
        ):
            return ObjectScope.FULL_OBJECT, True
        return ObjectScope.UNKNOWN, False

    @staticmethod
    def _ordered_relation(
        premise: object,
        conclusion: object,
        ranks: dict[object, int],
        *,
        incomparable: set[frozenset[object]] | None = None,
    ) -> ScopeRelation:
        if premise == conclusion:
            return ScopeRelation.SAME
        if str(premise).endswith("unknown") or str(conclusion).endswith("unknown"):
            return ScopeRelation.UNKNOWN
        if incomparable and frozenset((premise, conclusion)) in incomparable:
            return ScopeRelation.INCOMPARABLE
        premise_rank = ranks.get(premise)
        conclusion_rank = ranks.get(conclusion)
        if premise_rank is None or conclusion_rank is None:
            return ScopeRelation.UNKNOWN
        return (
            ScopeRelation.CLAIM_STRONGER
            if premise_rank > conclusion_rank
            else ScopeRelation.CLAIM_WEAKER
        )

    @staticmethod
    def _object_relation(
        premise: ObjectScope, conclusion: ObjectScope
    ) -> ScopeRelation:
        if premise == conclusion:
            return ScopeRelation.SAME
        if ObjectScope.UNKNOWN in {premise, conclusion}:
            return ScopeRelation.UNKNOWN
        if premise == ObjectScope.FULL_OBJECT:
            return ScopeRelation.CLAIM_STRONGER
        if conclusion == ObjectScope.FULL_OBJECT:
            return ScopeRelation.CLAIM_WEAKER
        return ScopeRelation.INCOMPARABLE

    @staticmethod
    def _quantifier_relation(
        premise: ScopeSignature, conclusion: ScopeSignature
    ) -> ScopeRelation:
        premise_items = [
            (
                item.order,
                item.kind,
                item.domain.casefold(),
                tuple(sorted(value.casefold() for value in item.restrictions)),
            )
            for item in premise.quantifiers
        ]
        conclusion_items = [
            (
                item.order,
                item.kind,
                item.domain.casefold(),
                tuple(sorted(value.casefold() for value in item.restrictions)),
            )
            for item in conclusion.quantifiers
        ]
        if premise_items == conclusion_items:
            return ScopeRelation.SAME
        if not premise_items or not conclusion_items:
            return ScopeRelation.UNKNOWN
        return ScopeRelation.INCOMPARABLE
