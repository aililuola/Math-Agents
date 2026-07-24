from __future__ import annotations

from ..communication.route_registry import RouteRegistry
from ..config import InspirationConfig
from ..schemas import (
    InspirationMechanism,
    InspirationTask,
    MetaDirective,
    MetaDirectiveAction,
    MetaDirectiveAudit,
    MetaDirectiveExecution,
    MetaStrategyDecision,
    RouteStatus,
    stable_hash,
)
from .trigger_policy import InspirationSnapshot


_ACTION_MAP = {
    "continue_current_mechanism": MetaDirectiveAction.CONTINUE,
    "local_repair": MetaDirectiveAction.REPAIR,
    "rewrite_plan": MetaDirectiveAction.REWRITE_PLAN,
    "switch_representation": MetaDirectiveAction.SWITCH_REPRESENTATION,
    "search_analogy": MetaDirectiveAction.REWRITE_PLAN,
    "invent_auxiliary_construction": MetaDirectiveAction.REPAIR,
    "surprise_exploration": MetaDirectiveAction.ALLOCATE_SURPRISE_BUDGET,
    "merge_route": MetaDirectiveAction.MERGE_ROUTES,
    "split_route": MetaDirectiveAction.REWRITE_PLAN,
    "cooldown_route": MetaDirectiveAction.COOLDOWN_ROUTE,
    "abandon_route": MetaDirectiveAction.ABANDON_ROUTE,
}


class MetaDirectiveController:
    """Audit and execute strategy control without admitting it as proof evidence."""

    def __init__(
        self,
        config: InspirationConfig,
        route_registry: RouteRegistry,
    ) -> None:
        self.config = config
        self.route_registry = route_registry

    def from_decision(
        self,
        decision: MetaStrategyDecision,
        snapshot: InspirationSnapshot,
    ) -> MetaDirective:
        action = _ACTION_MAP[decision.action]
        mandatory = bool(
            snapshot.final_repair_failed
            or action
            in {
                MetaDirectiveAction.COOLDOWN_ROUTE,
                MetaDirectiveAction.ABANDON_ROUTE,
            }
        )
        return MetaDirective(
            directive_id=(
                "meta_directive_"
                + stable_hash(
                    (
                        decision.decision_id,
                        decision.round_index,
                        action.value,
                        sorted(decision.affected_route_ids),
                    )
                )[:12]
            ),
            source_decision_id=decision.decision_id,
            round_index=decision.round_index,
            action=action,
            route_ids=list(dict.fromkeys(decision.affected_route_ids)),
            selected_mechanism=decision.selected_mechanism,
            observable_evidence=dict(decision.observable_metrics),
            reason=decision.reason,
            mandatory=mandatory,
            estimated_calls=decision.estimated_calls,
            expires_round=(
                decision.round_index + self.config.meta_directive_expiry_rounds
            ),
        )

    def audit(
        self,
        directive: MetaDirective,
        snapshot: InspirationSnapshot,
    ) -> MetaDirectiveAudit:
        known = {route.route_id: route for route in self.route_registry.routes}
        targets_valid = all(route_id in known for route_id in directive.route_ids)
        evidence_complete = bool(directive.reason and directive.observable_evidence)
        budget_safe = (
            directive.estimated_calls <= self.config.meta_directive_max_estimated_calls
            and directive.estimated_calls
            <= max(
                0,
                snapshot.remaining_calls - snapshot.finalization_reserve_calls,
            )
        )
        reasons: list[str] = []
        if directive.round_index > snapshot.round_index:
            reasons.append("directive originates in a future round")
        if directive.expires_round < snapshot.round_index:
            reasons.append("directive expired before execution")
        if not targets_valid:
            reasons.append("one or more target routes are unknown")
        if not evidence_complete:
            reasons.append("observable evidence or reason is missing")
        if not budget_safe:
            reasons.append("directive would consume protected finalization budget")
        if (
            directive.action
            in {
                MetaDirectiveAction.COOLDOWN_ROUTE,
                MetaDirectiveAction.ABANDON_ROUTE,
                MetaDirectiveAction.MERGE_ROUTES,
            }
            and not directive.route_ids
        ):
            reasons.append("route-mutating directive has no target")
        if (
            directive.action == MetaDirectiveAction.MERGE_ROUTES
            and len(directive.route_ids) < 2
        ):
            reasons.append("merge requires two distinct routes")
        if (
            directive.action == MetaDirectiveAction.MERGE_ROUTES
            and snapshot.route_redundancy < self.config.route_redundancy_trigger
        ):
            reasons.append("merge lacks measured route redundancy")
        if directive.action == MetaDirectiveAction.ABANDON_ROUTE:
            if not self.config.meta_allow_route_abandon:
                reasons.append("route abandonment is disabled")
            failed = set(snapshot.failed_route_ids)
            if any(route_id not in failed for route_id in directive.route_ids):
                reasons.append("only independently failed routes may be abandoned")
            survivors = [
                route
                for route in self.route_registry.active_routes(snapshot.round_index)
                if route.route_id not in directive.route_ids
            ]
            if not survivors:
                reasons.append("abandonment would leave no active route")
        if directive.action == MetaDirectiveAction.COOLDOWN_ROUTE:
            actionable = any(
                snapshot.stagnation_rounds_by_route.get(route_id, 0) > 0
                or route_id in snapshot.failed_route_ids
                for route_id in directive.route_ids
            )
            if not actionable:
                reasons.append("cooldown lacks a stalled or failed target")
        accepted = not reasons
        return MetaDirectiveAudit(
            directive_id=directive.directive_id,
            accepted=accepted,
            evidence_complete=evidence_complete,
            targets_valid=targets_valid,
            budget_safe=budget_safe,
            reason="; ".join(reasons)
            if reasons
            else "directive passed all control gates",
        )

    def execute(
        self,
        directive: MetaDirective,
        audit: MetaDirectiveAudit,
        snapshot: InspirationSnapshot,
        *,
        trigger_id: str,
    ) -> tuple[MetaDirectiveExecution, list[InspirationTask]]:
        if not audit.accepted:
            return (
                MetaDirectiveExecution(
                    directive_id=directive.directive_id,
                    status="rejected",
                    reason=audit.reason,
                ),
                [],
            )
        if directive.action == MetaDirectiveAction.CONTINUE:
            return (
                MetaDirectiveExecution(
                    directive_id=directive.directive_id,
                    status="noop",
                    reason="current mechanism retained",
                ),
                [],
            )
        if directive.action == MetaDirectiveAction.COOLDOWN_ROUTE:
            affected: list[str] = []
            for route_id in directive.route_ids:
                route = self.route_registry.get(route_id)
                if route.status not in {
                    RouteStatus.ACTIVE,
                    RouteStatus.REPAIR_ONCE,
                }:
                    continue
                self.route_registry.mark_cooling(
                    route_id,
                    snapshot.round_index + self.config.meta_directive_cooldown_rounds,
                    directive.reason,
                )
                affected.append(route_id)
            return (
                MetaDirectiveExecution(
                    directive_id=directive.directive_id,
                    status="executed" if affected else "noop",
                    affected_route_ids=affected,
                    reason="stalled routes entered audited cooldown",
                ),
                [],
            )
        if directive.action == MetaDirectiveAction.ABANDON_ROUTE:
            affected = []
            for route_id in directive.route_ids:
                route = self.route_registry.get(route_id)
                if route.status not in {
                    RouteStatus.ACTIVE,
                    RouteStatus.REPAIR_ONCE,
                }:
                    continue
                self.route_registry.mark_abandoned(route_id, directive.reason)
                affected.append(route_id)
            return (
                MetaDirectiveExecution(
                    directive_id=directive.directive_id,
                    status="executed" if affected else "noop",
                    affected_route_ids=affected,
                    reason="independently failed routes were abandoned",
                ),
                [],
            )
        if directive.action == MetaDirectiveAction.MERGE_ROUTES:
            target_id, source_id = directive.route_ids[0], directive.route_ids[1]
            self.route_registry.merge_routes(source_id, target_id)
            return (
                MetaDirectiveExecution(
                    directive_id=directive.directive_id,
                    status="executed",
                    affected_route_ids=[source_id, target_id],
                    reason="duplicate routes were deterministically merged",
                ),
                [],
            )

        mechanism = self._mechanism_for(directive)
        task_id = (
            "inspiration_task_"
            + stable_hash((directive.directive_id, mechanism.value))[:12]
        )
        task = InspirationTask(
            task_id=task_id,
            trigger_id=trigger_id,
            mechanism=mechanism,
            target_route_ids=list(directive.route_ids),
            target_obligation_ids=list(snapshot.open_obligation_ids),
            reason=f"MetaDirective {directive.action.value}: {directive.reason}",
            max_proposals=self.config.max_proposals_per_task,
        )
        return (
            MetaDirectiveExecution(
                directive_id=directive.directive_id,
                status="executed",
                affected_route_ids=list(directive.route_ids),
                generated_task_ids=[task.task_id],
                reason="directive generated a scheduler-admissible mechanism task",
            ),
            [task],
        )

    @staticmethod
    def _mechanism_for(directive: MetaDirective) -> InspirationMechanism:
        if directive.action == MetaDirectiveAction.SWITCH_REPRESENTATION:
            return InspirationMechanism.REPRESENTATION_SWITCH
        if directive.action == MetaDirectiveAction.ALLOCATE_SURPRISE_BUDGET:
            return InspirationMechanism.SURPRISE_EXPLORATION
        selected = directive.selected_mechanism
        if selected is not None and selected != InspirationMechanism.META_REPLAN:
            return selected
        if directive.action == MetaDirectiveAction.REPAIR:
            return InspirationMechanism.AUXILIARY_CONSTRUCTION
        return InspirationMechanism.REVERSE_GOAL_ANALYSIS


__all__ = ["MetaDirectiveController"]
