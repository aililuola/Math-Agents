from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from typing import Literal


AuditStatus = Literal["pass", "fail", "not_applicable"]


@dataclass(frozen=True, slots=True)
class SemanticProfile:
    language: Literal["zh", "en", "unknown"]
    concepts: frozenset[str]
    task_intents: frozenset[str]
    polarities: frozenset[str]
    quantifiers: tuple[str, ...]
    domains: frozenset[str]
    logical_relations: frozenset[str]
    ordered_math_fragments: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class SemanticInvariantComparison:
    invariant: str
    status: AuditStatus
    source_values: tuple[str, ...]
    target_values: tuple[str, ...]
    detail: str


_CJK = re.compile(r"[\u3400-\u9fff]")
_LATIN_WORD = re.compile(r"\b[A-Za-z]{2,}\b")
_MATH_BLOCK = re.compile(r"\$[^$]+\$|\\\([^)]*\\\)|\\\[[^\]]*\\\]")


_CONCEPT_PATTERNS: dict[str, tuple[str, ...]] = {
    "adjacency": (
        r"相邻|邻接",
        r"\b(?:adjacent|neighbou?ring|neighbou?rs?)\b",
    ),
    "object": (
        r"对象|元素",
        r"\b(?:objects?|elements?)\b",
    ),
    "distance": (
        r"距离|间距",
        r"\b(?:distance|distances|gap|gaps)\b",
    ),
    "boundedness": (
        r"有界|无界|界限",
        r"\b(?:bounded|unbounded|finite\s+bound|finite\s+bounds)\b",
    ),
    "representation": (
        r"表示|表象",
        r"\b(?:representation|representations|represent|represents)\b",
    ),
    "mapping": (
        r"映射|函数",
        r"\b(?:map|maps|mapping|mappings|function|functions)\b",
    ),
    "domain": (
        r"定义域",
        r"\bdomains?\b",
    ),
    "order": (
        r"次序|顺序|序关系",
        r"\b(?:order|ordering|ordered)\b",
    ),
    "preservation": (
        r"保持|不变|守恒",
        r"\b(?:preserve|preserves|preserved|preservation|invariant)\b",
    ),
    "interval": (
        r"区间",
        r"\bintervals?\b",
    ),
    "compactness": (
        r"紧致",
        r"\bcompact(?:ness)?\b",
    ),
    "continuity": (
        r"连续",
        r"\bcontinu(?:ous|ity)\b",
    ),
    "convergence": (
        r"收敛",
        r"\bconver(?:ge|ges|gence|gent)\b",
    ),
    "periodicity": (
        r"周期",
        r"\bperiodic(?:ity)?\b",
    ),
    "monotonicity": (
        r"单调|递增|递减",
        r"\b(?:monotone|monotonic|increasing|decreasing)\b",
    ),
    "uniqueness": (
        r"唯一",
        r"\b(?:unique|uniqueness)\b",
    ),
    "finiteness": (
        r"有限",
        r"\bfinite(?:ness)?\b",
    ),
    "infinitude": (
        r"无限",
        r"\binfinite(?:ly|ness)?\b",
    ),
    "sequence": (
        r"数列|序列",
        r"\bsequences?\b",
    ),
    "relation": (
        r"关系",
        r"\brelations?\b",
    ),
    "decomposition": (
        r"分解",
        r"\bdecompos(?:e|es|ition|itions)\b",
    ),
}

_TASK_PATTERNS: dict[str, tuple[str, ...]] = {
    "disprove": (
        r"证伪|否证|反驳|举反例(?:说明|证明).{0,8}不成立",
        r"\b(?:disprove|refute|falsify)\b",
    ),
    "prove": (
        r"证明|证实|论证",
        r"\b(?:prove|show|demonstrate|establish)\b",
    ),
    "compute": (
        r"计算|求值",
        r"\b(?:compute|calculate|evaluate)\b",
    ),
    "find": (
        r"求出|寻找|找出",
        r"\b(?:find|locate)\b",
    ),
    "determine": (
        r"确定|判定",
        r"\bdetermine\b",
    ),
    "classify": (
        r"分类",
        r"\bclassify\b",
    ),
    "construct": (
        r"构造",
        r"\bconstruct\b",
    ),
}

_QUANTIFIER_PATTERNS: tuple[tuple[str, tuple[str, ...]], ...] = (
    (
        "exists_unique",
        (
            r"存在\s*唯一|唯一\s*存在|恰有一个",
            r"\bthere\s+exists\s+(?:a\s+)?unique\b|\bexactly\s+one\b",
        ),
    ),
    (
        "at_least",
        (
            r"至少(?:有)?(?:一个)?",
            r"\bat\s+least(?:\s+one)?\b",
        ),
    ),
    (
        "at_most",
        (
            r"至多(?:有)?(?:一个)?|不超过",
            r"\bat\s+most(?:\s+one)?\b",
        ),
    ),
    (
        "universal",
        (
            r"每个|任意|所有|任一|对一切",
            r"\b(?:every|each|all|any)\b|\bfor\s+all\b",
        ),
    ),
    (
        "existential",
        (
            r"存在|某个|有一个",
            r"\bthere\s+exists\b|\bthere\s+is\b|\bsome\b",
        ),
    ),
)

_DOMAIN_PATTERNS: tuple[tuple[str, tuple[str, ...]], ...] = (
    (
        "positive_integer",
        (
            r"正整数",
            r"\bpositive\s+integers?\b",
        ),
    ),
    (
        "nonnegative_integer",
        (
            r"非负整数",
            r"\bnonnegative\s+integers?\b",
        ),
    ),
    (
        "natural_number",
        (
            r"自然数|\\mathbb\s*\{\s*n\s*\}",
            r"\bnatural\s+(?:number|numbers|integer|integers)\b|"
            r"\\mathbb\s*\{\s*n\s*\}",
        ),
    ),
    (
        "integer",
        (
            r"整数|\\mathbb\s*\{\s*z\s*\}",
            r"\bintegers?\b|\\mathbb\s*\{\s*z\s*\}",
        ),
    ),
    (
        "rational_number",
        (
            r"有理数|\\mathbb\s*\{\s*q\s*\}",
            r"\brational\s+(?:number|numbers)\b|\\mathbb\s*\{\s*q\s*\}",
        ),
    ),
    (
        "real_number",
        (
            r"实数|\\mathbb\s*\{\s*r\s*\}",
            r"\breal\s+(?:number|numbers)\b|\\mathbb\s*\{\s*r\s*\}",
        ),
    ),
    (
        "complex_number",
        (
            r"复数|\\mathbb\s*\{\s*c\s*\}",
            r"\bcomplex\s+(?:number|numbers)\b|\\mathbb\s*\{\s*c\s*\}",
        ),
    ),
)

_LOGICAL_RELATION_PATTERNS: dict[str, tuple[str, ...]] = {
    "equivalence": (
        r"当且仅当|等价于",
        r"\bif\s+and\s+only\s+if\b|\biff\b|\bequivalent\s+to\b",
    ),
    "implication": (
        r"(?:若|如果|假如).{0,300}(?:则|那么|就有|必有)",
        r"\bif\b.{0,300}\bthen\b",
    ),
    "only_if": (
        r"仅当",
        r"\bonly\s+if\b",
    ),
}


def _normalize(value: str) -> str:
    return unicodedata.normalize("NFKC", value).casefold()


def _language(value: str) -> Literal["zh", "en", "unknown"]:
    text = _normalize(value)
    if _CJK.search(text):
        return "zh"
    if _LATIN_WORD.search(text):
        return "en"
    return "unknown"


def _matched_labels(
    text: str,
    patterns: dict[str, tuple[str, ...]],
) -> frozenset[str]:
    return frozenset(
        label
        for label, alternatives in patterns.items()
        if any(re.search(pattern, text, re.IGNORECASE) for pattern in alternatives)
    )


def _ordered_nonoverlapping_labels(
    text: str,
    patterns: tuple[tuple[str, tuple[str, ...]], ...],
) -> tuple[str, ...]:
    candidates: list[tuple[int, int, int, str]] = []
    for priority, (label, alternatives) in enumerate(patterns):
        for pattern in alternatives:
            for match in re.finditer(pattern, text, re.IGNORECASE):
                candidates.append(
                    (match.start(), -(match.end() - match.start()), priority, label)
                )
    selected: list[tuple[int, int, str]] = []
    occupied: list[tuple[int, int]] = []
    for start, negative_length, priority, label in sorted(
        candidates,
        key=lambda item: (item[2], item[0], item[1]),
    ):
        end = start - negative_length
        if any(start < old_end and end > old_start for old_start, old_end in occupied):
            continue
        occupied.append((start, end))
        selected.append((start, priority, label))
    return tuple(label for _start, _priority, label in sorted(selected))


def _polarity(text: str) -> frozenset[str]:
    masked = re.sub(
        r"不超过|不大于|不多于|不小于|不少于|不变量|非负|非零",
        " ",
        text,
    )
    negative = bool(
        re.search(
            r"不存在|无界|不成立|不能|不可|未能|"
            r"不(?!超过|大于|多于|小于|少于|变量)|"
            r"\b(?:not|no|never|cannot|without|unbounded|nonexistent|false)\b|"
            r"\bdoes\s+not\b",
            masked,
            re.IGNORECASE,
        )
    )
    return frozenset({"negative"} if negative else ())


def _math_fragments(text: str) -> tuple[str, ...]:
    return tuple(
        "".join(match.group(0).split()) for match in _MATH_BLOCK.finditer(text)
    )


def extract_semantic_profile(value: str) -> SemanticProfile:
    text = _normalize(value)
    return SemanticProfile(
        language=_language(text),
        concepts=_matched_labels(text, _CONCEPT_PATTERNS),
        task_intents=_matched_labels(text, _TASK_PATTERNS),
        polarities=_polarity(text),
        quantifiers=_ordered_nonoverlapping_labels(text, _QUANTIFIER_PATTERNS),
        domains=frozenset(_ordered_nonoverlapping_labels(text, _DOMAIN_PATTERNS)),
        logical_relations=_matched_labels(text, _LOGICAL_RELATION_PATTERNS),
        ordered_math_fragments=_math_fragments(text),
    )


def _profiles_conflict(left: SemanticProfile, right: SemanticProfile) -> bool:
    exact_sets = (
        (left.task_intents, right.task_intents),
        (left.polarities, right.polarities),
        (left.domains, right.domains),
        (left.logical_relations, right.logical_relations),
    )
    if any(
        (left_values or right_values) and left_values != right_values
        for left_values, right_values in exact_sets
    ):
        return True
    if (left.quantifiers or right.quantifiers) and (
        left.quantifiers != right.quantifiers
    ):
        return True
    directional = bool(
        {"implication", "only_if"} & (left.logical_relations | right.logical_relations)
    )
    return bool(
        directional
        and left.ordered_math_fragments
        and right.ordered_math_fragments
        and left.ordered_math_fragments != right.ordered_math_fragments
    )


def conservatively_matches_across_languages(left: str, right: str) -> bool:
    left_profile = extract_semantic_profile(left)
    right_profile = extract_semantic_profile(right)
    if {left_profile.language, right_profile.language} != {
        "zh",
        "en",
    } or _profiles_conflict(left_profile, right_profile):
        return False
    shared = left_profile.concepts & right_profile.concepts
    if len(shared) < 2:
        return False
    left_coverage = len(shared) / max(1, len(left_profile.concepts))
    right_coverage = len(shared) / max(1, len(right_profile.concepts))
    return min(left_coverage, right_coverage) >= 0.67


def _compare_values(
    invariant: str,
    source_values: tuple[str, ...],
    target_values: tuple[str, ...],
) -> SemanticInvariantComparison:
    if not source_values and not target_values:
        return SemanticInvariantComparison(
            invariant=invariant,
            status="not_applicable",
            source_values=(),
            target_values=(),
            detail=f"{invariant} was not explicitly detected",
        )
    passed = source_values == target_values
    return SemanticInvariantComparison(
        invariant=invariant,
        status="pass" if passed else "fail",
        source_values=source_values,
        target_values=target_values,
        detail=(
            f"{invariant} agrees"
            if passed
            else f"{invariant} differs between source and translation"
        ),
    )


def audit_bilingual_translation(
    source: str,
    translation: str,
) -> tuple[SemanticInvariantComparison, ...]:
    source_profile = extract_semantic_profile(source)
    target_profile = extract_semantic_profile(translation)
    comparisons = [
        _compare_values(
            "task_intent",
            tuple(sorted(source_profile.task_intents)),
            tuple(sorted(target_profile.task_intents)),
        ),
        _compare_values(
            "polarity",
            tuple(sorted(source_profile.polarities)),
            tuple(sorted(target_profile.polarities)),
        ),
        _compare_values(
            "quantifier",
            source_profile.quantifiers,
            target_profile.quantifiers,
        ),
        _compare_values(
            "domain",
            tuple(sorted(source_profile.domains)),
            tuple(sorted(target_profile.domains)),
        ),
        _compare_values(
            "logical_relation",
            tuple(sorted(source_profile.logical_relations)),
            tuple(sorted(target_profile.logical_relations)),
        ),
    ]

    directional = bool(
        {"implication", "only_if"}
        & (source_profile.logical_relations | target_profile.logical_relations)
    )
    if directional:
        comparisons.append(
            _compare_values(
                "logical_relation_order",
                source_profile.ordered_math_fragments,
                target_profile.ordered_math_fragments,
            )
        )
    else:
        comparisons.append(
            SemanticInvariantComparison(
                invariant="logical_relation_order",
                status="not_applicable",
                source_values=source_profile.ordered_math_fragments,
                target_values=target_profile.ordered_math_fragments,
                detail="no directional logical relation was detected",
            )
        )

    source_concepts = source_profile.concepts
    target_concepts = target_profile.concepts
    if len(source_concepts) < 2 and len(target_concepts) < 2:
        comparisons.append(
            SemanticInvariantComparison(
                invariant="semantic_concepts",
                status="not_applicable",
                source_values=tuple(sorted(source_concepts)),
                target_values=tuple(sorted(target_concepts)),
                detail="too few controlled-vocabulary concepts for comparison",
            )
        )
    else:
        shared = source_concepts & target_concepts
        source_coverage = len(shared) / max(1, len(source_concepts))
        target_coverage = len(shared) / max(1, len(target_concepts))
        passed = len(shared) >= 2 and min(source_coverage, target_coverage) >= 0.67
        comparisons.append(
            SemanticInvariantComparison(
                invariant="semantic_concepts",
                status="pass" if passed else "fail",
                source_values=tuple(sorted(source_concepts)),
                target_values=tuple(sorted(target_concepts)),
                detail=(
                    "controlled-vocabulary concepts agree"
                    if passed
                    else "controlled-vocabulary concept coverage is insufficient"
                ),
            )
        )
    return tuple(comparisons)
