from __future__ import annotations

import re
import threading
from enum import Enum
from typing import Any, Iterable

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .config import DeepExplorationPolicyConfig
from .proof_identity import obligation_identity_text
from .schemas import UsageRecord, new_id, stable_hash, utc_now_iso


class ExplorationOutcome(str, Enum):
    RUNNING = "running"
    VERIFIED_PROGRESS = "verified_progress"
    VERIFIED_MECHANISM_CHANGE = "verified_mechanism_change"
    USEFUL_COUNTEREXAMPLE = "useful_counterexample"
    USABLE_PARTIAL = "usable_partial"
    NO_ARTIFACT = "no_artifact"
    NO_VERIFIED_PROGRESS = "no_verified_progress"
    EXTERNAL_FAILURE = "external_failure"
    INTERRUPTED = "interrupted"


class ExplorationModel(BaseModel):
    model_config = ConfigDict(extra="forbid", validate_assignment=True)


def _normalized(values: Iterable[str]) -> list[str]:
    return sorted(
        {
            " ".join(str(value).casefold().split())
            for value in values
            if str(value).strip()
        }
    )


def _tokens(values: Iterable[str]) -> set[str]:
    return set(
        re.findall(
            r"[a-z0-9_]+|[\u4e00-\u9fff]",
            " ".join(values).casefold(),
        )
    )


def _jaccard(left: set[str], right: set[str]) -> float:
    if not left and not right:
        return 1.0
    return len(left & right) / max(1, len(left | right))


class ExplorationSignature(ExplorationModel):
    """A mathematical state signature; route IDs and domains are metadata only."""

    signature_version: int = 2
    problem_hash: str
    verified_checkpoint_id: str
    verified_checkpoint_hash: str
    target_obligation_id: str | None = None
    target_statement: str
    mechanism_tags: list[str] = Field(default_factory=list)
    representation_tags: list[str] = Field(default_factory=list)
    construction_tags: list[str] = Field(default_factory=list)
    invariant_tags: list[str] = Field(default_factory=list)
    transformation_tags: list[str] = Field(default_factory=list)
    assumptions: list[str] = Field(default_factory=list)
    route_id: str | None = None
    recovery_lineage_id: str | None = None
    signature_hash: str = ""

    def canonical_payload(self) -> dict[str, Any]:
        return {
            "signature_version": self.signature_version,
            "problem_hash": self.problem_hash,
            "verified_checkpoint_hash": self.verified_checkpoint_hash,
            "target_statement": obligation_identity_text(self.target_statement),
            "mechanism_tags": _normalized(self.mechanism_tags),
            "representation_tags": _normalized(self.representation_tags),
            "construction_tags": _normalized(self.construction_tags),
            "invariant_tags": _normalized(self.invariant_tags),
            "transformation_tags": _normalized(self.transformation_tags),
            "assumptions": _normalized(self.assumptions),
        }

    @model_validator(mode="after")
    def set_signature_hash(self) -> "ExplorationSignature":
        expected = stable_hash(self.canonical_payload())
        if self.signature_hash and self.signature_hash != expected:
            raise ValueError("deep exploration signature hash mismatch")
        object.__setattr__(self, "signature_hash", expected)
        return self

    def similarity(self, other: "ExplorationSignature") -> tuple[float, float]:
        target = _jaccard(
            _tokens([self.target_statement]),
            _tokens([other.target_statement]),
        )
        own_mechanism = _tokens(
            [
                *self.mechanism_tags,
                *self.representation_tags,
                *self.construction_tags,
                *self.invariant_tags,
                *self.transformation_tags,
            ]
        )
        other_mechanism = _tokens(
            [
                *other.mechanism_tags,
                *other.representation_tags,
                *other.construction_tags,
                *other.invariant_tags,
                *other.transformation_tags,
            ]
        )
        return target, _jaccard(own_mechanism, other_mechanism)


class ExplorationEvidence(ExplorationModel):
    has_verified_checkpoint: bool = False
    explicit_critical_target: bool = False
    meta_approved: bool = False
    final_reserve_available: bool = False
    novelty_review_passed: bool = False
    referee_confirmed_mechanism_change: bool = False


class ExplorationAdmission(ExplorationModel):
    allowed: bool
    signature_hash: str
    requested_tier: int
    granted_tier: int | None = None
    max_output_tokens: int | None = None
    lease_id: str | None = None
    reason: str
    novelty_review_required: bool = False
    recovery_only: bool = False
    parallel_distinct_allowed: bool = True


class ExplorationAttemptRecord(ExplorationModel):
    lease_id: str
    signature: ExplorationSignature
    route_id: str
    round_index: int = Field(ge=0)
    requested_tier: int = Field(ge=0)
    granted_tier: int = Field(ge=0)
    max_output_tokens: int = Field(ge=512)
    recovery_only: bool = False
    outcome: ExplorationOutcome = ExplorationOutcome.RUNNING
    usage: UsageRecord = Field(default_factory=UsageRecord)
    checkpoint_after_hash: str | None = None
    proof_debt_changed: bool = False
    current_goal_changed: bool = False
    reason: str = ""
    started_at: str = Field(default_factory=utc_now_iso)
    finished_at: str | None = None


class BottleneckPivotRecord(ExplorationModel):
    pivot_id: str = Field(default_factory=lambda: new_id("pivot"))
    route_id: str
    parent_signature_hash: str
    new_signature_hash: str
    checkpoint_hash: str
    target_statement: str
    old_mechanism_tags: list[str]
    new_mechanism_tags: list[str]
    referee_confirmed: bool
    created_at: str = Field(default_factory=utc_now_iso)


class DeepExplorationRegistry:
    """Atomic per-signature admission with no cross-signature concurrency quota."""

    _MEANINGFUL = {
        ExplorationOutcome.VERIFIED_PROGRESS,
        ExplorationOutcome.VERIFIED_MECHANISM_CHANGE,
        ExplorationOutcome.USEFUL_COUNTEREXAMPLE,
    }
    _NO_PROGRESS = {
        ExplorationOutcome.USABLE_PARTIAL,
        ExplorationOutcome.NO_ARTIFACT,
        ExplorationOutcome.NO_VERIFIED_PROGRESS,
        ExplorationOutcome.INTERRUPTED,
    }

    def __init__(
        self,
        config: DeepExplorationPolicyConfig,
        *,
        problem_hash: str,
    ) -> None:
        self.config = config
        self.problem_hash = problem_hash
        self.attempts: dict[str, ExplorationAttemptRecord] = {}
        self.running_by_signature: dict[str, list[str]] = {}
        self.strikes: dict[str, int] = {}
        self.locked_signatures: dict[str, str] = {}
        self.partial_repairs: dict[str, int] = {}
        self.recovery_repairs: dict[str, int] = {}
        self.pivots: dict[str, BottleneckPivotRecord] = {}
        self.route_usage: dict[str, UsageRecord] = {}
        self._lock = threading.RLock()

    def recommend_tier(
        self,
        route_id: str,
        evidence: ExplorationEvidence,
        signature: ExplorationSignature,
    ) -> int:
        baseline = self.config.tier_index_for_limit(
            self.config.high_tier_threshold_tokens
        )
        if not evidence.has_verified_checkpoint:
            return baseline
        verified_tiers = self._verified_tiers_for_signature(route_id, signature)
        if not verified_tiers:
            return baseline
        desired = min(max(verified_tiers) + 1, len(self.config.tiers) - 1)
        if self._is_high_tier(desired) and not evidence.explicit_critical_target:
            desired = self._highest_tier_below(self.config.high_tier_threshold_tokens)
        if self._is_128k_tier(desired) and (
            (self.config.require_meta_approval_for_128k and not evidence.meta_approved)
            or not evidence.final_reserve_available
            or not any(self._is_96k_tier(index) for index in verified_tiers)
        ):
            desired = self.config.tier_index_for_limit(96000)
        return desired

    def admit(
        self,
        signature: ExplorationSignature,
        *,
        route_id: str,
        round_index: int,
        evidence: ExplorationEvidence,
        requested_tier: int | None = None,
    ) -> ExplorationAdmission:
        with self._lock:
            if signature.problem_hash != self.problem_hash:
                raise ValueError("exploration signature belongs to another problem")
            maximum = len(self.config.tiers) - 1
            requested = (
                self.recommend_tier(route_id, evidence, signature)
                if requested_tier is None
                else max(0, min(int(requested_tier), maximum))
            )
            signature_hash = signature.signature_hash
            running = self.running_by_signature.get(signature_hash, [])
            if len(running) >= self.config.max_running_per_signature:
                return ExplorationAdmission(
                    allowed=False,
                    signature_hash=signature_hash,
                    requested_tier=requested,
                    reason="the same mathematical state and mechanism already has a running lease",
                )
            recovery_lineage_id = signature.recovery_lineage_id
            if recovery_lineage_id is not None:
                running_recovery = [
                    record
                    for record in self.attempts.values()
                    if record.outcome == ExplorationOutcome.RUNNING
                    and record.signature.recovery_lineage_id == recovery_lineage_id
                ]
                if len(running_recovery) >= self.config.max_running_per_signature:
                    return ExplorationAdmission(
                        allowed=False,
                        signature_hash=signature_hash,
                        requested_tier=requested,
                        reason=(
                            "the same post-failure recovery lineage already has "
                            "a running lease"
                        ),
                    )

            granted = requested
            reason = "evidence-gated tier admitted"
            recovery_only = False
            novelty_required = False

            if recovery_lineage_id is not None:
                repairs = self.recovery_repairs.get(recovery_lineage_id, 0)
                if repairs >= self.config.max_partial_repairs_per_signature:
                    return ExplorationAdmission(
                        allowed=False,
                        signature_hash=signature_hash,
                        requested_tier=requested,
                        reason=(
                            "this post-failure recovery lineage already exhausted "
                            "its one bounded repair"
                        ),
                    )
                repair_tier = self.config.tier_index_for_limit(
                    self.config.partial_repair_max_output_tokens
                )
                granted = min(granted, repair_tier)
                recovery_only = True
                reason = "one bounded repair is allowed for a post-failure lineage"
            elif signature_hash in self.locked_signatures:
                repairs = self.partial_repairs.get(signature_hash, 0)
                if repairs >= self.config.max_partial_repairs_per_signature:
                    return ExplorationAdmission(
                        allowed=False,
                        signature_hash=signature_hash,
                        requested_tier=requested,
                        reason=(
                            "this exact mathematical state already exhausted its "
                            "normal attempt and one bounded repair"
                        ),
                    )
                repair_tier = self.config.tier_index_for_limit(
                    self.config.partial_repair_max_output_tokens
                )
                granted = min(granted, repair_tier)
                recovery_only = True
                reason = "one bounded repair is allowed for a locked signature"

            near_duplicate = (
                None
                if recovery_lineage_id is not None
                else self._near_duplicate(signature)
            )
            if near_duplicate is not None and not evidence.novelty_review_passed:
                repair_tier = self.config.tier_index_for_limit(
                    self.config.partial_repair_max_output_tokens
                )
                granted = min(granted, repair_tier)
                novelty_required = True
                reason = (
                    "semantic similarity is ambiguous; high-tier work awaits a quick "
                    "novelty review instead of being globally blocked"
                )

            if self._is_high_tier(granted) and not evidence.explicit_critical_target:
                granted = self._highest_tier_below(
                    self.config.high_tier_threshold_tokens
                )
                reason = "high-tier work requires one explicit critical local target"
            if (
                self._is_128k_tier(granted)
                and self.config.require_meta_approval_for_128k
            ):
                prior_verified_tiers = self._verified_tiers_for_signature(
                    route_id, signature
                )
                if not any(self._is_96k_tier(index) for index in prior_verified_tiers):
                    granted = self.config.tier_index_for_limit(96000)
                    reason = "128K requires prior verified progress at the 96K tier"
                elif not evidence.meta_approved:
                    granted = self.config.tier_index_for_limit(96000)
                    reason = "128K requires explicit meta-strategy approval"
                elif not evidence.final_reserve_available:
                    granted = self.config.tier_index_for_limit(96000)
                    reason = "128K was deferred to preserve finalization calls"

            tier = self.config.tiers[granted]
            lease_id = new_id("deep_lease")
            record = ExplorationAttemptRecord(
                lease_id=lease_id,
                signature=signature,
                route_id=route_id,
                round_index=round_index,
                requested_tier=requested,
                granted_tier=granted,
                max_output_tokens=tier.output_tokens,
                recovery_only=recovery_only,
            )
            self.attempts[lease_id] = record
            self.running_by_signature.setdefault(signature_hash, []).append(lease_id)
            if recovery_only:
                if recovery_lineage_id is not None:
                    self.recovery_repairs[recovery_lineage_id] = (
                        self.recovery_repairs.get(recovery_lineage_id, 0) + 1
                    )
                else:
                    self.partial_repairs[signature_hash] = (
                        self.partial_repairs.get(signature_hash, 0) + 1
                    )
            return ExplorationAdmission(
                allowed=True,
                signature_hash=signature_hash,
                requested_tier=requested,
                granted_tier=granted,
                max_output_tokens=tier.output_tokens,
                lease_id=lease_id,
                reason=reason,
                novelty_review_required=novelty_required,
                recovery_only=recovery_only,
            )

    def finish(
        self,
        lease_id: str,
        outcome: ExplorationOutcome,
        *,
        usage: UsageRecord | None = None,
        checkpoint_after_hash: str | None = None,
        proof_debt_changed: bool = False,
        current_goal_changed: bool = False,
        reason: str = "",
    ) -> ExplorationAttemptRecord:
        with self._lock:
            record = self.attempts[lease_id]
            if record.outcome != ExplorationOutcome.RUNNING:
                return record
            record.outcome = outcome
            record.usage = usage or UsageRecord()
            record.checkpoint_after_hash = checkpoint_after_hash
            record.proof_debt_changed = proof_debt_changed
            record.current_goal_changed = current_goal_changed
            record.reason = reason
            record.finished_at = utc_now_iso()
            running = self.running_by_signature.get(record.signature.signature_hash, [])
            self.running_by_signature[record.signature.signature_hash] = [
                item for item in running if item != lease_id
            ]
            if not self.running_by_signature[record.signature.signature_hash]:
                self.running_by_signature.pop(record.signature.signature_hash, None)
            self._add_route_usage(record.route_id, record.usage)

            if outcome in self._NO_PROGRESS:
                signature_hash = record.signature.signature_hash
                self.strikes[signature_hash] = self.strikes.get(signature_hash, 0) + 1
                if (
                    self.strikes[signature_hash]
                    >= self.config.no_progress_high_tier_limit_per_signature
                ):
                    self.locked_signatures[signature_hash] = outcome.value
            if (
                outcome
                in {
                    ExplorationOutcome.VERIFIED_PROGRESS,
                    ExplorationOutcome.VERIFIED_MECHANISM_CHANGE,
                }
                and checkpoint_after_hash
                and checkpoint_after_hash != record.signature.verified_checkpoint_hash
            ):
                self._resolve_route_locks(
                    record.route_id,
                    f"new_verified_checkpoint:{checkpoint_after_hash}",
                )
            return record

    def register_pivot(
        self,
        *,
        route_id: str,
        parent_signature_hash: str,
        new_signature: ExplorationSignature,
        referee_confirmed: bool,
    ) -> BottleneckPivotRecord | None:
        with self._lock:
            if (
                not self.config.allow_local_bottleneck_pivot
                or not self.config.reset_on_referee_confirmed_mechanism_change
            ):
                return None
            parent = self._latest_by_signature(parent_signature_hash)
            if parent is None or new_signature.signature_hash == parent_signature_hash:
                return None
            if self.config.require_novelty_review_for_pivot and not referee_confirmed:
                return None
            _, mechanism_similarity = parent.signature.similarity(new_signature)
            if mechanism_similarity >= self.config.semantic_duplicate_threshold:
                return None
            pivot = BottleneckPivotRecord(
                route_id=route_id,
                parent_signature_hash=parent_signature_hash,
                new_signature_hash=new_signature.signature_hash,
                checkpoint_hash=new_signature.verified_checkpoint_hash,
                target_statement=new_signature.target_statement,
                old_mechanism_tags=parent.signature.mechanism_tags,
                new_mechanism_tags=new_signature.mechanism_tags,
                referee_confirmed=referee_confirmed,
            )
            self.pivots[pivot.pivot_id] = pivot
            return pivot

    def export_state(self) -> dict[str, Any]:
        with self._lock:
            return {
                "problem_hash": self.problem_hash,
                "attempts": {
                    key: value.model_dump(mode="json")
                    for key, value in self.attempts.items()
                },
                "running_by_signature": {
                    key: list(value) for key, value in self.running_by_signature.items()
                },
                "strikes": dict(self.strikes),
                "locked_signatures": dict(self.locked_signatures),
                "partial_repairs": dict(self.partial_repairs),
                "recovery_repairs": dict(self.recovery_repairs),
                "pivots": {
                    key: value.model_dump(mode="json")
                    for key, value in self.pivots.items()
                },
                "route_usage": {
                    key: value.model_dump(mode="json")
                    for key, value in self.route_usage.items()
                },
            }

    @classmethod
    def from_state(
        cls,
        state: dict[str, Any],
        config: DeepExplorationPolicyConfig,
        *,
        problem_hash: str,
    ) -> "DeepExplorationRegistry":
        registry = cls(config, problem_hash=problem_hash)
        if str(state.get("problem_hash", problem_hash)) != problem_hash:
            raise ValueError("deep exploration checkpoint belongs to another problem")
        hash_remap: dict[str, str] = {}
        for key, value in dict(state.get("attempts", {})).items():
            payload = dict(value)
            signature_payload = dict(payload.get("signature", {}))
            legacy_hash = str(signature_payload.get("signature_hash", ""))
            if int(signature_payload.get("signature_version", 1)) < 2:
                signature_payload["signature_version"] = 2
                signature_payload["signature_hash"] = ""
            payload["signature"] = signature_payload
            record = ExplorationAttemptRecord.model_validate(payload)
            registry.attempts[str(key)] = record
            if legacy_hash:
                hash_remap[legacy_hash] = record.signature.signature_hash

        def remap_counts(raw: Any) -> dict[str, int]:
            output: dict[str, int] = {}
            for key, value in dict(raw).items():
                mapped = hash_remap.get(str(key), str(key))
                output[mapped] = output.get(mapped, 0) + int(value)
            return output

        registry.strikes = remap_counts(state.get("strikes", {}))
        registry.partial_repairs = remap_counts(state.get("partial_repairs", {}))
        registry.recovery_repairs = {
            str(key): int(value)
            for key, value in dict(state.get("recovery_repairs", {})).items()
        }
        if "recovery_repairs" not in state:
            for record in registry.attempts.values():
                lineage = record.signature.recovery_lineage_id
                if lineage is not None and record.recovery_only:
                    registry.recovery_repairs[lineage] = (
                        registry.recovery_repairs.get(lineage, 0) + 1
                    )
        registry.locked_signatures = {}
        for key, value in dict(state.get("locked_signatures", {})).items():
            registry.locked_signatures[hash_remap.get(str(key), str(key))] = str(value)
        registry.pivots = {}
        for key, value in dict(state.get("pivots", {})).items():
            payload = dict(value)
            payload["parent_signature_hash"] = hash_remap.get(
                str(payload.get("parent_signature_hash", "")),
                str(payload.get("parent_signature_hash", "")),
            )
            payload["new_signature_hash"] = hash_remap.get(
                str(payload.get("new_signature_hash", "")),
                str(payload.get("new_signature_hash", "")),
            )
            registry.pivots[str(key)] = BottleneckPivotRecord.model_validate(payload)
        registry.route_usage = {
            str(key): UsageRecord.model_validate(value)
            for key, value in dict(state.get("route_usage", {})).items()
        }
        # A process cannot safely resume private in-flight reasoning. Durable running
        # leases become interrupted outcomes and retain their high-tier strike.
        for record in registry.attempts.values():
            if record.outcome == ExplorationOutcome.RUNNING:
                record.outcome = ExplorationOutcome.INTERRUPTED
                record.finished_at = utc_now_iso()
                key = record.signature.signature_hash
                registry.strikes[key] = registry.strikes.get(key, 0) + 1
                registry.locked_signatures[key] = "interrupted_on_resume"
        registry.running_by_signature = {}
        return registry

    def summary(self) -> dict[str, Any]:
        return {
            "attempt_count": len(self.attempts),
            "running_signature_count": len(self.running_by_signature),
            "locked_signature_count": len(self.locked_signatures),
            "recovery_lineage_count": len(self.recovery_repairs),
            "pivot_count": len(self.pivots),
            "route_usage": {
                key: value.model_dump(mode="json")
                for key, value in self.route_usage.items()
            },
            "parallel_distinct_signatures_allowed": True,
        }

    def _near_duplicate(
        self, signature: ExplorationSignature
    ) -> ExplorationAttemptRecord | None:
        threshold = self.config.semantic_duplicate_threshold
        for record in reversed(list(self.attempts.values())):
            other = record.signature
            if other.signature_hash == signature.signature_hash:
                continue
            if (
                other.problem_hash != signature.problem_hash
                or other.verified_checkpoint_hash != signature.verified_checkpoint_hash
            ):
                continue
            target_similarity, mechanism_similarity = signature.similarity(other)
            if target_similarity >= threshold and mechanism_similarity >= threshold:
                return record
        return None

    def _verified_tiers_for_signature(
        self,
        route_id: str,
        signature: ExplorationSignature,
    ) -> list[int]:
        tiers: list[int] = []
        threshold = self.config.semantic_duplicate_threshold
        for record in self.attempts.values():
            if record.route_id != route_id or record.outcome not in {
                ExplorationOutcome.VERIFIED_PROGRESS,
                ExplorationOutcome.VERIFIED_MECHANISM_CHANGE,
            }:
                continue
            _, mechanism_similarity = signature.similarity(record.signature)
            if mechanism_similarity >= threshold:
                # max_output_tokens is stable across tier-list migrations (for
                # example the v0.7 removal of 32K); stored numeric indices are not.
                tiers.append(self.config.tier_index_for_limit(record.max_output_tokens))
        return tiers

    def _latest_by_signature(
        self, signature_hash: str
    ) -> ExplorationAttemptRecord | None:
        candidates = [
            item
            for item in self.attempts.values()
            if item.signature.signature_hash == signature_hash
        ]
        return candidates[-1] if candidates else None

    def _resolve_route_locks(self, route_id: str, reason: str) -> None:
        if not self.config.reset_on_verified_checkpoint:
            return
        for signature_hash in list(self.locked_signatures):
            record = self._latest_by_signature(signature_hash)
            if record is not None and record.route_id == route_id:
                self.locked_signatures.pop(signature_hash, None)
                record.reason = " ".join(filter(None, [record.reason, reason]))

    def _add_route_usage(self, route_id: str, usage: UsageRecord) -> None:
        current = self.route_usage.setdefault(route_id, UsageRecord())
        current.input_tokens += usage.input_tokens
        current.output_tokens += usage.output_tokens
        current.total_tokens += usage.total_tokens
        current.estimated_cost_usd += usage.estimated_cost_usd
        current.latency_ms += usage.latency_ms

    def _is_high_tier(self, tier_index: int) -> bool:
        return (
            self.config.tiers[tier_index].output_tokens
            >= self.config.high_tier_threshold_tokens
        )

    def _is_96k_tier(self, tier_index: int) -> bool:
        tokens = self.config.tiers[tier_index].output_tokens
        return 96000 <= tokens < 128000

    def _is_128k_tier(self, tier_index: int) -> bool:
        return self.config.tiers[tier_index].output_tokens >= 128000

    def _highest_tier_below(self, threshold: int) -> int:
        eligible = [
            index
            for index, tier in enumerate(self.config.tiers)
            if tier.output_tokens < threshold
        ]
        return eligible[-1] if eligible else 0
