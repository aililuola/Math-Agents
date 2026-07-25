from __future__ import annotations

from collections import defaultdict, deque
from typing import TYPE_CHECKING, Any, Iterable

from .config import SystemConfig
from .schemas import (
    ClaimCard,
    ClaimStatus,
    EvidenceType,
    InspirationProposal,
    MemoryTier,
    MessageEnvelope,
    VerificationReport,
    VerificationVerdict,
)
from .store import ArtifactStore

if TYPE_CHECKING:
    from .proof_control.claim_lifecycle import ClaimLifecycleController
    from .proof_control.models import ClaimVerificationLedgerEntry


class LemmaMemory:
    """Structured claim store that preserves provenance and never upgrades uncertainty silently."""

    def __init__(self, store: ArtifactStore) -> None:
        self.store = store
        self._claims: dict[str, ClaimCard] = {}
        self._hash_to_id: dict[str, str] = {}
        from .proof_control.claim_lifecycle import ClaimLifecycleController

        self._claim_lifecycle: ClaimLifecycleController = ClaimLifecycleController(
            self._claims
        )

    @property
    def claims(self) -> list[ClaimCard]:
        return list(self._claims.values())

    def add_many(self, claims: Iterable[ClaimCard]) -> list[ClaimCard]:
        added: list[ClaimCard] = []
        for claim in claims:
            existing_id = self._hash_to_id.get(claim.content_hash)
            if existing_id:
                existing = self._claims[existing_id]
                # Merge provenance/evidence without upgrading status.
                known_refs = {e.artifact_ref for e in existing.evidence_refs}
                existing.evidence_refs.extend(
                    e for e in claim.evidence_refs if e.artifact_ref not in known_refs
                )
                existing.self_confidence = max(
                    existing.self_confidence, claim.self_confidence
                )
                existing.scope_limitations = sorted(
                    set(existing.scope_limitations) | set(claim.scope_limitations)
                )
                if existing.source_delta_id is None and claim.source_delta_id:
                    existing.source_delta_id = claim.source_delta_id
                continue
            self._claims[claim.claim_id] = claim
            self._hash_to_id[claim.content_hash] = claim.claim_id
            self._claim_lifecycle.register_claim(claim)
            added.append(claim)
            self.store.append_event("claim_added", claim)
        self._persist()
        return added

    def mark_attempt_verified(
        self, attempt_id: str, report: VerificationReport
    ) -> list[ClaimCard]:
        """Record attempt completeness without projecting it onto child Claims."""

        before = {
            claim.claim_id: claim.status
            for claim in self._claims.values()
            if claim.source_attempt_id == attempt_id
        }
        changed = self._claim_lifecycle.apply_attempt_report(attempt_id, report)
        preserved_ids = [
            claim.claim_id
            for claim in changed
            if claim.status == before.get(claim.claim_id)
        ]
        explicitly_rejected_ids = [
            claim.claim_id
            for claim in changed
            if before.get(claim.claim_id) != ClaimStatus.REJECTED
            and claim.status == ClaimStatus.REJECTED
        ]
        if changed:
            self.store.append_event(
                "attempt_claim_scope_reconciled",
                {
                    "attempt_id": attempt_id,
                    "report_id": report.report_id,
                    "attempt_verdict": report.verdict.value,
                    "preserved_claim_ids": preserved_ids,
                    "explicitly_rejected_claim_ids": explicitly_rejected_ids,
                    "source_attempt_incomplete": (
                        report.verdict != VerificationVerdict.PASS
                    ),
                },
            )
        self._downgrade_invalid_dependency_cycles()
        self._persist()
        return changed

    def apply_claim_report(self, report: VerificationReport) -> ClaimCard | None:
        claim = self._claims.get(report.target_id)
        if claim is None:
            return None
        self._claim_lifecycle.apply_claim_report(report)
        self._downgrade_invalid_dependency_cycles()
        self._persist()
        return claim

    def attach_claim_lifecycle(
        self,
        ledger: dict[str, ClaimVerificationLedgerEntry],
    ) -> ClaimLifecycleController:
        from .proof_control.claim_lifecycle import ClaimLifecycleController

        self._claim_lifecycle = ClaimLifecycleController(self._claims, ledger)
        return self._claim_lifecycle

    def mark_claim_checkpoint_verified(
        self,
        claim_id: str,
        *,
        report_ids: list[str],
        confidence: float,
        independent: bool,
    ) -> ClaimCard:
        self._claim_lifecycle.record_checkpoint_verification(
            claim_id,
            report_ids=report_ids,
            confidence=confidence,
            independent=independent,
        )
        self._downgrade_invalid_dependency_cycles()
        self._persist()
        return self._claims[claim_id]

    def verified(self) -> list[ClaimCard]:
        valid_ids = self._valid_verified_ids()
        return [
            claim
            for claim in self._claims.values()
            if claim.claim_id in valid_ids and claim.status == ClaimStatus.VERIFIED
        ]

    def uncertain(self) -> list[ClaimCard]:
        return [
            claim
            for claim in self._claims.values()
            if claim.status in {ClaimStatus.PROPOSED, ClaimStatus.UNCERTAIN}
        ]

    def rejected(self) -> list[ClaimCard]:
        return [
            claim
            for claim in self._claims.values()
            if claim.status == ClaimStatus.REJECTED
        ]

    def _valid_verified_ids(self) -> set[str]:
        verified = {
            c.claim_id: c
            for c in self._claims.values()
            if c.status == ClaimStatus.VERIFIED
        }
        valid: set[str] = set()
        changed = True
        while changed:
            changed = False
            for claim_id, claim in verified.items():
                if claim_id in valid:
                    continue
                claim_dependencies = [
                    d for d in claim.dependencies if not d.startswith("external:")
                ]
                if all(dep in valid for dep in claim_dependencies):
                    valid.add(claim_id)
                    changed = True
        return valid

    def _downgrade_invalid_dependency_cycles(self) -> None:
        graph: dict[str, list[str]] = defaultdict(list)
        indegree: dict[str, int] = {}
        verified_ids = {
            c.claim_id
            for c in self._claims.values()
            if c.status == ClaimStatus.VERIFIED
        }
        for claim_id in verified_ids:
            indegree.setdefault(claim_id, 0)
        for claim_id in verified_ids:
            claim = self._claims[claim_id]
            for dep in claim.dependencies:
                if dep in verified_ids:
                    graph[dep].append(claim_id)
                    indegree[claim_id] = indegree.get(claim_id, 0) + 1
                elif dep.startswith("external:"):
                    # External dependencies are allowed only when their exact statement and applicability
                    # are carried in the claim's proof/citation packet; the verifier remains responsible.
                    continue
                elif dep in self._claims:
                    # A verified claim depending on a non-verified stored claim is not reusable as verified.
                    self._claim_lifecycle.invalidate_claim(
                        claim.claim_id,
                        reason="dependency_invalidated",
                        evidence_ids=[dep],
                    )
                    limitation = f"dependency is not verified: {dep}"
                    if limitation not in claim.scope_limitations:
                        claim.scope_limitations.append(limitation)
                else:
                    # Missing IDs are never silently ignored. This also catches accidental use of a
                    # local proof-step ID in ClaimCard.dependencies.
                    self._claim_lifecycle.invalidate_claim(
                        claim.claim_id,
                        reason="dependency_invalidated",
                        evidence_ids=[dep],
                    )
                    limitation = f"missing dependency: {dep}"
                    if limitation not in claim.scope_limitations:
                        claim.scope_limitations.append(limitation)
        queue = deque([node for node, degree in indegree.items() if degree == 0])
        visited: set[str] = set()
        while queue:
            node = queue.popleft()
            visited.add(node)
            for nxt in graph.get(node, []):
                indegree[nxt] -= 1
                if indegree[nxt] == 0:
                    queue.append(nxt)
        cycle_nodes = verified_ids - visited
        for claim_id in cycle_nodes:
            self._claim_lifecycle.invalidate_claim(
                claim_id,
                reason="dependency_invalidated",
                evidence_ids=sorted(cycle_nodes),
            )
            limitations = self._claims[claim_id].scope_limitations
            if "dependency cycle detected" not in limitations:
                limitations.append("dependency cycle detected")

    def _persist(self) -> None:
        self.store.write_json(
            "structured",
            "lemma_memory",
            [claim.model_dump(mode="json") for claim in self._claims.values()],
        )


class TypedMemory:
    """Fact/Insight/Negative memory with provenance-preserving promotion gates."""

    def __init__(
        self,
        store: ArtifactStore | None,
        config: SystemConfig | None = None,
        *,
        lemma_memory: LemmaMemory | None = None,
    ) -> None:
        self.store = store
        self.config = config
        self.lemma_memory = lemma_memory or (
            LemmaMemory(store) if store is not None else None
        )
        self._messages: dict[str, MessageEnvelope] = {}
        self._proposals: dict[str, InspirationProposal] = {}
        self._tiers: dict[str, MemoryTier] = {}
        self._content_index: dict[str, str] = {}
        self._provenance: dict[str, set[str]] = defaultdict(set)
        self._invalidated: dict[str, str] = {}

    @property
    def facts(self) -> list[MessageEnvelope]:
        return [
            self._messages[item_id]
            for item_id, tier in self._tiers.items()
            if tier == MemoryTier.FACT and item_id in self._messages
        ]

    @property
    def insights(self) -> list[MessageEnvelope | InspirationProposal]:
        result: list[MessageEnvelope | InspirationProposal] = []
        for item_id, tier in self._tiers.items():
            if tier != MemoryTier.INSIGHT:
                continue
            if item_id in self._messages:
                result.append(self._messages[item_id])
            elif item_id in self._proposals:
                result.append(self._proposals[item_id])
        return result

    @property
    def negatives(self) -> list[MessageEnvelope | InspirationProposal]:
        result: list[MessageEnvelope | InspirationProposal] = []
        for item_id, tier in self._tiers.items():
            if tier != MemoryTier.NEGATIVE:
                continue
            if item_id in self._messages:
                result.append(self._messages[item_id])
            elif item_id in self._proposals:
                result.append(self._proposals[item_id])
        return result

    def add_message(
        self, message: MessageEnvelope, *, referee_agent_id: str | None = None
    ) -> MessageEnvelope:
        existing_id = self._content_index.get(message.content_hash)
        if existing_id is not None:
            existing = self._messages[existing_id]
            existing.artifact_refs = list(
                dict.fromkeys(existing.artifact_refs + message.artifact_refs)
            )
            self._provenance[existing_id].add(message.source_agent_id)
            return existing
        if message.memory_tier == MemoryTier.FACT:
            return self.add_fact(message, referee_agent_id=referee_agent_id)
        if message.memory_tier == MemoryTier.NEGATIVE:
            return self.add_negative(message)
        return self.add_insight(message)

    def add_fact(
        self,
        message: MessageEnvelope,
        *,
        referee_agent_id: str | None = None,
    ) -> MessageEnvelope:
        if message.memory_tier != MemoryTier.FACT:
            raise ValueError("add_fact requires memory_tier=fact")
        if message.verification_status != ClaimStatus.VERIFIED:
            raise ValueError("facts must be independently verified")
        threshold = (
            self.config.topology.typed_memory.fact_pass_threshold
            if self.config is not None
            else 0.8
        )
        if message.verification_confidence < threshold:
            raise ValueError("verification confidence is below the fact gate")
        if message.normalization_confidence < threshold:
            raise ValueError("quantifier/scope normalization is incomplete")
        if message.evidence_type not in {
            EvidenceType.NATURAL_PROOF_AUDITED,
            EvidenceType.EXACT_SYMBOLIC_IDENTITY,
            EvidenceType.COMPLETE_FINITE_ENUMERATION,
            EvidenceType.SAT_SMT_CERTIFICATE,
            EvidenceType.FORMAL_KERNEL_CERTIFICATE,
        }:
            raise ValueError("this evidence cannot establish a reusable fact")
        if referee_agent_id is None or referee_agent_id == message.source_agent_id:
            raise ValueError("facts require an independent referee")
        if not self.dependencies_resolved(message.dependencies):
            raise ValueError("fact dependencies are unresolved")
        if self.would_create_cycle(message.message_id, message.dependencies):
            raise ValueError("fact dependency cycle detected")
        if self.has_counterexample(message.normalized_statement):
            raise ValueError("known counterexample blocks fact promotion")
        result = self._store_message(message, MemoryTier.FACT)
        self._event(
            "fact_promoted",
            {"message_id": message.message_id, "content_hash": message.content_hash},
        )
        return result

    def add_insight(
        self, item: MessageEnvelope | InspirationProposal
    ) -> MessageEnvelope | InspirationProposal:
        if isinstance(item, MessageEnvelope):
            if item.memory_tier != MemoryTier.INSIGHT:
                raise ValueError("add_insight requires memory_tier=insight")
            return self._store_message(item, MemoryTier.INSIGHT)
        existing = self._proposals.get(item.proposal_id)
        if existing is not None:
            return existing
        self._proposals[item.proposal_id] = item
        self._tiers[item.proposal_id] = MemoryTier.INSIGHT
        self._content_index.setdefault(
            item.novelty_signature.normalized_hash, item.proposal_id
        )
        self._provenance[item.proposal_id].add(item.source_agent_id)
        self._persist()
        return item

    def add_negative(
        self,
        item: MessageEnvelope | InspirationProposal,
        *,
        reason: str = "",
    ) -> MessageEnvelope | InspirationProposal:
        if isinstance(item, MessageEnvelope):
            if item.memory_tier != MemoryTier.NEGATIVE:
                raise ValueError("add_negative requires memory_tier=negative")
            result = self._store_message(item, MemoryTier.NEGATIVE)
            if reason:
                self._invalidated[item.message_id] = reason
            self._event(
                "negative_added",
                {"message_id": item.message_id, "reason": reason},
            )
            return result
        self._proposals[item.proposal_id] = item
        self._tiers[item.proposal_id] = MemoryTier.NEGATIVE
        if reason:
            self._invalidated[item.proposal_id] = reason
        self._persist()
        self._event(
            "negative_added",
            {"proposal_id": item.proposal_id, "reason": reason},
        )
        return item

    def _store_message(
        self, message: MessageEnvelope, tier: MemoryTier
    ) -> MessageEnvelope:
        self._messages[message.message_id] = message
        self._tiers[message.message_id] = tier
        self._content_index.setdefault(message.content_hash, message.message_id)
        self._provenance[message.message_id].add(message.source_agent_id)
        self._persist()
        return message

    def facts_for_route(
        self, route_id: str, *, max_items: int | None = None
    ) -> list[MessageEnvelope]:
        limit = max_items or (
            self.config.topology.typed_memory.max_fact_context
            if self.config is not None
            else 32
        )
        return self._for_route(self.facts, route_id)[:limit]

    def insights_for_route(
        self, route_id: str, *, max_items: int | None = None
    ) -> list[MessageEnvelope | InspirationProposal]:
        limit = max_items or (
            self.config.topology.typed_memory.max_insight_context
            if self.config is not None
            else 16
        )
        return self._for_route(self.insights, route_id)[:limit]

    def negatives_for_route(
        self, route_id: str, *, max_items: int | None = None
    ) -> list[MessageEnvelope | InspirationProposal]:
        limit = max_items or (
            self.config.topology.typed_memory.max_negative_context
            if self.config is not None
            else 16
        )
        return self._for_route(self.negatives, route_id, global_negative=True)[:limit]

    @staticmethod
    def _for_route(
        values: list[MessageEnvelope | InspirationProposal],
        route_id: str,
        *,
        global_negative: bool = False,
    ) -> list[MessageEnvelope | InspirationProposal]:
        return [
            item
            for item in values
            if global_negative
            or (
                isinstance(item, MessageEnvelope)
                and (
                    item.source_route_id == route_id
                    or route_id in item.target_route_ids
                )
            )
            or (
                isinstance(item, InspirationProposal)
                and route_id in item.target_route_ids
            )
        ]

    def promote(
        self,
        message_id: str,
        *,
        referee_agent_id: str,
        confidence: float | None = None,
    ) -> MessageEnvelope:
        message = self._messages[message_id]
        if referee_agent_id == message.source_agent_id:
            raise ValueError("the author cannot promote its own insight")
        if message.evidence_type in {
            EvidenceType.NUMERICAL_HEURISTIC,
            EvidenceType.BOUNDED_EXPERIMENT,
            EvidenceType.UNVERIFIED_IDEA,
        }:
            raise ValueError("this evidence cannot be promoted to FactMemory")
        threshold = (
            self.config.topology.typed_memory.fact_pass_threshold
            if self.config is not None
            else 0.8
        )
        final_confidence = (
            message.verification_confidence if confidence is None else confidence
        )
        if final_confidence < threshold:
            raise ValueError("verification confidence is below the fact gate")
        if message.normalization_confidence < threshold:
            raise ValueError("quantifier/scope normalization is incomplete")
        if not self.dependencies_resolved(message.dependencies):
            raise ValueError("dependencies are unresolved")
        if self.would_create_cycle(message.message_id, message.dependencies):
            raise ValueError("fact dependency cycle detected")
        if self.has_counterexample(message.normalized_statement):
            raise ValueError("known counterexample blocks promotion")
        payload = message.model_dump(mode="json")
        old_hash = message.content_hash
        payload.update(
            {
                "verification_status": ClaimStatus.VERIFIED.value,
                "verification_confidence": final_confidence,
                "memory_tier": MemoryTier.FACT.value,
                "content_hash": "",
            }
        )
        message = MessageEnvelope.model_validate(payload)
        self._messages[message_id] = message
        if self._content_index.get(old_hash) == message_id:
            del self._content_index[old_hash]
        self._content_index[message.content_hash] = message_id
        self._tiers[message_id] = MemoryTier.FACT
        self._persist()
        self._event(
            "fact_promoted",
            {
                "message_id": message_id,
                "referee_agent_id": referee_agent_id,
                "verification_confidence": final_confidence,
            },
        )
        return message

    def demote(
        self,
        item_id: str,
        *,
        to_tier: MemoryTier = MemoryTier.INSIGHT,
        reason: str,
    ) -> None:
        if to_tier == MemoryTier.FACT:
            raise ValueError("demote cannot target FactMemory")
        if item_id not in self._tiers:
            raise KeyError(item_id)
        self._tiers[item_id] = to_tier
        self._invalidated[item_id] = reason
        message = self._messages.get(item_id)
        if message is not None:
            old_hash = message.content_hash
            payload = message.model_dump(mode="json")
            payload.update(
                {
                    "memory_tier": to_tier.value,
                    "verification_status": (
                        ClaimStatus.REJECTED.value
                        if to_tier == MemoryTier.NEGATIVE
                        else ClaimStatus.UNCERTAIN.value
                    ),
                    "content_hash": "",
                }
            )
            message = MessageEnvelope.model_validate(payload)
            self._messages[item_id] = message
            if self._content_index.get(old_hash) == item_id:
                del self._content_index[old_hash]
            self._content_index[message.content_hash] = item_id
        self._persist()
        self._event(
            "fact_demoted",
            {"item_id": item_id, "to_tier": to_tier.value, "reason": reason},
        )

    def dependencies_resolved(self, dependencies: Iterable[str]) -> bool:
        fact_ids = {item.message_id for item in self.facts}
        fact_hashes = {item.content_hash for item in self.facts}
        allow_legacy_dependencies = (
            self.config is None or self.config.topology.mode == "legacy_sparse"
        )
        legacy_ids = (
            {item.claim_id for item in self.lemma_memory.verified()}
            if allow_legacy_dependencies and self.lemma_memory is not None
            else set()
        )
        return all(
            dep.startswith("external:")
            or dep in fact_ids
            or dep in fact_hashes
            or dep in legacy_ids
            for dep in dependencies
        )

    def would_create_cycle(self, node_id: str, dependencies: Iterable[str]) -> bool:
        graph: dict[str, list[str]] = {
            item.message_id: list(item.dependencies) for item in self.facts
        }
        graph[node_id] = list(dependencies)

        def reaches(start: str, target: str, seen: set[str]) -> bool:
            if start == target:
                return True
            if start in seen:
                return False
            seen.add(start)
            return any(reaches(item, target, seen) for item in graph.get(start, []))

        return any(reaches(dep, node_id, set()) for dep in graph[node_id])

    def has_counterexample(self, normalized_statement: str) -> bool:
        return any(
            isinstance(item, MessageEnvelope)
            and item.evidence_type == EvidenceType.COUNTEREXAMPLE
            and item.normalized_statement == normalized_statement
            for item in self.negatives
        )

    @staticmethod
    def _normalized_text(value: str) -> str:
        return " ".join(value.casefold().split())

    def _facts_refuted_by_counterexample(
        self, counterexample: MessageEnvelope | str
    ) -> list[MessageEnvelope]:
        if isinstance(counterexample, str):
            target_texts = {self._normalized_text(counterexample)}
            dependency_ids: set[str] = set()
        else:
            target_texts = {
                self._normalized_text(counterexample.normalized_statement),
                self._normalized_text(counterexample.conclusion),
            }
            target_texts.discard("")
            dependency_ids = set(counterexample.dependencies)
        matched: list[MessageEnvelope] = []
        for fact in self.facts:
            fact_texts = {
                self._normalized_text(fact.normalized_statement),
                self._normalized_text(fact.conclusion),
            }
            fact_texts.discard("")
            dependency_match = bool(
                {fact.message_id, fact.content_hash} & dependency_ids
            )
            statement_match = any(
                target == fact_text or target in fact_text or fact_text in target
                for target in target_texts
                for fact_text in fact_texts
            )
            if dependency_match or statement_match:
                matched.append(fact)
        return matched

    def refuted_statements_for_counterexample(
        self, counterexample: MessageEnvelope
    ) -> list[str]:
        values = {
            self._normalized_text(counterexample.normalized_statement),
            self._normalized_text(counterexample.conclusion),
        }
        values.update(
            self._normalized_text(fact.normalized_statement)
            for fact in self._facts_refuted_by_counterexample(counterexample)
        )
        return sorted(value for value in values if value)

    def affected_routes_for_counterexample(
        self, counterexample: MessageEnvelope | str
    ) -> list[str]:
        routes: set[str] = set()
        for fact in self._facts_refuted_by_counterexample(counterexample):
            routes.add(fact.source_route_id)
            routes.update(fact.target_route_ids)
        return sorted(routes)

    def apply_counterexample(self, counterexample: MessageEnvelope) -> list[str]:
        affected = [
            fact.message_id
            for fact in self._facts_refuted_by_counterexample(counterexample)
        ]
        invalidated = self.invalidate_dependents(
            affected,
            reason=f"counterexample:{counterexample.message_id}",
            rejected=True,
        )
        return invalidated

    def invalidate_dependents(
        self,
        item_ids: Iterable[str],
        *,
        reason: str,
        rejected: bool = False,
    ) -> list[str]:
        pending = deque(item_ids)
        invalidated: list[str] = []
        while pending:
            item_id = pending.popleft()
            if item_id in invalidated:
                continue
            if item_id in self._tiers:
                target_tier = MemoryTier.NEGATIVE if rejected else MemoryTier.INSIGHT
                self.demote(item_id, to_tier=target_tier, reason=reason)
                invalidated.append(item_id)
            for fact in list(self.facts):
                if item_id in fact.dependencies or any(
                    self._messages.get(item_id) is not None
                    and self._messages[item_id].content_hash == dep
                    for dep in fact.dependencies
                ):
                    pending.append(fact.message_id)
        if self.store is not None and invalidated:
            self.store.append_event(
                "typed_memory_invalidated",
                {"item_ids": invalidated, "reason": reason},
            )
        return invalidated

    # Legacy compatibility surface.
    def add_many(self, claims: Iterable[ClaimCard]) -> list[ClaimCard]:
        if self.lemma_memory is None:
            raise RuntimeError("legacy LemmaMemory requires an ArtifactStore")
        return self.lemma_memory.add_many(claims)

    def apply_claim_report(self, report: VerificationReport) -> ClaimCard | None:
        if self.lemma_memory is None:
            return None
        return self.lemma_memory.apply_claim_report(report)

    def mark_attempt_verified(
        self, attempt_id: str, report: VerificationReport
    ) -> list[ClaimCard]:
        if self.lemma_memory is None:
            return []
        return self.lemma_memory.mark_attempt_verified(attempt_id, report)

    def verified(self) -> list[ClaimCard]:
        return self.lemma_memory.verified() if self.lemma_memory is not None else []

    def uncertain(self) -> list[ClaimCard]:
        return self.lemma_memory.uncertain() if self.lemma_memory is not None else []

    def rejected(self) -> list[ClaimCard]:
        return self.lemma_memory.rejected() if self.lemma_memory is not None else []

    def export_state(self) -> dict[str, Any]:
        return {
            "messages": {
                key: value.model_dump(mode="json")
                for key, value in self._messages.items()
            },
            "proposals": {
                key: value.model_dump(mode="json")
                for key, value in self._proposals.items()
            },
            "tiers": {key: value.value for key, value in self._tiers.items()},
            "content_index": dict(self._content_index),
            "provenance": {
                key: sorted(value) for key, value in self._provenance.items()
            },
            "invalidated": dict(self._invalidated),
        }

    @classmethod
    def from_state(
        cls,
        state: dict[str, Any],
        *,
        store: ArtifactStore | None,
        config: SystemConfig | None = None,
        lemma_memory: LemmaMemory | None = None,
    ) -> "TypedMemory":
        memory = cls(store, config, lemma_memory=lemma_memory)
        memory._messages = {
            str(key): MessageEnvelope.model_validate(value)
            for key, value in dict(state.get("messages", {})).items()
        }
        memory._proposals = {
            str(key): InspirationProposal.model_validate(value)
            for key, value in dict(state.get("proposals", {})).items()
        }
        memory._tiers = {
            str(key): MemoryTier(value)
            for key, value in dict(state.get("tiers", {})).items()
        }
        memory._content_index = {
            str(key): str(value)
            for key, value in dict(state.get("content_index", {})).items()
        }
        memory._provenance = defaultdict(
            set,
            {
                str(key): set(value)
                for key, value in dict(state.get("provenance", {})).items()
            },
        )
        memory._invalidated = {
            str(key): str(value)
            for key, value in dict(state.get("invalidated", {})).items()
        }
        return memory

    def _persist(self) -> None:
        if self.store is not None:
            self.store.write_json("structured", "typed_memory", self.export_state())

    def _event(self, event_type: str, payload: dict[str, Any]) -> None:
        if self.store is not None:
            self.store.append_event(event_type, payload)
