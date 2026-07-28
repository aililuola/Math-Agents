from __future__ import annotations

import re
import unicodedata

from ..proof_identity import normalize_text, obligation_identity_text
from ..schemas import ObligationKind, ProofObligation, stable_hash
from .domains import classify_obligation_domain
from .models import (
    ObligationDomain,
    ObligationSemanticQuality,
    ObligationSemanticVerdict,
)


class ObligationSemanticGate:
    """Keep tasks and placeholders out of the mathematical obligation graph.

    The deterministic layer is multilingual (English, Chinese, Unicode math,
    LaTeX). Its verdict is four-state: a statement that is clearly mathematical
    but structurally incomplete is NEEDS_NORMALIZATION (repairable), never
    silently rejected as non-truth-apt.
    """

    _PROPOSITION_PREFIX = re.compile(
        r"^(?:prove|show|establish|verify)\s+(?:that\s+)?",
        re.IGNORECASE,
    )
    _PROPOSITION_PREFIX_CJK = re.compile(
        r"^(?:求证|证明|试证明?|请证明|证)[：:，,、\s]*",
    )
    _ACTION_PREFIX = re.compile(
        r"^(?:find|search|explore|analyze|complete|avoid|choose|"
        r"investigate|try)\b",
        re.IGNORECASE,
    )
    _ACTION_PREFIX_CJK = re.compile(
        r"^(?:寻找|找出|找一个|找到|探索|尝试|分析|研究|考察|枚举|搜索|避免|选择)",
    )
    _RELATION_WORDS = {
        "admits",
        "are",
        "belongs",
        "contains",
        "divides",
        "equals",
        "exists",
        "has",
        "have",
        "holds",
        "iff",
        "implies",
        "intersects",
        "is",
        "preserves",
        "satisfies",
    }
    _RELATION_MARKERS_CJK = (
        "等于",
        "不等于",
        "大于",
        "小于",
        "不超过",
        "不少于",
        "至少",
        "至多",
        "属于",
        "包含",
        "包含于",
        "整除",
        "互素",
        "互质",
        "同余",
        "构成",
        "组成",
        "成立",
        "满足",
        "使得",
        "蕴含",
        "当且仅当",
        "相等",
        "相同",
        "相交",
        "两两",
        "平行",
        "垂直",
        "共圆",
        "共线",
        "收敛",
        "发散",
        "递增",
        "递减",
        "单调",
        "有界",
        "无界",
        "有限",
        "无限",
        "周期",
        "存在",
        "是",
    )
    _QUANTIFIER_MARKERS = (
        "every ",
        "each ",
        "all ",
        "any ",
        "for all ",
        "for every ",
        "for any ",
        "there exists ",
        "there exist ",
        "there is ",
        "there are ",
        "some ",
        "whenever ",
        "if ",
        "eventually",
        "sufficiently large",
        "infinitely many",
        "finitely many",
        "unique ",
    )
    _QUANTIFIER_MARKERS_CJK = (
        "任意",
        "任给",
        "任一",
        "任何",
        "对所有",
        "对每个",
        "对一切",
        "所有",
        "每个",
        "每一个",
        "全体",
        "一切",
        "存在",
        "至少存在",
        "存在唯一",
        "唯一存在",
        "总有",
        "恒成立",
        "充分大",
        "足够大",
        "从某项起",
        "从某一项起",
        "最终",
        "无穷多",
        "有限多",
        "固定的",
        "给定",
        "若",
        "如果",
        "假设",
        "假定",
        "当且仅当",
    )
    # Predicates that carry an implicit universal quantifier over the ambient
    # index/object ("the sequence is strictly increasing" quantifies over n).
    _IMPLICIT_QUANTIFIER_PREDICATES = (
        "递增",
        "递减",
        "单调",
        "有界",
        "无界",
        "收敛",
        "发散",
        "周期",
        "有限",
        "无限",
        "两两互素",
        "increasing",
        "decreasing",
        "monotone",
        "monotonic",
        "bounded",
        "unbounded",
        "convergent",
        "divergent",
        "periodic",
        "finite",
        "infinite",
        "coprime",
        "constant",
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
        "完成论证",
        "完成证明",
        "补全论证",
        "仔细分析",
        "找一个合适的不变量",
        "避免循环依赖",
        "证明该定理",
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
    # ASCII comparison / set / arrow operators plus their Unicode forms. A
    # statement written with "≥" is exactly as relational as one written
    # with ">=".
    _SYMBOL_RELATION = re.compile(
        r"(?:<=|>=|!=|==|=|<|>"
        r"|≤|≥|≠|≡|∈|∉|⊆|⊂|⊇|⊃|∣|∤|∥|⊥"
        r"|→|⇒|↔|⇔|≅|∼|≈|∝|≪|≫)"
    )
    # LaTeX relation commands survive normalize_text/casefold as literal text.
    _LATEX_RELATION = re.compile(
        r"\\(?:le|leq|ge|geq|ne|neq|equiv|in|notin|ni|subseteq|subsetneq"
        r"|subset|supseteq|supset|mid|nmid|divides|rightarrow|leftrightarrow"
        r"|longrightarrow|implies|iff|to|mapsto|sim|simeq|cong|approx"
        r"|parallel|perp|pmod)\b"
    )
    _LATEX_QUANTIFIER = re.compile(r"\\(?:forall|exists)\b")
    _CJK_RUN = re.compile(r"[\u3400-\u9fff]+")
    _CJK_IF_THEN = re.compile(r"(?:若|如果|假设|假定).+?(?:则|那么|就有|必有|可得)")
    _SYMBOLIC_IMPLICATION = re.compile(
        r"(.+?)\s*(?:<=>|<->|=>|->|→|⇒|↔|⇔"
        r"|\\(?:rightarrow|longrightarrow|leftrightarrow|implies|iff|to)\b)"
        r"\s*(.+?)[.]?"
    )
    _WORD_IMPLICATION = re.compile(
        r"(.+?)\s+(?:implies|iff|is\s+equivalent\s+to)\s+(.+?)[.]?"
    )
    _CJK_IMPLICATION = re.compile(r"(.+?)\s*(?:蕴含|当且仅当|等价于)\s*(.+?)[。.]?")

    def assess_statement(
        self,
        statement: str,
        *,
        source_kind: str | None = None,
        executable_first_step: str | None = None,
    ) -> ObligationSemanticQuality:
        normalized = normalize_text(statement)
        return self.assess(
            ProofObligation(
                obligation_id=(
                    "semantic_probe_" + stable_hash(normalized.casefold())[:16]
                ),
                problem_hash="semantic-quality-probe",
                route_ids=[],
                kind=ObligationKind.LEMMA,
                statement=statement,
                normalized_statement=normalized,
            ),
            source_kind=source_kind,
            executable_first_step=executable_first_step,
        )

    @classmethod
    def _cjk_tokens(cls, text: str) -> list[str]:
        tokens: list[str] = []
        for run in cls._CJK_RUN.findall(text):
            if len(run) == 1:
                tokens.append(run)
                continue
            tokens.extend(run[i : i + 2] for i in range(len(run) - 1))
        return tokens

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
        # Compatibility-fold only the parser view. The obligation itself and
        # its content hash retain NFC-normalized source text, preserving v0.7
        # identities while accepting fullwidth mathematical typography.
        parser_text = unicodedata.normalize("NFKC", normalized).translate(
            str.maketrans({"≦": "≤", "≧": "≥"})
        )
        semantic_text = self._PROPOSITION_PREFIX.sub("", parser_text).strip()
        semantic_text = self._PROPOSITION_PREFIX_CJK.sub("", semantic_text).strip()
        latin_tokens = re.findall(r"[a-z][a-z0-9_]*|\d+", semantic_text)
        cjk_tokens = self._cjk_tokens(semantic_text)
        tokens = latin_tokens + cjk_tokens
        content_tokens = [
            token
            for token in latin_tokens
            if token not in self._STOP_WORDS and token not in self._RELATION_WORDS
        ] + [
            token
            for token in cjk_tokens
            if not any(
                token in marker or marker in token
                for marker in self._RELATION_MARKERS_CJK
            )
        ]
        symbolic_relation = bool(
            self._SYMBOL_RELATION.search(semantic_text)
            or self._LATEX_RELATION.search(semantic_text)
        )
        word_relation = (
            bool(set(latin_tokens) & self._RELATION_WORDS)
            or (" if and only if " in f" {semantic_text} ")
            or (semantic_text.startswith("if ") and " then " in semantic_text)
            or any(marker in semantic_text for marker in self._RELATION_MARKERS_CJK)
            or bool(self._CJK_IF_THEN.search(semantic_text))
        )
        has_relation = symbolic_relation or word_relation
        implicit_quantifier = any(
            predicate in semantic_text
            for predicate in self._IMPLICIT_QUANTIFIER_PREDICATES
        )
        explicit_quantifier = (
            bool(obligation.quantifiers)
            or any(marker in f"{semantic_text} " for marker in self._QUANTIFIER_MARKERS)
            or any(marker in semantic_text for marker in self._QUANTIFIER_MARKERS_CJK)
            or bool(self._LATEX_QUANTIFIER.search(semantic_text))
        )
        quantified = explicit_quantifier or implicit_quantifier
        implicit_only = implicit_quantifier and not explicit_quantifier
        has_objects = len(set(content_tokens)) >= 2 or bool(
            symbolic_relation and content_tokens
        )
        pure_action = bool(
            self._ACTION_PREFIX.match(parser_text)
            or self._ACTION_PREFIX_CJK.match(parser_text)
        )
        phrase_placeholder = any(
            phrase in parser_text for phrase in self._PLACEHOLDER_PHRASES
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
        arrow_parts = self._SYMBOLIC_IMPLICATION.fullmatch(semantic_text)
        word_parts = self._WORD_IMPLICATION.fullmatch(semantic_text)
        cjk_parts = re.fullmatch(
            r"(?:若|如果)\s*(.+?)[，,;；]?\s*(?:则|那么)\s*(.+?)[。.]?",
            semantic_text,
        )
        cjk_infix_parts = self._CJK_IMPLICATION.fullmatch(semantic_text)
        implication_parts = (
            logical_parts or arrow_parts or word_parts or cjk_parts or cjk_infix_parts
        )
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

        structural_defects: list[str] = []
        if not truth_apt:
            structural_defects.append("not_truth_apt")
        if not has_objects:
            structural_defects.append("missing_explicit_objects")
        if not has_relation:
            structural_defects.append("missing_explicit_relation")
        if not has_scope:
            structural_defects.append("missing_quantifier_or_scope")

        fatal_defects: list[str] = []
        if domain != ObligationDomain.MATHEMATICAL:
            fatal_defects.append(f"non_mathematical_domain:{domain.value}")
        if is_placeholder:
            fatal_defects.append("placeholder")
        if is_self_implication:
            fatal_defects.append("self_implication")
        if duplicates_main_goal:
            fatal_defects.append("duplicates_main_goal")

        # Four-state verdict. Missing structural metadata on an otherwise
        # mathematical statement is repairable, never a rejection; the absence
        # of an executable first step is an execution-readiness property and
        # never gates semantic admission at all.
        if fatal_defects:
            if domain in {
                ObligationDomain.SEARCH,
                ObligationDomain.PROCESS,
                ObligationDomain.TOOL,
                ObligationDomain.VERIFICATION,
            }:
                verdict = ObligationSemanticVerdict.SEARCH_OR_PROCESS_TASK
            else:
                verdict = ObligationSemanticVerdict.REJECT
        elif not tokens:
            verdict = ObligationSemanticVerdict.REJECT
        elif truth_apt and has_scope:
            verdict = ObligationSemanticVerdict.ACCEPT
        elif has_objects or has_relation:
            verdict = ObligationSemanticVerdict.NEEDS_NORMALIZATION
        else:
            verdict = ObligationSemanticVerdict.REJECT

        if verdict in {
            ObligationSemanticVerdict.REJECT,
            ObligationSemanticVerdict.SEARCH_OR_PROCESS_TASK,
        }:
            rejection_reasons = [*fatal_defects, *structural_defects]
            normalization_needs: list[str] = []
        elif verdict == ObligationSemanticVerdict.NEEDS_NORMALIZATION:
            rejection_reasons = []
            normalization_needs = structural_defects
        else:
            rejection_reasons = []
            normalization_needs = []
            if implicit_only:
                normalization_needs = ["explicit_index_quantifier"]

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
        accepted = verdict == ObligationSemanticVerdict.ACCEPT
        quarantined = verdict in {
            ObligationSemanticVerdict.REJECT,
            ObligationSemanticVerdict.SEARCH_OR_PROCESS_TASK,
        }
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
            semantic_quarantine=quarantined,
            eligible_for_core_debt=accepted,
            eligible_for_bottleneck=accepted,
            verdict=verdict,
            normalization_needs=normalization_needs,
            implicit_quantifiers_detected=implicit_quantifier,
        )
