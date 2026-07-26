from __future__ import annotations

import json
import hashlib
import math
import re
from dataclasses import dataclass, asdict
from typing import Iterable

from .config import SystemConfig
from .llm.pool import AgentPool, AgentRuntime
from .schemas import (
    ClaimCard,
    ProofAttempt,
    StrategyCard,
    VerificationReport,
    VerificationVerdict,
)
from .store import ArtifactStore


@dataclass(slots=True)
class CommunicationEdge:
    source: str
    target: str
    stage: str
    payload_type: str
    reason: str
    raw_evidence_included: bool = False

    def as_dict(self) -> dict[str, object]:
        return asdict(self)


def _features(text: str) -> set[str]:
    text = text.lower()
    latin_tokens = set(re.findall(r"[a-z0-9_]+", text))
    cjk = "".join(re.findall(r"[\u3400-\u9fff]", text))
    cjk_bigrams = {cjk[i : i + 2] for i in range(max(0, len(cjk) - 1))}
    symbols = set(re.findall(r"\\[a-zA-Z]+|[∀∃∑∏≤≥≠≈∞]|\bmod\b", text))
    return latin_tokens | cjk_bigrams | symbols


def jaccard_similarity(a: str, b: str) -> float:
    fa, fb = _features(a), _features(b)
    if not fa and not fb:
        return 1.0
    if not fa or not fb:
        return 0.0
    return len(fa & fb) / len(fa | fb)


# Fixed table of LaTeX commands whose operator meaning survives normalization.
_MATH_COMMAND_MAP = {
    "le": "<=",
    "ge": ">=",
    "ne": "!=",
    "in": "∈",
    "subseteq": "⊆",
    "mid": "|",
    "cdot": "*",
    "times": "*",
    "forall": "对任意",
    "exists": "存在",
}

# Multi-letter function words that must never be alpha-renamed and whose LaTeX
# command form (\sin, \gcd, ...) keeps its name instead of being deleted.
_MATH_FUNCTION_WORDS = {
    "sin",
    "cos",
    "tan",
    "log",
    "exp",
    "gcd",
    "lcm",
    "mod",
    "max",
    "min",
    "sum",
    "prod",
    "deg",
    "ord",
}

_LATEX_COMMAND_RE = re.compile(r"\\([a-zA-Z]+)")
_SUBSCRIPT_BRACE_RE = re.compile(r"_\{([^{}]*)\}")
# A candidate variable token: a letter run, optionally with a numeric
# subscript (a_1, x_2). Only single-letter bases are renamed.
_MATH_TOKEN_RE = re.compile(r"[a-zA-Z]+(?:_[0-9]+)?")


def _math_normalize(text: str) -> str:
    """Canonicalize math notation before lexical tokenization.

    LaTeX commands from the fixed table become their operator meaning, other
    backslash commands are deleted while their arguments survive, ``$`` and
    brace markup is stripped, and every distinct single-letter variable (with
    an optional numeric subscript such as ``a_1``) is alpha-renamed to
    ``v1, v2, ...`` in order of first appearance, so that ``f(x)+g(y)`` and
    ``u(s)+w(t)`` normalize identically. Multi-letter identifiers (``sin``,
    ``gcd``, ...) are never renamed.
    """

    def replace_command(match: re.Match[str]) -> str:
        name = match.group(1)
        mapped = _MATH_COMMAND_MAP.get(name.lower())
        if mapped is not None:
            return f" {mapped} "
        if name.lower() in _MATH_FUNCTION_WORDS:
            return f" {name.lower()} "
        return " "

    normalized = _LATEX_COMMAND_RE.sub(replace_command, text)
    normalized = normalized.replace("$", " ")
    normalized = _SUBSCRIPT_BRACE_RE.sub(r"_\1", normalized)
    normalized = normalized.replace("{", " ").replace("}", " ")

    rename: dict[str, str] = {}

    def replace_token(match: re.Match[str]) -> str:
        token = match.group(0)
        base = token.split("_", 1)[0]
        if len(base) > 1:
            return token
        if token not in rename:
            rename[token] = f"v{len(rename) + 1}"
        return rename[token]

    return _MATH_TOKEN_RE.sub(replace_token, normalized)


def _math_embedding(text: str, *, dimensions: int = 128) -> tuple[float, ...]:
    """Build a deterministic local embedding from normalized math features."""

    normalized = _math_normalize(text).casefold()
    tokens = sorted(_features(normalized))
    compact = re.sub(r"\s+", " ", normalized).strip()
    tokens.extend(
        f"tri:{compact[index : index + 3]}" for index in range(max(0, len(compact) - 2))
    )
    vector = [0.0] * dimensions
    for token in tokens:
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        index = int.from_bytes(digest[:4], "big") % dimensions
        sign = 1.0 if digest[4] & 1 else -1.0
        vector[index] += sign
    norm = math.sqrt(sum(value * value for value in vector))
    if norm:
        vector = [value / norm for value in vector]
    return tuple(vector)


def _cosine_similarity(a: tuple[float, ...], b: tuple[float, ...]) -> float:
    if not any(a) and not any(b):
        return 1.0
    if not any(a) or not any(b):
        return 0.0
    return max(0.0, min(1.0, sum(left * right for left, right in zip(a, b))))


def math_similarity(a: str, b: str) -> float:
    """Blend normalized structural overlap with a local math embedding.

    LaTeX markup and variable naming no longer distort the comparison:
    ``$a_{n+1} \\le a_n + C$`` and ``b_{k+1} <= b_k + D`` compare as the same
    mechanism. Used only inside topology's own similarity computations; the
    plain :func:`jaccard_similarity` keeps its historical behavior for other
    modules that import it.
    """

    normalized_a = _math_normalize(a)
    normalized_b = _math_normalize(b)
    structural = jaccard_similarity(normalized_a, normalized_b)
    embedded = _cosine_similarity(_math_embedding(a), _math_embedding(b))
    return 0.8 * structural + 0.2 * embedded


def strategy_text(strategy: StrategyCard) -> str:
    return " ".join(
        [
            strategy.title,
            strategy.core_idea,
            strategy.independence_basis,
            strategy.bottleneck,
            strategy.key_original_step or "",
            " ".join(strategy.expected_lemmas),
            " ".join(strategy.tags),
        ]
    )


def select_sparse_route_neighbors(
    routes: Iterable[tuple[str, StrategyCard]],
    *,
    max_neighbors: int,
) -> dict[str, list[str]]:
    """Build deterministic, relevance-ranked sparse route neighborhoods."""

    items = list(routes)
    if max_neighbors <= 0:
        return {route_id: [] for route_id, _ in items}
    result: dict[str, list[str]] = {}
    for route_id, strategy in items:
        ranked = sorted(
            (
                (
                    jaccard_similarity(
                        strategy_text(strategy), strategy_text(other_strategy)
                    ),
                    other_id,
                )
                for other_id, other_strategy in items
                if other_id != route_id
            ),
            key=lambda item: (-item[0], item[1]),
        )
        result[route_id] = [item[1] for item in ranked[:max_neighbors]]
    return result


class SparseTopologyRouter:
    """
    Hierarchical sparse topology:
      planner -> isolated explorers -> independent reviewers -> meta-reviewer -> synthesizer -> final verifier.
    Cross-path edges are created only for explicit conflicts or complementary dependencies.
    """

    def __init__(
        self, config: SystemConfig, pool: AgentPool, store: ArtifactStore
    ) -> None:
        self.config = config
        self.pool = pool
        self.store = store
        self.edges: list[CommunicationEdge] = []

    def add_edge(
        self,
        source: str,
        target: str,
        stage: str,
        payload_type: str,
        reason: str,
        *,
        raw_evidence_included: bool = False,
    ) -> None:
        edge = CommunicationEdge(
            source=source,
            target=target,
            stage=stage,
            payload_type=payload_type,
            reason=reason,
            raw_evidence_included=raw_evidence_included,
        )
        self.edges.append(edge)
        self.store.append_event("communication_edge", edge.as_dict())

    def select_diverse_strategies(
        self,
        strategies: list[StrategyCard],
        count: int,
    ) -> list[StrategyCard]:
        if len(strategies) <= count:
            return strategies
        # Start with the most feasible strategy, then maximize minimum distance from selected paths.
        remaining = list(strategies)
        first = max(
            remaining,
            key=lambda s: s.estimated_success - 0.25 * s.estimated_cost,
        )
        selected = [first]
        remaining.remove(first)
        while remaining and len(selected) < count:

            def diversity_score(candidate: StrategyCard) -> float:
                min_distance = min(
                    1.0
                    - math_similarity(strategy_text(candidate), strategy_text(existing))
                    for existing in selected
                )
                feasibility = (
                    candidate.estimated_success - 0.2 * candidate.estimated_cost
                )
                return 0.7 * min_distance + 0.3 * feasibility

            chosen = max(remaining, key=diversity_score)
            selected.append(chosen)
            remaining.remove(chosen)
        return selected

    def assign_explorers(
        self, strategies: list[StrategyCard]
    ) -> list[tuple[StrategyCard, AgentRuntime]]:
        assignments: list[tuple[StrategyCard, AgentRuntime]] = []
        used: set[str] = set()
        for strategy in strategies:
            hints = strategy.tags + [strategy.title]
            try:
                agent = self.pool.select(
                    "explorer", exclude=used, specialty_hints=hints
                )
            except RuntimeError:
                agent = self.pool.select("explorer", specialty_hints=hints)
            strategy.assigned_agent_id = agent.id
            assignments.append((strategy, agent))
            used.add(agent.id)
            if len(used) >= len(self.pool.agents):
                used.clear()
            self.add_edge(
                source="planner",
                target=agent.id,
                stage="strategy_assignment",
                payload_type="StrategyCard",
                reason=f"independent path: {strategy.title}",
            )
        return assignments

    def select_reviewers(
        self,
        attempt: ProofAttempt,
        *,
        role: str,
        count: int,
        exclude_extra: set[str] | None = None,
    ) -> list[AgentRuntime]:
        exclude = {attempt.agent_id} | (exclude_extra or set())
        prover = self.pool.get(attempt.agent_id)
        selected: list[AgentRuntime] = []
        for _ in range(count):
            reviewer = self.pool.select(
                role,
                exclude=exclude,
                prefer_provider_not=(
                    prover.provider
                    if self.config.topology.prefer_cross_provider_review
                    else None
                ),
            )
            selected.append(reviewer)
            exclude.add(reviewer.id)
            self.add_edge(
                source=attempt.agent_id,
                target=reviewer.id,
                stage=role,
                payload_type="ProofAttempt",
                reason="independent verification; no reviewer-to-reviewer chat",
                raw_evidence_included=False,
            )
            if len(exclude) >= len(self.pool.agents):
                break
        return selected

    def verification_replicas(
        self,
        attempt: ProofAttempt,
        existing_reports: Iterable[VerificationReport] = (),
    ) -> int:
        risk = 1.0 - attempt.self_confidence
        risk += min(0.3, 0.05 * len(attempt.unresolved_gaps))
        reports = list(existing_reports)
        if reports:
            verdicts = {r.verdict for r in reports}
            if len(verdicts) > 1 or VerificationVerdict.UNCERTAIN in verdicts:
                risk += 0.25
        if risk >= self.config.budget.high_risk_threshold:
            return self.config.budget.high_risk_verifier_replicas
        return self.config.budget.base_verifier_replicas

    def relevant_claims(
        self,
        claims: list[ClaimCard],
        strategy: StrategyCard | None,
        feedback: list[str] | None = None,
    ) -> list[ClaimCard]:
        verified = [c for c in claims if c.status.value == "verified"]
        if not verified:
            return []
        query = " ".join(
            [strategy_text(strategy) if strategy else "", " ".join(feedback or [])]
        )
        ranked = sorted(
            verified,
            key=lambda c: (
                math_similarity(
                    query, f"{c.statement} {c.conclusion} {' '.join(c.tags)}"
                ),
                c.verification_confidence or 0.0,
                c.self_confidence,
            ),
            reverse=True,
        )

        # Sparse cross-path transfer: choose at most neighbor_k source paths, then send
        # only their most relevant verified claims. This prevents a de facto all-to-all
        # broadcast through a shared memory dump.
        source_keys: list[str] = []
        for claim in ranked:
            source = claim.source_attempt_id or claim.source_agent_id or claim.claim_id
            if source not in source_keys:
                source_keys.append(source)
            if len(source_keys) >= self.config.topology.neighbor_k:
                break
        allowed_sources = set(source_keys)
        sparse_ranked = [
            claim
            for claim in ranked
            if (claim.source_attempt_id or claim.source_agent_id or claim.claim_id)
            in allowed_sources
        ]

        # Loss-aware context cap. The first/top claim is retained even when unusually
        # large; subsequent claims are admitted only while the claim packet stays within
        # its share of the configured prompt budget.
        char_budget = max(2000, self.config.topology.max_context_chars // 3)
        selected: list[ClaimCard] = []
        used = 0
        for claim in sparse_ranked:
            encoded = json.dumps(
                claim.model_dump(mode="json"), ensure_ascii=False, separators=(",", ":")
            )
            size = len(encoded)
            if selected and used + size > char_budget:
                continue
            selected.append(claim)
            used += size
            if len(selected) >= self.config.topology.max_verified_claims_per_context:
                break
        return selected

    def pairwise_disagreement(self, reports: list[VerificationReport]) -> float:
        if len(reports) < 2:
            return 0.0
        verdict_values = {
            VerificationVerdict.FAIL: 0.0,
            VerificationVerdict.UNCERTAIN: 0.5,
            VerificationVerdict.PASS: 1.0,
            VerificationVerdict.SKIPPED: 0.5,
        }
        values = [verdict_values[r.verdict] for r in reports]
        mean = sum(values) / len(values)
        variance = sum((x - mean) ** 2 for x in values) / len(values)
        confidence_spread = max(r.confidence for r in reports) - min(
            r.confidence for r in reports
        )
        return min(1.0, 4.0 * variance + 0.25 * confidence_spread)

    def export(self) -> str:
        ref = self.store.write_json(
            "reports", "communication_graph", [e.as_dict() for e in self.edges]
        )
        mermaid = ["flowchart LR"]
        for edge in self.edges:
            source = re.sub(r"[^A-Za-z0-9_]", "_", edge.source)
            target = re.sub(r"[^A-Za-z0-9_]", "_", edge.target)
            label = edge.stage.replace('"', "'")
            mermaid.append(
                f'  {source}["{edge.source}"] -->|"{label}"| {target}["{edge.target}"]'
            )
        self.store.write_text(
            "reports", "communication_graph", "\n".join(mermaid), suffix=".mmd"
        )
        return ref
