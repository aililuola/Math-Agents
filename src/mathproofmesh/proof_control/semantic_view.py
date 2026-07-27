from __future__ import annotations

import re
import unicodedata

from ..schemas import (
    ProblemSemanticView,
    ProblemSemanticViewCandidate,
    SemanticInvariantAudit,
    stable_hash,
)
from .semantic_profile import audit_bilingual_translation

_CJK = re.compile(r"[\u3400-\u9fff]")
_LATIN_WORD = re.compile(r"\b[A-Za-z]{2,}\b")
_MATH_BLOCK = re.compile(r"\$[^$]+\$|\\\([^)]*\\\)|\\\[[^\]]*\\\]")
_LATEX_COMMAND = re.compile(r"\\[A-Za-z]+(?:\{[^{}]*\})?")
_IDENTIFIER = re.compile(
    r"(?<![A-Za-z0-9_\\])"
    r"[A-Za-z](?:_[A-Za-z0-9{}]+|\^[A-Za-z0-9{}]+)?"
    r"(?![A-Za-z0-9_])"
)
_NUMBER = re.compile(r"(?<![A-Za-z0-9_])\d+(?:\.\d+)?")
_MATH_SYMBOL = re.compile(r"[∀∃=≤≥≠∈∉⊂⊆→↔∣+\-*/^]")


def contains_cjk(value: str) -> bool:
    return bool(_CJK.search(unicodedata.normalize("NFKC", value)))


def protected_math_fragments(value: str) -> list[str]:
    """Extract tokens a translation must copy exactly.

    These fragments are prompt-control metadata only. They never replace or
    normalize the frozen statement.
    """

    text = unicodedata.normalize("NFKC", value)
    fragments: list[str] = []
    for pattern in (_MATH_BLOCK, _LATEX_COMMAND, _IDENTIFIER, _NUMBER, _MATH_SYMBOL):
        fragments.extend(match.group(0) for match in pattern.finditer(text))
    return list(dict.fromkeys(fragment for fragment in fragments if fragment.strip()))


def _compact(value: str) -> str:
    return "".join(unicodedata.normalize("NFKC", value).split())


def build_problem_semantic_view(
    source_statement: str,
    candidate: ProblemSemanticViewCandidate,
) -> ProblemSemanticView:
    """Audit an English translation candidate without changing the goal."""

    protected = protected_math_fragments(source_statement)
    compact_translation = _compact(candidate.english_statement)
    missing = [
        fragment
        for fragment in protected
        if _compact(fragment) not in compact_translation
    ]
    preservation_flags = (
        candidate.preserves_hypotheses,
        candidate.preserves_quantifiers,
        candidate.preserves_domains,
        candidate.preserves_conclusion,
    )
    comparisons = audit_bilingual_translation(
        source_statement,
        candidate.english_statement,
    )
    deterministic_audit_passed = all(
        comparison.status != "fail" for comparison in comparisons
    )
    usable = (
        contains_cjk(source_statement)
        and bool(_LATIN_WORD.search(candidate.english_statement))
        and all(preservation_flags)
        and candidate.confidence >= 0.75
        and not missing
        and deterministic_audit_passed
    )
    notes = list(candidate.notes)
    if missing:
        notes.append(
            "translation rejected because protected mathematical fragments changed"
        )
    if not all(preservation_flags):
        notes.append(
            "translation rejected because a semantic preservation check failed"
        )
    notes.extend(
        "deterministic semantic audit failed: "
        f"{comparison.invariant}: {comparison.detail}"
        for comparison in comparisons
        if comparison.status == "fail"
    )
    return ProblemSemanticView(
        source_statement_hash=stable_hash(source_statement),
        source_language="zh" if contains_cjk(source_statement) else "unknown",
        english_statement=candidate.english_statement,
        candidate_confidence=candidate.confidence,
        protected_fragments=protected,
        missing_protected_fragments=missing,
        deterministic_audit_passed=deterministic_audit_passed,
        audit_findings=[
            SemanticInvariantAudit(
                invariant=comparison.invariant,
                status=comparison.status,
                source_values=list(comparison.source_values),
                target_values=list(comparison.target_values),
                detail=comparison.detail,
            )
            for comparison in comparisons
        ],
        status="usable" if usable else "rejected",
        notes=list(dict.fromkeys(notes)),
    )
