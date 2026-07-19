from __future__ import annotations

from collections import defaultdict, deque
from typing import Iterable

from .schemas import ClaimCard, ClaimStatus, VerificationReport, VerificationVerdict
from .store import ArtifactStore


class LemmaMemory:
    """Structured claim store that preserves provenance and never upgrades uncertainty silently."""

    def __init__(self, store: ArtifactStore) -> None:
        self.store = store
        self._claims: dict[str, ClaimCard] = {}
        self._hash_to_id: dict[str, str] = {}

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
                continue
            self._claims[claim.claim_id] = claim
            self._hash_to_id[claim.content_hash] = claim.claim_id
            added.append(claim)
            self.store.append_event("claim_added", claim)
        self._persist()
        return added

    def mark_attempt_verified(
        self, attempt_id: str, report: VerificationReport
    ) -> list[ClaimCard]:
        changed: list[ClaimCard] = []
        for claim in self._claims.values():
            if claim.source_attempt_id != attempt_id:
                continue
            if report.verdict == VerificationVerdict.PASS:
                claim.status = ClaimStatus.VERIFIED
                claim.verification_confidence = report.confidence
            elif report.verdict == VerificationVerdict.FAIL:
                claim.status = ClaimStatus.REJECTED
                claim.verification_confidence = report.confidence
            else:
                claim.status = ClaimStatus.UNCERTAIN
                claim.verification_confidence = report.confidence
            changed.append(claim)
        self._downgrade_invalid_dependency_cycles()
        self._persist()
        return changed

    def apply_claim_report(self, report: VerificationReport) -> ClaimCard | None:
        claim = self._claims.get(report.target_id)
        if claim is None:
            return None
        if report.verdict == VerificationVerdict.PASS:
            claim.status = ClaimStatus.VERIFIED
        elif report.verdict == VerificationVerdict.FAIL:
            claim.status = ClaimStatus.REJECTED
        else:
            claim.status = ClaimStatus.UNCERTAIN
        claim.verification_confidence = report.confidence
        self._downgrade_invalid_dependency_cycles()
        self._persist()
        return claim

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
                    claim.status = ClaimStatus.UNCERTAIN
                    limitation = f"dependency is not verified: {dep}"
                    if limitation not in claim.scope_limitations:
                        claim.scope_limitations.append(limitation)
                else:
                    # Missing IDs are never silently ignored. This also catches accidental use of a
                    # local proof-step ID in ClaimCard.dependencies.
                    claim.status = ClaimStatus.UNCERTAIN
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
            self._claims[claim_id].status = ClaimStatus.UNCERTAIN
            limitations = self._claims[claim_id].scope_limitations
            if "dependency cycle detected" not in limitations:
                limitations.append("dependency cycle detected")

    def _persist(self) -> None:
        self.store.write_json(
            "structured",
            "lemma_memory",
            [claim.model_dump(mode="json") for claim in self._claims.values()],
        )
