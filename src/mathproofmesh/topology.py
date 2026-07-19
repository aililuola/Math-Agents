from __future__ import annotations

import json
import re
from dataclasses import dataclass, asdict
from typing import Iterable

from .config import SystemConfig
from .llm.pool import AgentPool, AgentRuntime
from .schemas import ClaimCard, ProofAttempt, StrategyCard, VerificationReport, VerificationVerdict
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


class SparseTopologyRouter:
    """
    Hierarchical sparse topology:
      planner -> isolated explorers -> independent reviewers -> meta-reviewer -> synthesizer -> final verifier.
    Cross-path edges are created only for explicit conflicts or complementary dependencies.
    """

    def __init__(self, config: SystemConfig, pool: AgentPool, store: ArtifactStore) -> None:
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
                    1.0 - jaccard_similarity(strategy_text(candidate), strategy_text(existing))
                    for existing in selected
                )
                feasibility = candidate.estimated_success - 0.2 * candidate.estimated_cost
                return 0.7 * min_distance + 0.3 * feasibility

            chosen = max(remaining, key=diversity_score)
            selected.append(chosen)
            remaining.remove(chosen)
        return selected

    def assign_explorers(self, strategies: list[StrategyCard]) -> list[tuple[StrategyCard, AgentRuntime]]:
        assignments: list[tuple[StrategyCard, AgentRuntime]] = []
        used: set[str] = set()
        for strategy in strategies:
            hints = strategy.tags + [strategy.title]
            try:
                agent = self.pool.select("explorer", exclude=used, specialty_hints=hints)
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
                    prover.provider if self.config.topology.prefer_cross_provider_review else None
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
                jaccard_similarity(query, f"{c.statement} {c.conclusion} {' '.join(c.tags)}"),
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
            if (claim.source_attempt_id or claim.source_agent_id or claim.claim_id) in allowed_sources
        ]

        # Loss-aware context cap. The first/top claim is retained even when unusually
        # large; subsequent claims are admitted only while the claim packet stays within
        # its share of the configured prompt budget.
        char_budget = max(2000, self.config.topology.max_context_chars // 3)
        selected: list[ClaimCard] = []
        used = 0
        for claim in sparse_ranked:
            encoded = json.dumps(claim.model_dump(mode="json"), ensure_ascii=False, separators=(",", ":"))
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
        confidence_spread = max(r.confidence for r in reports) - min(r.confidence for r in reports)
        return min(1.0, 4.0 * variance + 0.25 * confidence_spread)

    def export(self) -> str:
        ref = self.store.write_json("reports", "communication_graph", [e.as_dict() for e in self.edges])
        mermaid = ["flowchart LR"]
        for edge in self.edges:
            source = re.sub(r"[^A-Za-z0-9_]", "_", edge.source)
            target = re.sub(r"[^A-Za-z0-9_]", "_", edge.target)
            label = edge.stage.replace('"', "'")
            mermaid.append(f'  {source}["{edge.source}"] -->|"{label}"| {target}["{edge.target}"]')
        self.store.write_text("reports", "communication_graph", "\n".join(mermaid), suffix=".mmd")
        return ref
