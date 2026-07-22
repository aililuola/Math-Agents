from __future__ import annotations

from collections import Counter
from pydantic import Field

from ..config import InspirationConfig
from ..schemas import (
    InspirationMechanism,
    InspirationTask,
    InspirationTrigger,
    InspirationTriggerType,
    NoveltySignature,
    StrictModel,
    stable_hash,
)


class InspirationSnapshot(StrictModel):
    round_index: int = Field(ge=0)
    domain: str = "unknown"
    active_route_ids: list[str] = Field(default_factory=list)
    failed_route_ids: list[str] = Field(default_factory=list)
    stagnation_rounds_by_route: dict[str, int] = Field(default_factory=dict)
    verified_fact_gain_recent: int = Field(default=0, ge=0)
    proof_debt_by_route: dict[str, float] = Field(default_factory=dict)
    proof_debt_reduction_recent: float = 0.0
    proof_debt_history: list[float] = Field(default_factory=list)
    first_error_fingerprints: list[str] = Field(default_factory=list)
    route_redundancy: float = Field(default=0.0, ge=0.0, le=1.0)
    shared_bottleneck_ids: list[str] = Field(default_factory=list)
    route_budget_share: dict[str, float] = Field(default_factory=dict)
    message_utility_by_route: dict[str, float] = Field(default_factory=dict)
    unresolved_conflict_ids: list[str] = Field(default_factory=list)
    final_repair_failed: bool = False
    manual_trigger: bool = False
    manual_trigger_route_ids: list[str] = Field(default_factory=list)
    manual_evidence_refs: list[str] = Field(default_factory=list)
    remaining_calls: int = Field(default=0, ge=0)
    finalization_reserve_calls: int = Field(default=0, ge=0)
    current_path_count: int = Field(default=0, ge=0)
    max_paths: int = Field(default=0, ge=0)
    route_signatures: list[NoveltySignature] = Field(default_factory=list)
    open_obligation_ids: list[str] = Field(default_factory=list)


class TriggerPolicy:
    def __init__(self, config: InspirationConfig) -> None:
        self.config = config

    def detect(self, snapshot: InspirationSnapshot) -> list[InspirationTrigger]:
        triggers: list[InspirationTrigger] = []
        affected_stalled = [
            route_id
            for route_id, rounds in snapshot.stagnation_rounds_by_route.items()
            if rounds >= self.config.stagnation_rounds
        ]
        if (
            affected_stalled
            and snapshot.verified_fact_gain_recent < self.config.minimum_verified_gain
        ):
            triggers.append(
                self._trigger(
                    InspirationTriggerType.STAGNATION,
                    snapshot,
                    affected_stalled,
                    "verified fact gain stayed below the configured threshold",
                )
            )
        if (
            snapshot.proof_debt_by_route
            and len(snapshot.proof_debt_history) >= 2
            and snapshot.proof_debt_reduction_recent
            < self.config.proof_debt_min_reduction
        ):
            triggers.append(
                self._trigger(
                    InspirationTriggerType.PROOF_DEBT_PLATEAU,
                    snapshot,
                    list(snapshot.proof_debt_by_route),
                    "proof debt reduction plateaued",
                )
            )
        overconcentrated = [
            route_id
            for route_id, share in snapshot.route_budget_share.items()
            if share >= self.config.route_budget_share_trigger
            and snapshot.stagnation_rounds_by_route.get(route_id, 0) > 0
        ]
        if (
            overconcentrated
            and snapshot.verified_fact_gain_recent < self.config.minimum_verified_gain
        ):
            triggers.append(
                self._trigger(
                    InspirationTriggerType.STAGNATION,
                    snapshot,
                    overconcentrated,
                    "a route consumed too much budget without verified progress",
                    evidence_refs=["budget_concentration"],
                )
            )
        repeated = [
            key
            for key, count in Counter(snapshot.first_error_fingerprints).items()
            if key and count >= self.config.repeated_error_threshold
        ]
        if repeated:
            triggers.append(
                self._trigger(
                    InspirationTriggerType.REPEATED_FIRST_ERROR,
                    snapshot,
                    snapshot.active_route_ids,
                    "the same first-error fingerprint recurred",
                    evidence_refs=repeated,
                )
            )
        if self.config.shared_bottleneck_trigger and snapshot.shared_bottleneck_ids:
            triggers.append(
                self._trigger(
                    InspirationTriggerType.SHARED_BOTTLENECK,
                    snapshot,
                    snapshot.active_route_ids,
                    "multiple routes share an open proof obligation",
                    evidence_refs=snapshot.shared_bottleneck_ids,
                )
            )
        if (
            self.config.all_routes_failed_trigger
            and snapshot.active_route_ids
            and set(snapshot.active_route_ids) <= set(snapshot.failed_route_ids)
        ):
            triggers.append(
                self._trigger(
                    InspirationTriggerType.ALL_ROUTES_FAILED,
                    snapshot,
                    snapshot.active_route_ids,
                    "all active routes failed independent verification",
                )
            )
        if snapshot.route_redundancy >= self.config.route_redundancy_trigger:
            triggers.append(
                self._trigger(
                    InspirationTriggerType.HIGH_ROUTE_REDUNDANCY,
                    snapshot,
                    snapshot.active_route_ids,
                    "route mechanisms are too similar",
                )
            )
        if self.config.final_repair_failure_trigger and snapshot.final_repair_failed:
            triggers.append(
                self._trigger(
                    InspirationTriggerType.FINAL_REPAIR_FAILED,
                    snapshot,
                    snapshot.active_route_ids,
                    "the final targeted repair failed",
                )
            )
        if snapshot.manual_trigger:
            triggers.append(
                self._trigger(
                    InspirationTriggerType.MANUAL,
                    snapshot,
                    snapshot.manual_trigger_route_ids or snapshot.active_route_ids,
                    "an explicit route-local bottleneck requires a mechanism change",
                    evidence_refs=snapshot.manual_evidence_refs,
                )
            )
        return triggers

    def _trigger(
        self,
        trigger_type: InspirationTriggerType,
        snapshot: InspirationSnapshot,
        routes: list[str],
        reason: str,
        *,
        evidence_refs: list[str] | None = None,
    ) -> InspirationTrigger:
        fingerprint = stable_hash(
            {
                "type": trigger_type.value,
                "round": snapshot.round_index,
                "routes": sorted(routes),
                "evidence": sorted(evidence_refs or []),
            }
        )
        return InspirationTrigger(
            trigger_id=f"trigger_{fingerprint[:12]}",
            trigger_type=trigger_type,
            round_index=snapshot.round_index,
            affected_route_ids=list(dict.fromkeys(routes)),
            evidence_refs=evidence_refs or [],
            proof_debt_before=sum(snapshot.proof_debt_by_route.values()),
            verified_gain_recent=snapshot.verified_fact_gain_recent,
            repeated_error_fingerprints=list(
                dict.fromkeys(snapshot.first_error_fingerprints)
            ),
            reason=reason,
        )

    def select_tasks(
        self,
        triggers: list[InspirationTrigger],
        snapshot: InspirationSnapshot,
        mechanism_history: dict[str, dict[str, int | float | str]] | None = None,
    ) -> list[InspirationTask]:
        mapping = {
            InspirationTriggerType.SHARED_BOTTLENECK: (
                InspirationMechanism.REVERSE_GOAL_ANALYSIS,
                InspirationMechanism.BRIDGE_LEMMA,
            ),
            InspirationTriggerType.HIGH_ROUTE_REDUNDANCY: (
                InspirationMechanism.REPRESENTATION_SWITCH,
                InspirationMechanism.SURPRISE_EXPLORATION,
            ),
            InspirationTriggerType.REPEATED_FIRST_ERROR: (
                InspirationMechanism.AUXILIARY_CONSTRUCTION,
                InspirationMechanism.STRUCTURAL_ANALOGY,
                InspirationMechanism.META_REPLAN,
            ),
            InspirationTriggerType.ALL_ROUTES_FAILED: (
                InspirationMechanism.REPRESENTATION_SWITCH,
                InspirationMechanism.SURPRISE_EXPLORATION,
            ),
            InspirationTriggerType.PROOF_DEBT_PLATEAU: (
                InspirationMechanism.META_REPLAN,
                InspirationMechanism.INVARIANT_HYPOTHESIS,
                InspirationMechanism.AUXILIARY_CONSTRUCTION,
            ),
            InspirationTriggerType.FINAL_REPAIR_FAILED: (
                InspirationMechanism.META_REPLAN,
                InspirationMechanism.REPRESENTATION_SWITCH,
            ),
            InspirationTriggerType.STAGNATION: (
                InspirationMechanism.REVERSE_GOAL_ANALYSIS,
                InspirationMechanism.REPRESENTATION_SWITCH,
            ),
            InspirationTriggerType.MANUAL: (
                InspirationMechanism.REPRESENTATION_SWITCH,
                InspirationMechanism.STRUCTURAL_ANALOGY,
                InspirationMechanism.AUXILIARY_CONSTRUCTION,
            ),
        }
        enabled = {
            InspirationMechanism.REPRESENTATION_SWITCH: self.config.representation_switchboard,
            InspirationMechanism.STRUCTURAL_ANALOGY: self.config.analogy_agent,
            InspirationMechanism.AUXILIARY_CONSTRUCTION: self.config.auxiliary_construction_inventor,
            InspirationMechanism.INVARIANT_HYPOTHESIS: self.config.invariant_hypothesis_agent,
            InspirationMechanism.REVERSE_GOAL_ANALYSIS: self.config.reverse_goal_analysis,
            InspirationMechanism.BRIDGE_LEMMA: self.config.bridge_lemma_generator,
            InspirationMechanism.SURPRISE_EXPLORATION: self.config.surprise_exploration,
            InspirationMechanism.META_REPLAN: self.config.persistent_meta_strategist,
        }
        if not triggers:
            return []
        history = mechanism_history or {}
        candidates: list[tuple[int, InspirationTrigger, InspirationMechanism]] = []
        seen: set[InspirationMechanism] = set()
        for trigger in triggers:
            for mechanism in mapping[trigger.trigger_type]:
                if mechanism in seen or not enabled[mechanism]:
                    continue
                seen.add(mechanism)
                candidates.append((len(candidates), trigger, mechanism))
        # Stagnation must not repeatedly consume only the first two mapped
        # mechanisms. Add every enabled mechanism as a fair-rotation fallback,
        # while retaining the first trigger as the auditable cause.
        primary_trigger = triggers[0]
        for mechanism, is_enabled in enabled.items():
            if not is_enabled or mechanism in seen:
                continue
            seen.add(mechanism)
            candidates.append((len(candidates), primary_trigger, mechanism))

        def priority(
            item: tuple[int, InspirationTrigger, InspirationMechanism],
        ) -> tuple[int, int, int, int]:
            source_rank, _trigger, mechanism = item
            stat = history.get(mechanism.value, {})
            selected = int(stat.get("selected_count", 0) or 0)
            no_gain = int(stat.get("consecutive_no_verified_gain", 0) or 0)
            raw_last_round = stat.get("last_selected_round", -1)
            last_round = -1 if raw_last_round is None else int(raw_last_round)
            return (
                0 if selected == 0 else 1,
                1 if no_gain >= 2 else 0,
                last_round,
                source_rank,
            )

        candidates.sort(key=priority)
        tasks: list[InspirationTask] = []
        for _source_rank, trigger, mechanism in candidates:
            task_hash = stable_hash((trigger.trigger_id, mechanism.value))
            tasks.append(
                InspirationTask(
                    task_id=f"inspiration_task_{task_hash[:12]}",
                    trigger_id=trigger.trigger_id,
                    mechanism=mechanism,
                    target_route_ids=trigger.affected_route_ids,
                    target_obligation_ids=(
                        trigger.evidence_refs
                        if trigger.trigger_type
                        == InspirationTriggerType.SHARED_BOTTLENECK
                        else snapshot.open_obligation_ids
                    ),
                    reason=trigger.reason,
                    max_proposals=self.config.max_proposals_per_task,
                )
            )
            if len(tasks) >= self.config.max_inspiration_tasks_per_round:
                return tasks
        return tasks
