from __future__ import annotations

import re

from ..proof_identity import normalize_text, obligation_identity_text
from ..schemas import ObligationKind, ProofObligation
from .domains import classify_obligation_domain
from .models import ObligationDomain, ObligationSemanticQuality


class ObligationSemanticGate:
    """Keep tasks and placeholders out of the mathematical obligation graph."""

    _PROPOSITION_PREFIX = re.compile(
        r"^(?:prove|show|establish|verify)\s+(?:that\s+)?",
        re.IGNORECASE,
    )
    _ACTION_PREFIX = re.compile(
        r"^(?:find|search|explore|analyze|complete|avoid|choose|construct|"
        r"investigate|try)\b",
        re.IGNORECASE,
    )
    _RELATION_WORDS = {
        "admits",
        "are",
        "belongs",
        "contains",
        "equals",
        "exists",
        "has",
        "have",
        "holds",
        "implies",
        "intersects",
        "is",
        "preserves",
        "satisfies",
    }
    _QUANTIFIER_MARKERS = (
        "every ",
        "each ",
        "all ",
        "any ",
        "for all ",
        "for every ",
        "there exists ",
        "some ",
        "whenever ",
        "if ",
    )
    _PLACEHOLDER_PHRASES = (
        "find an invariant",
        "find a suitable invariant",
        "avoid circular dependencies",
        "prove the theorem",
        "complete the argument",
        "construct something suitable",
        "analyze carefully",
        "find a mechanism-level bridge",
        "establish a reviewed intermediate implication",
    )
    _STOP_WORDS = {
        "a",
        "an",
        "and",
        "any",
        "each",
        "every",
        "for",
        "from",
        "if",
        "in",
        "is",
        "of",
        "some",
        "that",
        "the",
        "then",
        "there",
        "to",
    }

    def assess(
        self,
        obligation: ProofObligation,
        *,
        source_kind: str | None = None,
        main_goal: ProofObligation | None = None,
        source_statement: str | None = None,
        executable_first_step: str | None = None,
    ) -> ObligationSemanticQuality:
        normalized = normalize_text(
            obligation.normalized_statement or obligation.statement
        ).casefold()
        semantic_text = self._PROPOSITION_PREFIX.sub("", normalized).strip()
        tokens = re.findall(r"[a-z][a-z0-9_]*|\d+", semantic_text)
        content_tokens = [
            token
            for token in tokens
            if token not in self._STOP_WORDS and token not in self._RELATION_WORDS
        ]
        symbolic_relation = bool(
            re.search(r"(?:<=|>=|!=|==|=|<|>|∈|⊆|→|↔)", semantic_text)
        )
        word_relation = (
            bool(set(tokens) & self._RELATION_WORDS)
            or (" if and only if " in f" {semantic_text} ")
            or (semantic_text.startswith("if ") and " then " in semantic_text)
        )
        has_relation = symbolic_relation or word_relation
        quantified = bool(obligation.quantifiers) or any(
            marker in f"{semantic_text} " for marker in self._QUANTIFIER_MARKERS
        )
        has_objects = len(set(content_tokens)) >= 2 or bool(
            symbolic_relation and content_tokens
        )
        pure_action = bool(self._ACTION_PREFIX.match(normalized))
        phrase_placeholder = any(
            phrase in normalized for phrase in self._PLACEHOLDER_PHRASES
        )
        is_placeholder = phrase_placeholder or (
            pure_action and not (has_relation and quantified)
        )
        truth_apt = bool(
            has_relation and has_objects and not is_placeholder and len(tokens) >= 2
        )
        has_scope = bool(
            quantified
            or obligation.assumptions
            or (truth_apt and (symbolic_relation or len(content_tokens) >= 2))
        )
        has_executable_step = bool(
            (executable_first_step or "").strip()
            or (
                truth_apt
                and (
                    has_scope
                    or obligation.kind
                    in {
                        ObligationKind.COMPUTATION_QUESTION,
                        ObligationKind.CONSTRUCTION,
                    }
                )
            )
        )
        source_text = normalize_text(source_statement or "").casefold()
        source_identity = obligation_identity_text(
            self._PROPOSITION_PREFIX.sub("", source_text).strip()
        )
        obligation_identity = obligation_identity_text(semantic_text)
        logical_parts = re.fullmatch(
            r"if\s+(.+?)[,;]?\s+then\s+(.+?)[.]?",
            semantic_text,
        )
        arrow_parts = re.fullmatch(
            r"(.+?)\s*(?:=>|->|→)\s*(.+?)[.]?",
            semantic_text,
        )
        implication_parts = logical_parts or arrow_parts
        internal_self_implication = bool(
            implication_parts
            and obligation_identity_text(implication_parts.group(1))
            == obligation_identity_text(implication_parts.group(2))
        )
        is_self_implication = bool(
            internal_self_implication
            or (source_identity and source_identity == obligation_identity)
        )
        duplicates_main_goal = bool(
            main_goal is not None
            and obligation.kind != ObligationKind.MAIN_GOAL
            and obligation_identity
            == obligation_identity_text(
                self._PROPOSITION_PREFIX.sub(
                    "",
                    normalize_text(main_goal.normalized_statement).casefold(),
                ).strip()
            )
        )
        domain_record = classify_obligation_domain(
            obligation,
            source_kind=source_kind,
        )
        domain = domain_record.domain
        if domain == ObligationDomain.MATHEMATICAL and is_placeholder:
            domain = ObligationDomain.SEARCH

        rejection_reasons: list[str] = []
        if domain != ObligationDomain.MATHEMATICAL:
            rejection_reasons.append(f"non_mathematical_domain:{domain.value}")
        if not truth_apt:
            rejection_reasons.append("not_truth_apt")
        if not has_objects:
            rejection_reasons.append("missing_explicit_objects")
        if not has_relation:
            rejection_reasons.append("missing_explicit_relation")
        if not has_scope:
            rejection_reasons.append("missing_quantifier_or_scope")
        if is_placeholder:
            rejection_reasons.append("placeholder")
        if is_self_implication:
            rejection_reasons.append("self_implication")
        if duplicates_main_goal:
            rejection_reasons.append("duplicates_main_goal")
        if not has_executable_step:
            rejection_reasons.append("no_executable_first_step")

        score = (
            sum(
                (
                    domain == ObligationDomain.MATHEMATICAL,
                    truth_apt,
                    has_objects,
                    has_relation,
                    has_scope,
                    has_executable_step,
                    not is_placeholder,
                    not is_self_implication,
                    not duplicates_main_goal,
                )
            )
            / 9.0
        )
        accepted = not rejection_reasons
        return ObligationSemanticQuality(
            obligation_id=obligation.obligation_id,
            domain=domain,
            truth_apt=truth_apt,
            has_explicit_objects=has_objects,
            has_explicit_relation=has_relation,
            has_explicit_quantifiers_or_scope=has_scope,
            is_placeholder=is_placeholder,
            is_self_implication=is_self_implication,
            duplicates_main_goal=duplicates_main_goal,
            has_executable_first_step=has_executable_step,
            score=score,
            rejection_reasons=rejection_reasons,
            accepted=accepted,
            semantic_quarantine=not accepted,
            eligible_for_core_debt=accepted,
            eligible_for_bottleneck=accepted,
        )
