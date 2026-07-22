from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING, Any, Iterable

from ..activity import ActivityStream
from ..communication.route_registry import RouteRegistry
from ..config import SystemConfig
from ..memory import TypedMemory
from ..proof_graph.store import ProofGraphStore
from ..schemas import (
    ClaimStatus,
    ConstructionProposal,
    EvidenceType,
    InspirationCallReservation,
    InspirationCandidateDecision,
    InspirationContextMode,
    InspirationMaterialization,
    InspirationMechanism,
    InspirationProposal,
    InspirationReview,
    InspirationTask,
    InspirationTrigger,
    InvariantHypothesis,
    MetaDirective,
    MetaDirectiveAudit,
    MetaDirectiveExecution,
    MetaStrategyDecision,
    MemoryTier,
    MessageEnvelope,
    MessageType,
    NoveltySignature,
    ObligationKind,
    ProblemContract,
    RepresentationCandidate,
    ReverseGoalPlan,
    RouteRole,
    StrategyCard,
    VerifiedExperienceRecord,
    NegativeAnalogyRecord,
    stable_hash,
)
from ..store import ArtifactStore
from .analogy_agent import AnalogyAgent
from .construction_inventor import AuxiliaryConstructionInventor
from .invariant_hypothesis import InvariantHypothesisAgent
from .local_library import LocalAnalogyLibrary
from .experience import VerifiedExperienceDistiller
from .meta_control import MetaDirectiveController
from .meta_strategist import PersistentMetaStrategist
from .novelty import InspirationReferee, NoveltyGate
from .ontology import MechanismNormalizer
from .outcomes import InspirationOutcomeLedger
from .representation_switchboard import RepresentationSwitchboard
from .reverse_goal import ReverseGoalAnalyzer
from .surprise_budget import SurpriseBudgetExplorer
from .trigger_policy import InspirationSnapshot, TriggerPolicy

if TYPE_CHECKING:
    from ..communication.broker import MessageBroker


def _protected_finalization_calls(config: SystemConfig) -> int:
    verification_calls = (
        1
        + config.budget.high_risk_verifier_replicas
        + config.scheduler.verification_call_safety_margin
    )
    revision_cycles = min(
        config.scheduler.reserve_revision_cycles,
        config.budget.max_revisions,
    )
    requested = 1 + verification_calls
    requested += revision_cycles * (1 + verification_calls)
    requested += config.scheduler.finish_transition_buffer_calls
    return min(config.budget.max_total_calls, requested)


class InspirationEngine:
    """P0 mechanism-changing search pipeline with persistent, exactly-once state."""

    def __init__(
        self,
        config: SystemConfig,
        *,
        problem: ProblemContract,
        proof_graph: ProofGraphStore,
        typed_memory: TypedMemory,
        route_registry: RouteRegistry,
        broker: "MessageBroker | None" = None,
        store: ArtifactStore | None = None,
        activity: ActivityStream | None = None,
        project_root: str | Path | None = None,
    ) -> None:
        self.config = config
        self.inspiration_config = config.topology.inspiration
        self.problem = problem
        self.proof_graph = proof_graph
        self.typed_memory = typed_memory
        self.route_registry = route_registry
        self.broker = broker
        self.store = store
        self.activity = activity
        self.trigger_policy = TriggerPolicy(self.inspiration_config)
        self.switchboard = RepresentationSwitchboard()
        root = Path(project_root) if project_root is not None else Path.cwd()
        library_path = Path(self.inspiration_config.analogy_library_path)
        if not library_path.is_absolute():
            library_path = root / library_path
        self.analogy_library = LocalAnalogyLibrary(
            library_path,
            enabled=self.inspiration_config.analogy_library_enabled,
        )
        self.analogy_agent = AnalogyAgent(
            self.analogy_library, top_k=self.inspiration_config.analogy_top_k
        )
        self.construction_inventor = AuxiliaryConstructionInventor()
        self.invariant_agent = InvariantHypothesisAgent()
        self.reverse_goal_analyzer = ReverseGoalAnalyzer()
        self.meta_strategist = PersistentMetaStrategist(self.inspiration_config)
        self.meta_controller = MetaDirectiveController(
            self.inspiration_config, self.route_registry
        )
        self.outcome_ledger = InspirationOutcomeLedger(self.inspiration_config)
        self.experience_distiller = VerifiedExperienceDistiller(self.inspiration_config)
        final_reserve = _protected_finalization_calls(config)
        self.surprise_explorer = SurpriseBudgetExplorer(
            self.inspiration_config,
            max_total_calls=config.budget.max_total_calls,
            finalization_reserve_calls=final_reserve,
        )
        self.novelty_gate = NoveltyGate(self.inspiration_config)
        self.referee = InspirationReferee(self.inspiration_config)
        self.mechanism_normalizer = MechanismNormalizer()
        self.triggers: dict[str, InspirationTrigger] = {}
        self.tasks: dict[str, InspirationTask] = {}
        self.proposals: dict[str, InspirationProposal] = {}
        self.reviews: dict[str, InspirationReview] = {}
        self.materializations: dict[str, InspirationMaterialization] = {}
        self.materialized_strategies: dict[str, StrategyCard] = {}
        self.verified_proposals: dict[str, str] = {}
        self.candidate_decisions: dict[str, InspirationCandidateDecision] = {}
        self.call_reservations: dict[str, InspirationCallReservation] = {}
        self.meta_directives: dict[str, MetaDirective] = {}
        self.meta_directive_audits: dict[str, MetaDirectiveAudit] = {}
        self.meta_directive_executions: dict[str, MetaDirectiveExecution] = {}
        self.pending_directive_tasks: dict[str, InspirationTask] = {}
        self.verified_experiences: dict[str, VerifiedExperienceRecord] = {}
        self.negative_analogy_records: dict[str, NegativeAnalogyRecord] = {}
        self.mechanism_stats: dict[str, dict[str, int]] = {
            mechanism.value: {
                "selected_count": 0,
                "consecutive_no_verified_gain": 0,
                "last_selected_round": -1,
                "proposal_count": 0,
                "route_created_count": 0,
                "verified_count": 0,
            }
            for mechanism in InspirationMechanism
        }
        self._last_snapshot: InspirationSnapshot | None = None
        if self.enabled:
            self._event(
                "surprise_budget_reserved",
                "Surprise budget reserved",
                "This budget is separate from the finalization reserve.",
                self.surprise_explorer.export_state(),
            )

    @property
    def enabled(self) -> bool:
        return self.inspiration_config.enabled and self.inspiration_config.mode != "off"

    def detect_triggers(
        self, state: InspirationSnapshot | dict[str, Any]
    ) -> list[InspirationTrigger]:
        if not self.enabled:
            return []
        snapshot = (
            state
            if isinstance(state, InspirationSnapshot)
            else InspirationSnapshot.model_validate(state)
        )
        self._last_snapshot = snapshot
        detected = self.trigger_policy.detect(snapshot)
        fresh: list[InspirationTrigger] = []
        for trigger in detected:
            if trigger.trigger_id in self.triggers:
                continue
            self.triggers[trigger.trigger_id] = trigger
            fresh.append(trigger)
            self._event(
                "inspiration_triggered",
                "Inspiration trigger detected",
                trigger.reason,
                trigger.model_dump(mode="json"),
            )
        self._checkpoint()
        return fresh

    def select_tasks(
        self,
        triggers: list[InspirationTrigger],
        state: InspirationSnapshot | dict[str, Any] | None = None,
        budget: Any = None,
    ) -> list[InspirationTask]:
        del budget
        snapshot = self._coerce_snapshot(state)
        adaptive_profiles = self.outcome_ledger.selection_profiles(triggers, snapshot)
        selected = self.trigger_policy.select_tasks(
            triggers,
            snapshot,
            self.mechanism_stats,
            adaptive_profiles,
        )
        combined = [*self.pending_directive_tasks.values(), *selected]
        selected = list({task.task_id: task for task in combined}.values())[
            : self.inspiration_config.max_inspiration_tasks_per_round
        ]
        fresh: list[InspirationTask] = []
        for task in selected:
            if task.task_id in self.tasks:
                continue
            self.tasks[task.task_id] = task
            self.pending_directive_tasks.pop(task.task_id, None)
            fresh.append(task)
            stats = self.mechanism_stats[task.mechanism.value]
            stats["selected_count"] += 1
            stats["consecutive_no_verified_gain"] += 1
            stats["last_selected_round"] = snapshot.round_index
            self._event(
                "inspiration_task_selected",
                "Inspiration mechanism selected",
                task.reason,
                task.model_dump(mode="json"),
            )
        self._checkpoint()
        return fresh

    async def generate(
        self, tasks: Iterable[InspirationTask]
    ) -> list[InspirationProposal]:
        if not self.enabled:
            return []
        snapshot = self._coerce_snapshot(None)
        generated: list[InspirationProposal] = []
        for task in tasks:
            started_event = {
                InspirationMechanism.REPRESENTATION_SWITCH: "representation_switch_started",
                InspirationMechanism.STRUCTURAL_ANALOGY: "analogy_search_started",
                InspirationMechanism.AUXILIARY_CONSTRUCTION: "construction_inventor_started",
                InspirationMechanism.META_REPLAN: "meta_strategy_replan",
            }.get(task.mechanism)
            if started_event:
                self._event(
                    started_event,
                    "Inspiration mechanism started",
                    task.reason,
                    task.model_dump(mode="json"),
                )
            if task.mechanism == InspirationMechanism.META_REPLAN:
                self.register_meta_decision(
                    task,
                    self.meta_strategist.decide(snapshot),
                    state=snapshot,
                )
                continue
            items = self._generate_task(task, snapshot)
            for proposal in items[: task.max_proposals]:
                if proposal.proposal_id in self.proposals:
                    continue
                self.proposals[proposal.proposal_id] = proposal
                self._register_outcome(proposal, snapshot)
                generated.append(proposal)
                self.mechanism_stats[task.mechanism.value]["proposal_count"] += 1
                generated_event = {
                    InspirationMechanism.REPRESENTATION_SWITCH: "representation_candidate_generated",
                    InspirationMechanism.STRUCTURAL_ANALOGY: "analogy_mapping_generated",
                    InspirationMechanism.AUXILIARY_CONSTRUCTION: "construction_proposal_generated",
                    InspirationMechanism.INVARIANT_HYPOTHESIS: "invariant_hypothesis_generated",
                }.get(task.mechanism, "inspiration_proposal_generated")
                self._event(
                    generated_event,
                    "Inspiration proposal generated",
                    proposal.rationale_summary,
                    {
                        "proposal_id": proposal.proposal_id,
                        "mechanism": proposal.mechanism.value,
                        "novelty_score": proposal.novelty_score,
                        "target_obligations": proposal.generated_obligations,
                    },
                )
        self._checkpoint()
        return generated

    def register_agent_artifact(
        self,
        task: InspirationTask,
        artifact: Any,
        *,
        source_agent_id: str,
        proposal_slot: int = 0,
        context_mode: InspirationContextMode = InspirationContextMode.WARM,
        state: InspirationSnapshot | dict[str, Any] | None = None,
    ) -> InspirationProposal | None:
        """Normalize a typed artifact into a proposal or control directive."""
        snapshot = self._coerce_snapshot(state)
        if isinstance(artifact, MetaStrategyDecision):
            self.register_meta_decision(task, artifact, state=snapshot)
            return None
        if isinstance(artifact, RepresentationCandidate):
            proposal = (
                self._from_surprise(task, artifact, snapshot)
                if task.mechanism == InspirationMechanism.SURPRISE_EXPLORATION
                else self._from_representation(task, artifact, snapshot)
            )
        elif isinstance(artifact, ConstructionProposal):
            proposal = self._from_construction(task, artifact, snapshot)
        elif isinstance(artifact, InvariantHypothesis):
            proposal = self._from_invariant(task, artifact, snapshot)
        elif isinstance(artifact, ReverseGoalPlan):
            proposal = self._from_reverse(task, artifact, snapshot)
        elif hasattr(artifact, "source_record_id") and hasattr(
            artifact, "object_correspondence"
        ):
            proposal = self._from_analogy(task, artifact, snapshot)
        elif isinstance(artifact, InspirationProposal):
            payload = artifact.model_dump(mode="json")
            payload.update(
                {
                    "trigger_id": task.trigger_id,
                    "mechanism": task.mechanism.value,
                    "target_route_ids": list(task.target_route_ids),
                    "generated_obligations": list(
                        dict.fromkeys(
                            task.target_obligation_ids
                            or artifact.generated_obligations
                            or snapshot.open_obligation_ids
                        )
                    ),
                    "evidence_type": EvidenceType.UNVERIFIED_IDEA.value,
                }
            )
            proposal = InspirationProposal.model_validate(payload)
        else:
            raise TypeError(
                f"unsupported inspiration artifact for {task.mechanism.value}: "
                f"{type(artifact).__name__}"
            )
        payload = proposal.model_dump(mode="json")
        normalized_signature = self.mechanism_normalizer.normalize_signature(
            proposal.novelty_signature
        )
        payload["source_agent_id"] = source_agent_id
        payload["task_id"] = task.task_id
        payload["proposal_slot"] = proposal_slot
        payload["context_mode"] = context_mode.value
        payload["novelty_signature"] = normalized_signature.model_dump(mode="json")
        payload["proposal_id"] = (
            f"inspiration_{stable_hash((task.task_id, proposal_slot, context_mode.value, source_agent_id, normalized_signature.normalized_hash, proposal.statement))[:12]}"
        )
        payload["novelty_score"] = self.novelty_gate.assess(
            normalized_signature, snapshot.route_signatures
        ).novelty_score
        normalized = InspirationProposal.model_validate(payload)
        existing = self.proposals.get(normalized.proposal_id)
        if existing is not None:
            return existing
        self.proposals[normalized.proposal_id] = normalized
        self._register_outcome(normalized, snapshot)
        self.mechanism_stats[task.mechanism.value]["proposal_count"] += 1
        generated_event = {
            InspirationMechanism.REPRESENTATION_SWITCH: "representation_candidate_generated",
            InspirationMechanism.STRUCTURAL_ANALOGY: "analogy_mapping_generated",
            InspirationMechanism.AUXILIARY_CONSTRUCTION: "construction_proposal_generated",
            InspirationMechanism.INVARIANT_HYPOTHESIS: "invariant_hypothesis_generated",
        }.get(task.mechanism, "inspiration_proposal_generated")
        self._event(
            generated_event,
            "Inspiration proposal generated by assigned agent",
            normalized.rationale_summary,
            {
                "proposal_id": normalized.proposal_id,
                "source_agent_id": source_agent_id,
                "mechanism": normalized.mechanism.value,
                "novelty_score": normalized.novelty_score,
                "proposal_slot": normalized.proposal_slot,
                "context_mode": normalized.context_mode.value,
            },
        )
        self._checkpoint()
        return normalized

    def register_meta_decision(
        self,
        task: InspirationTask,
        decision: MetaStrategyDecision,
        *,
        state: InspirationSnapshot | dict[str, Any] | None = None,
    ) -> MetaDirectiveExecution | None:
        """Move a Meta-Strategist result through the audited control plane."""

        if not self.inspiration_config.meta_directives_enabled:
            return None
        snapshot = self._coerce_snapshot(state)
        allowed_routes = set(task.target_route_ids or snapshot.active_route_ids)
        proposed_routes = [
            route_id
            for route_id in decision.affected_route_ids
            if route_id in allowed_routes
        ]
        normalized = decision.model_copy(
            update={
                "round_index": snapshot.round_index,
                "affected_route_ids": proposed_routes
                or list(task.target_route_ids)
                or list(snapshot.active_route_ids),
                "observable_metrics": self.meta_strategist.observable_metrics(snapshot),
            }
        )
        self.meta_strategist.record(normalized)
        directive = self.meta_controller.from_decision(normalized, snapshot)
        existing = self.meta_directive_executions.get(directive.directive_id)
        if existing is not None:
            return existing
        audit = self.meta_controller.audit(directive, snapshot)
        if self.inspiration_config.mode == "shadow":
            execution = MetaDirectiveExecution(
                directive_id=directive.directive_id,
                status="noop",
                reason="shadow mode records the directive without control mutation",
            )
            generated_tasks: list[InspirationTask] = []
        else:
            execution, generated_tasks = self.meta_controller.execute(
                directive,
                audit,
                snapshot,
                trigger_id=task.trigger_id,
            )
        self.meta_directives[directive.directive_id] = directive
        self.meta_directive_audits[directive.directive_id] = audit
        self.meta_directive_executions[directive.directive_id] = execution
        for generated_task in generated_tasks:
            if generated_task.task_id not in self.tasks:
                self.pending_directive_tasks[generated_task.task_id] = generated_task
        self._event(
            "meta_directive_audited",
            "Meta directive audited",
            audit.reason,
            {
                "directive": directive.model_dump(mode="json"),
                "audit": audit.model_dump(mode="json"),
            },
        )
        self._event(
            "meta_directive_executed",
            "Meta directive execution recorded",
            execution.reason,
            execution.model_dump(mode="json"),
        )
        self._checkpoint()
        return execution

    def record_outcome_usage(
        self,
        proposal_id: str,
        *,
        phase: str,
        calls: int = 1,
        tokens: int = 0,
    ) -> None:
        self.outcome_ledger.record_usage(
            proposal_id,
            phase=phase,
            calls=calls,
            tokens=tokens,
        )
        self._checkpoint()

    def _register_outcome(
        self, proposal: InspirationProposal, snapshot: InspirationSnapshot
    ) -> None:
        trigger = self.triggers.get(proposal.trigger_id)
        if trigger is None:
            return
        kinds: list[ObligationKind] = []
        for obligation_id in proposal.generated_obligations:
            raw = snapshot.obligation_kinds.get(obligation_id)
            if raw is None:
                continue
            try:
                kinds.append(ObligationKind(raw))
            except ValueError:
                continue
        target_routes = proposal.target_route_ids or trigger.affected_route_ids
        debt_before = sum(
            snapshot.proof_debt_by_route.get(route_id, 0.0)
            for route_id in target_routes
        )
        self.outcome_ledger.register(
            proposal,
            snapshot=snapshot,
            trigger=trigger,
            obligation_kinds=kinds,
            proof_debt_before=debt_before,
        )

    def select_proposals_for_review(
        self,
        proposals: Iterable[InspirationProposal],
        *,
        existing_signatures: Iterable[NoveltySignature],
    ) -> list[InspirationProposal]:
        """Greedily retain a small, mechanism-diverse population per task."""

        grouped: dict[str, list[InspirationProposal]] = {}
        for proposal in proposals:
            task_id = proposal.task_id or f"legacy:{proposal.trigger_id}"
            grouped.setdefault(task_id, []).append(proposal)
        selected_all: list[InspirationProposal] = []
        normalized_existing = [
            self.mechanism_normalizer.normalize_signature(item)
            for item in existing_signatures
        ]
        limit = self.inspiration_config.max_reviewed_proposals_per_task
        for task_id, candidates in grouped.items():
            remaining = list(candidates)
            selected: list[InspirationProposal] = []
            selected_modes: set[InspirationContextMode] = set()
            rank = 0
            while remaining:
                scored: list[
                    tuple[
                        float,
                        float,
                        int,
                        InspirationProposal,
                        Any,
                    ]
                ] = []
                comparison = [
                    *normalized_existing,
                    *(item.novelty_signature for item in selected),
                ]
                for proposal in remaining:
                    assessment = self.novelty_gate.assess(
                        proposal.novelty_signature, comparison
                    )
                    mode_bonus = (
                        0.05 if proposal.context_mode not in selected_modes else 0.0
                    )
                    scored.append(
                        (
                            assessment.novelty_score + mode_bonus,
                            proposal.expected_information_gain,
                            -proposal.proposal_slot,
                            proposal,
                            assessment,
                        )
                    )
                _score, _information, _slot, proposal, assessment = max(
                    scored,
                    key=lambda item: (item[0], item[1], item[2], item[3].proposal_id),
                )
                remaining.remove(proposal)
                rank += 1
                nearest_proposal = next(
                    (
                        item.proposal_id
                        for item in selected
                        if item.novelty_signature.normalized_hash
                        == assessment.nearest_hash
                    ),
                    None,
                )
                if assessment.duplicate:
                    accepted = False
                    reason = "mechanism duplicate removed before model review"
                elif proposal.novelty_score < self.inspiration_config.novelty_threshold:
                    accepted = False
                    reason = "proposal is below the configured novelty threshold"
                elif len(selected) >= limit:
                    accepted = False
                    reason = "proposal ranked below max_reviewed_proposals_per_task"
                else:
                    accepted = True
                    reason = "proposal admitted to independent review"
                    selected.append(proposal)
                    selected_modes.add(proposal.context_mode)
                    selected_all.append(proposal)
                decision = InspirationCandidateDecision(
                    proposal_id=proposal.proposal_id,
                    task_id=task_id,
                    selected_for_review=accepted,
                    rank=rank,
                    reason=reason,
                    nearest_proposal_id=nearest_proposal,
                    maximum_similarity=assessment.maximum_similarity,
                )
                self.candidate_decisions[proposal.proposal_id] = decision
                self._event(
                    "inspiration_candidate_selected"
                    if accepted
                    else "inspiration_candidate_not_selected",
                    "Inspiration candidate population filtered",
                    reason,
                    decision.model_dump(mode="json"),
                )
        self._checkpoint()
        return selected_all

    def reserve_task_calls(
        self,
        task: InspirationTask,
        *,
        snapshot: InspirationSnapshot,
        proposer_calls: int,
        referee_calls: int,
        skeptic_calls: int,
        route_attempt_calls: int,
    ) -> tuple[InspirationCallReservation | None, str]:
        existing = self.call_reservations.get(task.task_id)
        if existing is not None and existing.status == "active":
            return existing, "existing active reservation reused"
        reserved = proposer_calls + referee_calls + skeptic_calls + route_attempt_calls
        if task.mechanism == InspirationMechanism.SURPRISE_EXPLORATION:
            allowed, reason = self.surprise_explorer.reserve(
                current_round=snapshot.round_index,
                remaining_calls=snapshot.remaining_calls,
                current_path_count=snapshot.current_path_count,
                max_paths=snapshot.max_paths,
                estimated_calls=reserved,
            )
            if not allowed:
                return None, reason
        identifier = f"inspiration_budget_{stable_hash((task.task_id, snapshot.round_index))[:12]}"
        reservation = InspirationCallReservation(
            reservation_id=identifier,
            task_id=task.task_id,
            trigger_id=task.trigger_id,
            round_index=snapshot.round_index,
            proposer_calls=proposer_calls,
            referee_calls=referee_calls,
            skeptic_calls=skeptic_calls,
            route_attempt_calls=route_attempt_calls,
            reserved_calls=reserved,
        )
        self.call_reservations[task.task_id] = reservation
        self._event(
            "inspiration_call_budget_reserved",
            "Inspiration call budget reserved",
            "Proposer, referee, skeptic, and first route attempt were admitted atomically.",
            reservation.model_dump(mode="json"),
        )
        self._checkpoint()
        return reservation, "reserved"

    def record_reserved_calls(self, task_id: str, calls: int, *, phase: str) -> None:
        if calls <= 0:
            return
        reservation = self.call_reservations.get(task_id)
        if reservation is None:
            return
        phase_calls = dict(reservation.phase_calls)
        phase_calls[phase] = phase_calls.get(phase, 0) + calls
        consumed = reservation.consumed_calls + calls
        overrun = max(0, consumed - reservation.reserved_calls)
        updated = reservation.model_copy(
            update={
                "consumed_calls": consumed,
                "overrun_calls": overrun,
                "phase_calls": phase_calls,
            }
        )
        self.call_reservations[task_id] = updated
        task = self.tasks.get(task_id)
        if (
            task is not None
            and task.mechanism == InspirationMechanism.SURPRISE_EXPLORATION
        ):
            self.surprise_explorer.consume(calls)
        self._event(
            "inspiration_call_budget_consumed",
            "Inspiration reserved calls consumed",
            phase,
            {
                "reservation_id": updated.reservation_id,
                "task_id": task_id,
                "calls": calls,
                "consumed_calls": consumed,
                "overrun_calls": overrun,
            },
        )
        self._checkpoint()

    def finish_task_reservation(
        self, task_id: str, *, interrupted: bool = False
    ) -> None:
        reservation = self.call_reservations.get(task_id)
        if reservation is None or reservation.status != "active":
            return
        released = reservation.remaining_reserved_calls
        status = "interrupted" if interrupted else "completed"
        updated = reservation.model_copy(
            update={"released_calls": released, "status": status}
        )
        self.call_reservations[task_id] = updated
        task = self.tasks.get(task_id)
        if (
            task is not None
            and task.mechanism == InspirationMechanism.SURPRISE_EXPLORATION
        ):
            self.surprise_explorer.release(released)
        self._event(
            "inspiration_call_budget_released",
            "Unused inspiration call budget released",
            status,
            updated.model_dump(mode="json"),
        )
        self._checkpoint()

    def reconcile_call_reservations(
        self, ledger_reservation_calls: dict[str, int]
    ) -> None:
        """Charge calls persisted before a crash, then release orphaned capacity."""

        for task_id, reservation in list(self.call_reservations.items()):
            if reservation.status != "active":
                continue
            actual = int(ledger_reservation_calls.get(reservation.reservation_id, 0))
            missing = max(0, actual - reservation.consumed_calls)
            if missing:
                self.record_reserved_calls(task_id, missing, phase="resume_reconcile")
            self.finish_task_reservation(task_id, interrupted=True)

    def reservation_id_for_task(self, task_id: str | None) -> str | None:
        if not task_id:
            return None
        reservation = self.call_reservations.get(task_id)
        return reservation.reservation_id if reservation is not None else None

    async def review(
        self,
        proposals: Iterable[InspirationProposal],
        *,
        reviewer_agent_id: str = "inspiration_referee",
        immediate_counterexamples: dict[str, list[str]] | None = None,
        hidden_assumptions: dict[str, list[str]] | None = None,
        precomputed_reviews: dict[str, InspirationReview] | None = None,
    ) -> list[InspirationReview]:
        snapshot = self._coerce_snapshot(None)
        counterexamples = immediate_counterexamples or {}
        assumptions = hidden_assumptions or {}
        supplied = precomputed_reviews or {}
        reviews: list[InspirationReview] = []
        existing = list(snapshot.route_signatures)
        for proposal in proposals:
            if proposal.proposal_id in self.reviews:
                continue
            local_review = self.referee.review(
                proposal,
                reviewer_agent_id=reviewer_agent_id,
                open_obligation_ids=snapshot.open_obligation_ids,
                existing_signatures=existing,
                immediate_counterexamples=counterexamples.get(proposal.proposal_id, []),
                hidden_assumptions=assumptions.get(proposal.proposal_id, []),
            )
            review = supplied.get(proposal.proposal_id, local_review)
            if review.proposal_id != proposal.proposal_id:
                raise ValueError("inspiration review targets the wrong proposal")
            if review.reviewer_agent_id == proposal.source_agent_id:
                raise ValueError(
                    "an inspiration author cannot referee its own proposal"
                )
            merged_counterexamples = list(
                dict.fromkeys(
                    [
                        *review.immediate_counterexamples,
                        *local_review.immediate_counterexamples,
                    ]
                )
            )
            merged_assumptions = list(
                dict.fromkeys(
                    [*review.hidden_assumptions, *local_review.hidden_assumptions]
                )
            )
            if (
                local_review.recommendation == "reject"
                or merged_counterexamples
                or not local_review.semantically_distinct
                or not local_review.relevant_to_open_obligation
            ):
                review = local_review.model_copy(
                    update={
                        "reviewer_agent_id": review.reviewer_agent_id,
                        "hidden_assumptions": merged_assumptions,
                        "immediate_counterexamples": merged_counterexamples,
                        "recommendation": "reject",
                    }
                )
            else:
                review = review.model_copy(
                    update={
                        "hidden_assumptions": merged_assumptions,
                        "immediate_counterexamples": merged_counterexamples,
                    }
                )
            self.reviews[proposal.proposal_id] = review
            reviews.append(review)
            event_type = (
                "inspiration_proposal_rejected"
                if review.recommendation == "reject"
                else "inspiration_proposal_reviewed"
            )
            self._event(
                event_type,
                "Inspiration proposal reviewed",
                review.recommendation,
                review.model_dump(mode="json"),
            )
        self._checkpoint()
        return reviews

    def materialize(
        self,
        reviews: Iterable[InspirationReview],
        state: InspirationSnapshot | dict[str, Any] | None = None,
    ) -> list[InspirationMaterialization]:
        snapshot = self._coerce_snapshot(state)
        decisions: list[InspirationMaterialization] = []
        for review in reviews:
            if review.proposal_id in self.materializations:
                continue
            proposal = self.proposals[review.proposal_id]
            recorded_review = self.reviews.get(review.proposal_id)
            referee_required_but_missing = (
                self.inspiration_config.mode == "active"
                and self.inspiration_config.require_inspiration_referee
                and (
                    recorded_review is None
                    or recorded_review.reviewer_agent_id == proposal.source_agent_id
                    or recorded_review.reviewer_agent_id
                    == "local_deterministic_referee"
                )
            )
            if self.inspiration_config.mode == "shadow":
                decision = InspirationMaterialization(
                    proposal_id=proposal.proposal_id,
                    action="shadow_only",
                    reason=(
                        "shadow mode records the referee recommendation without "
                        "changing memory, graph, routes, or scheduler state"
                    ),
                )
            elif referee_required_but_missing:
                self.typed_memory.add_insight(proposal)
                decision = InspirationMaterialization(
                    proposal_id=proposal.proposal_id,
                    action="stored_insight",
                    reason=(
                        "require_inspiration_referee is enabled, but no independent "
                        "agent referee admitted this proposal"
                    ),
                )
            elif review.recommendation == "reject":
                # The pipeline is explicit even for failed ideas: every proposal
                # first enters InsightMemory, then a refuted one is demoted to
                # NegativeMemory. This preserves the audit trail without ever
                # treating novelty as mathematical evidence.
                self.typed_memory.add_insight(proposal)
                self.typed_memory.add_negative(
                    proposal,
                    reason="; ".join(
                        review.immediate_counterexamples
                        or review.hidden_assumptions
                        or ["inspiration referee rejected proposal"]
                    ),
                )
                decision = InspirationMaterialization(
                    proposal_id=proposal.proposal_id,
                    action="rejected",
                    reason="independent inspiration review rejected the proposal",
                )
                if proposal.mechanism == InspirationMechanism.SURPRISE_EXPLORATION:
                    self.surprise_explorer.reject(current_round=snapshot.round_index)
            else:
                decision = self._materialize_active(proposal, review, snapshot)
            self.materializations[proposal.proposal_id] = decision
            self.outcome_ledger.record_materialization(
                proposal.proposal_id,
                action=decision.action,
                refuted=(decision.action == "rejected"),
            )
            negative_analogy = self.experience_distiller.distill_negative_analogy(
                problem=self.problem,
                proposal=proposal,
                review=review,
                round_index=snapshot.round_index,
            )
            if negative_analogy is not None:
                self.negative_analogy_records[negative_analogy.record_id] = (
                    negative_analogy
                )
                self.analogy_library.add_negative_record(
                    negative_analogy.model_dump(mode="json")
                )
                self._trim_experiences()
                self._event(
                    "negative_analogy_recorded",
                    "Failed analogy transfer recorded",
                    negative_analogy.failure_reason,
                    negative_analogy.model_dump(mode="json"),
                )
            if decision.action == "route_created":
                self.mechanism_stats[proposal.mechanism.value][
                    "route_created_count"
                ] += 1
            decisions.append(decision)
            self._event(
                "inspiration_proposal_materialized",
                "Inspiration decision materialized",
                decision.reason,
                decision.model_dump(mode="json"),
            )
            self._checkpoint()
        return decisions

    def _generate_task(
        self, task: InspirationTask, snapshot: InspirationSnapshot
    ) -> list[InspirationProposal]:
        obligations = [
            item
            for item in self.proof_graph.obligations
            if not task.target_obligation_ids
            or item.obligation_id in task.target_obligation_ids
        ]
        if task.mechanism == InspirationMechanism.REPRESENTATION_SWITCH:
            candidates = self.switchboard.generate(
                self.problem,
                obligations,
                domain=snapshot.domain,
                existing_signatures=snapshot.route_signatures,
                max_candidates=task.max_proposals,
            )
            return [
                self._from_representation(task, item, snapshot) for item in candidates
            ]
        if task.mechanism == InspirationMechanism.STRUCTURAL_ANALOGY:
            mappings = self.analogy_agent.search(
                self.problem,
                target_obligation_ids=task.target_obligation_ids,
                mechanism_tags=[
                    tag
                    for sig in snapshot.route_signatures
                    for tag in sig.mechanism_tags
                ],
                graph_tags=["shared_bottleneck"]
                if snapshot.shared_bottleneck_ids
                else [],
                obligation_kinds=[
                    snapshot.obligation_kinds[item]
                    for item in task.target_obligation_ids
                    if item in snapshot.obligation_kinds
                ],
                mechanism_chain=[
                    tag
                    for sig in snapshot.route_signatures
                    for tag in [
                        *sig.representation_tags,
                        *sig.mechanism_tags,
                        *sig.key_transformations,
                    ]
                ],
                graph_motif_tags=["shared_bottleneck"]
                if snapshot.shared_bottleneck_ids
                else [],
            )
            return [self._from_analogy(task, item, snapshot) for item in mappings]
        if task.mechanism == InspirationMechanism.AUXILIARY_CONSTRUCTION:
            constructions = self.construction_inventor.propose(
                self.problem,
                obligations,
                domain=snapshot.domain,
                max_proposals=task.max_proposals,
            )
            return [
                self._from_construction(task, item, snapshot) for item in constructions
            ]
        if task.mechanism == InspirationMechanism.INVARIANT_HYPOTHESIS:
            hypotheses = self.invariant_agent.propose(
                self.problem,
                obligations,
                domain=snapshot.domain,
                max_proposals=task.max_proposals,
            )
            return [self._from_invariant(task, item, snapshot) for item in hypotheses]
        if task.mechanism in {
            InspirationMechanism.REVERSE_GOAL_ANALYSIS,
            InspirationMechanism.BRIDGE_LEMMA,
        }:
            return [
                self._from_reverse(
                    task,
                    self.reverse_goal_analyzer.analyze(
                        item,
                        fact_statements=[
                            fact.statement for fact in self.typed_memory.facts
                        ],
                    ),
                    snapshot,
                )
                for item in obligations[: task.max_proposals]
            ]
        if task.mechanism == InspirationMechanism.META_REPLAN:
            return [
                self._from_meta(task, self.meta_strategist.decide(snapshot), snapshot)
            ]
        if task.mechanism == InspirationMechanism.SURPRISE_EXPLORATION:
            candidates = self.switchboard.generate(
                self.problem,
                obligations,
                domain=snapshot.domain,
                existing_signatures=snapshot.route_signatures,
                max_candidates=max(task.max_proposals, 4),
            )
            candidates.reverse()
            return [
                self._from_surprise(task, item, snapshot)
                for item in candidates[: task.max_proposals]
            ]
        return []

    def _proposal(
        self,
        task: InspirationTask,
        signature: NoveltySignature,
        snapshot: InspirationSnapshot,
        *,
        statement: str,
        rationale: str,
        representation: RepresentationCandidate | None = None,
        analogy: Any = None,
        construction: ConstructionProposal | None = None,
        invariant: InvariantHypothesis | None = None,
        reverse_goal: ReverseGoalPlan | None = None,
        target_routes: list[str] | None = None,
        estimated_cost: int = 1,
    ) -> InspirationProposal:
        signature = self.mechanism_normalizer.normalize_signature(signature)
        novelty = self.novelty_gate.assess(signature, snapshot.route_signatures)
        digest = stable_hash((task.task_id, signature.normalized_hash, statement))
        return InspirationProposal(
            proposal_id=f"inspiration_{digest[:12]}",
            task_id=task.task_id,
            trigger_id=task.trigger_id,
            mechanism=task.mechanism,
            source_agent_id="inspiration_engine",
            target_route_ids=(
                list(task.target_route_ids) if target_routes is None else target_routes
            ),
            statement=statement,
            rationale_summary=rationale,
            generated_obligations=list(
                dict.fromkeys(
                    task.target_obligation_ids
                    or signature.targeted_obligation_ids
                    or snapshot.open_obligation_ids
                )
            ),
            representation=representation,
            analogy=analogy,
            construction=construction,
            invariant=invariant,
            reverse_goal=reverse_goal,
            novelty_signature=signature,
            novelty_score=novelty.novelty_score,
            expected_information_gain=max(0.1, novelty.novelty_score),
            estimated_cost=estimated_cost,
        )

    def _from_representation(
        self,
        task: InspirationTask,
        item: RepresentationCandidate,
        snapshot: InspirationSnapshot,
    ) -> InspirationProposal:
        return self._proposal(
            task,
            item.novelty_signature,
            snapshot,
            statement=item.rewritten_problem_view,
            rationale=item.expected_advantage,
            representation=item,
        )

    def _from_analogy(
        self, task: InspirationTask, item: Any, snapshot: InspirationSnapshot
    ) -> InspirationProposal:
        return self._proposal(
            task,
            item.novelty_signature,
            snapshot,
            statement=f"Test transfer of: {'; '.join(item.transferable_lemmas)}",
            rationale=f"Local verified structural analogue {item.source_record_id}",
            analogy=item,
        )

    def _from_construction(
        self,
        task: InspirationTask,
        item: ConstructionProposal,
        snapshot: InspirationSnapshot,
    ) -> InspirationProposal:
        return self._proposal(
            task,
            item.novelty_signature,
            snapshot,
            statement=item.definition,
            rationale=item.expected_proof_debt_reduction,
            construction=item,
        )

    def _from_invariant(
        self,
        task: InspirationTask,
        item: InvariantHypothesis,
        snapshot: InspirationSnapshot,
    ) -> InspirationProposal:
        return self._proposal(
            task,
            item.novelty_signature,
            snapshot,
            statement=f"Candidate {item.behavior}: {item.candidate_expression}",
            rationale=item.falsification_request,
            invariant=item,
        )

    def _from_reverse(
        self,
        task: InspirationTask,
        item: ReverseGoalPlan,
        snapshot: InspirationSnapshot,
    ) -> InspirationProposal:
        return self._proposal(
            task,
            item.novelty_signature,
            snapshot,
            statement="; ".join(item.bridge_requests),
            rationale="Reverse goal analysis isolates the minimal unsupported gap",
            reverse_goal=item,
        )

    def _from_meta(
        self, task: InspirationTask, item: Any, snapshot: InspirationSnapshot
    ) -> InspirationProposal:
        signature = NoveltySignature(
            mechanism_tags=["persistent_meta_strategy", item.action],
            core_objects=["route_portfolio", "proof_debt"],
            key_transformations=[item.action],
            proof_principles=["observable_search_control"],
            targeted_obligation_ids=task.target_obligation_ids,
        )
        return self._proposal(
            task,
            signature,
            snapshot,
            statement=f"Meta action: {item.action}",
            rationale=item.reason,
            estimated_cost=item.estimated_calls,
        )

    def _from_surprise(
        self,
        task: InspirationTask,
        item: RepresentationCandidate,
        snapshot: InspirationSnapshot,
    ) -> InspirationProposal:
        payload = item.novelty_signature.model_dump(mode="json")
        payload["mechanism_tags"] = list(
            dict.fromkeys([*payload["mechanism_tags"], "surprise_exploration"])
        )
        payload["normalized_hash"] = ""
        signature = NoveltySignature.model_validate(payload)
        return self._proposal(
            task,
            signature,
            snapshot,
            statement=item.rewritten_problem_view,
            rationale="Protected surprise budget tests a mechanism outside current routes",
            representation=item,
            target_routes=[],
        )

    def _materialize_active(
        self,
        proposal: InspirationProposal,
        review: InspirationReview,
        snapshot: InspirationSnapshot,
    ) -> InspirationMaterialization:
        self.typed_memory.add_insight(proposal)
        if proposal.mechanism == InspirationMechanism.SURPRISE_EXPLORATION:
            allowed, reason = self.surprise_explorer.admit(
                proposal,
                current_round=snapshot.round_index,
                remaining_calls=snapshot.remaining_calls,
                current_path_count=snapshot.current_path_count,
                max_paths=snapshot.max_paths,
                pre_reserved=(
                    proposal.task_id in self.call_reservations
                    if proposal.task_id is not None
                    else False
                ),
            )
            if not allowed:
                return InspirationMaterialization(
                    proposal_id=proposal.proposal_id,
                    action="stored_insight",
                    reason=reason,
                )
        obligation_ids = self._attach_obligations(proposal)
        if review.recommendation == "store_insight":
            return InspirationMaterialization(
                proposal_id=proposal.proposal_id,
                action="stored_insight",
                obligation_ids=obligation_ids,
                reason="the independently reviewed proposal remains an Insight",
            )
        if review.recommendation == "create_new_route" or (
            proposal.mechanism == InspirationMechanism.SURPRISE_EXPLORATION
            and not proposal.target_route_ids
        ):
            routes_from_trigger = sum(
                1
                for proposal_id, materialization in self.materializations.items()
                if materialization.action == "route_created"
                and self.proposals.get(proposal_id) is not None
                and self.proposals[proposal_id].trigger_id == proposal.trigger_id
            )
            route_cap = min(
                self.inspiration_config.max_new_routes_per_trigger,
                self.inspiration_config.max_materialized_proposals_per_trigger,
            )
            if routes_from_trigger >= route_cap:
                return InspirationMaterialization(
                    proposal_id=proposal.proposal_id,
                    action="stored_insight",
                    obligation_ids=obligation_ids,
                    reason=(
                        "max_new_routes_per_trigger prevents another route from this trigger"
                    ),
                )
            if (
                len(self.route_registry.active_routes(snapshot.round_index))
                >= snapshot.max_paths
            ):
                return InspirationMaterialization(
                    proposal_id=proposal.proposal_id,
                    action="stored_insight",
                    obligation_ids=obligation_ids,
                    reason="max_paths prevents route creation",
                )
            strategy = StrategyCard(
                strategy_id=f"strategy_{proposal.proposal_id}",
                title=f"Inspiration: {proposal.mechanism.value}",
                core_idea=proposal.statement,
                independence_basis=(
                    "mechanism novelty signature differs from existing routes"
                ),
                expected_lemmas=proposal.generated_obligations,
                bottleneck="independent validation of the inspiration proposal",
                key_original_step=proposal.rationale_summary,
                falsification_test="run the proposal's explicit fast failure tests",
                estimated_success=0.35,
                estimated_cost=min(1.0, proposal.estimated_cost / 4),
                tags=[
                    proposal.mechanism.value,
                    *proposal.novelty_signature.mechanism_tags,
                ],
                inspiration_proposal_id=proposal.proposal_id,
                parent_strategy_ids=[
                    self.route_registry.get(route_id).strategy_id
                    for route_id in proposal.target_route_ids
                    if route_id
                    in {item.route_id for item in self.route_registry.routes}
                ],
            )
            route = self.route_registry.register_route(strategy)
            if route.strategy_id != strategy.strategy_id:
                return InspirationMaterialization(
                    proposal_id=proposal.proposal_id,
                    action="stored_insight",
                    route_id=route.route_id,
                    obligation_ids=obligation_ids,
                    reason=(
                        "the proposal is semantically equivalent to an existing route; "
                        "it was retained as route-local insight without consuming a new path"
                    ),
                )
            self.materialized_strategies[strategy.strategy_id] = strategy
            self.route_registry.assign_member(
                route.route_id,
                proposal.source_agent_id,
                RouteRole.PROVER,
                snapshot.round_index,
            )
            self._publish_typed(proposal, route.route_id, snapshot.round_index)
            if proposal.mechanism == InspirationMechanism.SURPRISE_EXPLORATION:
                self._event(
                    "surprise_route_created",
                    "Surprise route created",
                    proposal.rationale_summary,
                    {
                        "proposal_id": proposal.proposal_id,
                        "route_id": route.route_id,
                        "novelty_score": proposal.novelty_score,
                    },
                )
            return InspirationMaterialization(
                proposal_id=proposal.proposal_id,
                action="route_created",
                route_id=route.route_id,
                obligation_ids=obligation_ids,
                reason="active mode admitted an independently reviewed novel mechanism",
            )
        if review.recommendation == "request_computation":
            return InspirationMaterialization(
                proposal_id=proposal.proposal_id,
                action="computation_requested",
                obligation_ids=obligation_ids,
                reason="proposal requires a bounded falsification experiment",
            )
        if review.recommendation == "request_bridge_verification":
            return InspirationMaterialization(
                proposal_id=proposal.proposal_id,
                action="bridge_requested",
                obligation_ids=obligation_ids,
                reason="proposal isolates a shared bridge lemma",
            )
        route_id = proposal.target_route_ids[0] if proposal.target_route_ids else None
        if route_id:
            self._publish_typed(proposal, route_id, snapshot.round_index)
        return InspirationMaterialization(
            proposal_id=proposal.proposal_id,
            action="attached" if route_id else "stored_insight",
            route_id=route_id,
            obligation_ids=obligation_ids,
            reason="proposal remains an Insight pending ordinary proof validation",
        )

    def _attach_obligations(self, proposal: InspirationProposal) -> list[str]:
        if proposal.reverse_goal is not None:
            return [
                item.obligation_id
                for item in self.reverse_goal_analyzer.materialize(
                    proposal.reverse_goal, self.proof_graph
                )
            ]
        return [
            item_id
            for item_id in proposal.generated_obligations
            if item_id in {item.obligation_id for item in self.proof_graph.obligations}
        ]

    def mark_verified(self, proposal_id: str, evidence_message_id: str) -> None:
        if proposal_id not in self.proposals:
            raise KeyError(proposal_id)
        evidence = next(
            (
                item
                for item in self.typed_memory.facts
                if item.message_id == evidence_message_id
            ),
            None,
        )
        if evidence is None:
            raise ValueError(
                "only an independently verified Fact can verify a proposal"
            )
        if self.verified_proposals.get(proposal_id) == evidence_message_id:
            return
        self.verified_proposals[proposal_id] = evidence_message_id
        proposal = self.proposals[proposal_id]
        mechanism = proposal.mechanism.value
        stats = self.mechanism_stats[mechanism]
        stats["verified_count"] += 1
        stats["consecutive_no_verified_gain"] = 0
        snapshot = self._last_snapshot
        target_routes = proposal.target_route_ids
        proof_debt_after = sum(
            self.proof_graph.proof_debt(route_id)
            for route_id in target_routes
            if any(route.route_id == route_id for route in self.route_registry.routes)
        )
        closed_obligations = [
            item.obligation_id
            for item in self.proof_graph.obligations
            if item.obligation_id in proposal.generated_obligations
            and item.status == "closed"
        ]
        round_index = (
            snapshot.round_index
            if snapshot is not None
            else self.triggers[proposal.trigger_id].round_index
        )
        outcome = self.outcome_ledger.record_verified_gain(
            proposal_id,
            round_index=round_index,
            proof_debt_after=proof_debt_after,
            obligations_closed=closed_obligations,
        )
        experience = (
            self.experience_distiller.distill_verified(
                problem=self.problem,
                proposal=proposal,
                fact=evidence,
                outcome=outcome,
                proof_graph=self.proof_graph,
            )
            if outcome is not None
            else None
        )
        if experience is not None:
            self.verified_experiences[experience.record_id] = experience
            self.analogy_library.add_verified_record(experience.model_dump(mode="json"))
            self._trim_experiences()
            self._event(
                "verified_experience_distilled",
                "Verified proof experience distilled",
                proposal.statement,
                experience.model_dump(mode="json"),
            )
        self._event(
            "inspiration_proposal_verified",
            "Inspiration proposal produced a verified fact",
            self.proposals[proposal_id].statement,
            {
                "proposal_id": proposal_id,
                "evidence_message_id": evidence_message_id,
                "reward": outcome.reward if outcome is not None else 0.0,
                "experience_record_id": (
                    experience.record_id if experience is not None else None
                ),
            },
        )
        self._checkpoint()

    def mark_proposal_cited(self, proposal_id: str) -> None:
        if proposal_id not in self.verified_proposals:
            return
        self.outcome_ledger.mark_final_citation(proposal_id)
        for record_id, record in list(self.verified_experiences.items()):
            if record.source_proposal_id != proposal_id:
                continue
            updated = record.model_copy(update={"cited_by_final_proof": True})
            self.verified_experiences[record_id] = updated
            for index, payload in enumerate(self.analogy_library.records):
                if payload.get("record_id") == record_id:
                    self.analogy_library.records[index] = updated.model_dump(
                        mode="json"
                    )
                    break
        self._event(
            "inspiration_cited_by_final_proof",
            "Verified inspiration cited by final proof",
            proposal_id,
            {"proposal_id": proposal_id},
        )
        self._checkpoint()

    def _trim_experiences(self) -> None:
        positive_limit = self.inspiration_config.max_distilled_experiences
        while len(self.verified_experiences) > positive_limit:
            oldest = next(iter(self.verified_experiences))
            self.verified_experiences.pop(oldest, None)
            self.analogy_library.records = [
                item
                for item in self.analogy_library.records
                if item.get("record_id") != oldest
            ]
        negative_limit = self.inspiration_config.max_negative_analogy_records
        while len(self.negative_analogy_records) > negative_limit:
            oldest = next(iter(self.negative_analogy_records))
            self.negative_analogy_records.pop(oldest, None)
            self.analogy_library.negative_records = [
                item
                for item in self.analogy_library.negative_records
                if item.get("record_id") != oldest
            ]

    def _publish_typed(
        self, proposal: InspirationProposal, route_id: str, current_round: int
    ) -> None:
        if self.broker is None:
            return
        if not self.route_registry.owns_agent(
            route_id, proposal.source_agent_id, RouteRole.PROVER
        ):
            try:
                self.route_registry.assign_member(
                    route_id,
                    proposal.source_agent_id,
                    RouteRole.PROVER,
                    current_round,
                )
            except ValueError:
                return
        message = MessageEnvelope(
            problem_hash=self.problem.integrity_hash,
            source_agent_id=proposal.source_agent_id,
            source_route_id=route_id,
            source_role=RouteRole.PROVER,
            target_route_ids=[],
            message_type=MessageType.CLAIM_PROPOSAL,
            statement=proposal.statement,
            normalized_statement=proposal.statement.casefold().strip(),
            assumptions=[],
            conclusion=proposal.statement,
            dependencies=[],
            scope_limitations=["unverified inspiration proposal"],
            evidence_type=EvidenceType.UNVERIFIED_IDEA,
            memory_tier=MemoryTier.INSIGHT,
            verification_status=ClaimStatus.PROPOSED,
            verification_confidence=0.0,
            normalization_confidence=1.0,
            round_created=current_round,
            ttl_rounds=self.config.topology.cross_route.message_ttl_rounds,
        )
        self.broker.publish(message, referee_agent_id=None, current_round=current_round)

    def _coerce_snapshot(
        self, state: InspirationSnapshot | dict[str, Any] | None
    ) -> InspirationSnapshot:
        if state is None:
            if self._last_snapshot is None:
                raise RuntimeError("Inspiration Engine has no state snapshot")
            return self._last_snapshot
        snapshot = (
            state
            if isinstance(state, InspirationSnapshot)
            else InspirationSnapshot.model_validate(state)
        )
        self._last_snapshot = snapshot
        return snapshot

    def _event(
        self, event_type: str, title: str, detail: str, metrics: dict[str, Any]
    ) -> None:
        if self.store is not None:
            self.store.append_event(event_type, metrics)
        if self.activity is not None:
            self.activity.info(
                event_type,
                title=title,
                detail=detail,
                stage="inspiration",
                metrics=metrics,
            )

    def _checkpoint(self) -> None:
        if self.store is not None:
            self.store.write_json(
                "inspiration", "inspiration_checkpoint", self.export_state()
            )
            self.store.write_json(
                "inspiration",
                "verified_experiences",
                [
                    item.model_dump(mode="json")
                    for item in self.verified_experiences.values()
                ],
            )
            self.store.write_json(
                "inspiration",
                "negative_analogy_library",
                [
                    item.model_dump(mode="json")
                    for item in self.negative_analogy_records.values()
                ],
            )

    def export_state(self) -> dict[str, Any]:
        return {
            "mode": self.inspiration_config.mode,
            "triggers": {
                key: value.model_dump(mode="json")
                for key, value in self.triggers.items()
            },
            "tasks": {
                key: value.model_dump(mode="json") for key, value in self.tasks.items()
            },
            "proposals": {
                key: value.model_dump(mode="json")
                for key, value in self.proposals.items()
            },
            "reviews": {
                key: value.model_dump(mode="json")
                for key, value in self.reviews.items()
            },
            "materializations": {
                key: value.model_dump(mode="json")
                for key, value in self.materializations.items()
            },
            "materialized_strategies": {
                key: value.model_dump(mode="json")
                for key, value in self.materialized_strategies.items()
            },
            "verified_proposals": dict(self.verified_proposals),
            "candidate_decisions": {
                key: value.model_dump(mode="json")
                for key, value in self.candidate_decisions.items()
            },
            "call_reservations": {
                key: value.model_dump(mode="json")
                for key, value in self.call_reservations.items()
            },
            "meta_directives": {
                key: value.model_dump(mode="json")
                for key, value in self.meta_directives.items()
            },
            "meta_directive_audits": {
                key: value.model_dump(mode="json")
                for key, value in self.meta_directive_audits.items()
            },
            "meta_directive_executions": {
                key: value.model_dump(mode="json")
                for key, value in self.meta_directive_executions.items()
            },
            "pending_directive_tasks": {
                key: value.model_dump(mode="json")
                for key, value in self.pending_directive_tasks.items()
            },
            "outcomes": self.outcome_ledger.export_state(),
            "verified_experiences": {
                key: value.model_dump(mode="json")
                for key, value in self.verified_experiences.items()
            },
            "negative_analogy_records": {
                key: value.model_dump(mode="json")
                for key, value in self.negative_analogy_records.items()
            },
            "mechanism_stats": self.mechanism_stats,
            "meta_strategist": self.meta_strategist.export_state(),
            "surprise_budget": self.surprise_explorer.export_state(),
            "last_snapshot": (
                self._last_snapshot.model_dump(mode="json")
                if self._last_snapshot is not None
                else None
            ),
            "analogy_diagnostics": list(self.analogy_library.diagnostics),
        }

    def restore_state(self, state: dict[str, Any]) -> None:
        self.triggers = {
            str(key): InspirationTrigger.model_validate(value)
            for key, value in dict(state.get("triggers", {})).items()
        }
        self.tasks = {
            str(key): InspirationTask.model_validate(value)
            for key, value in dict(state.get("tasks", {})).items()
        }
        self.proposals = {
            str(key): InspirationProposal.model_validate(value)
            for key, value in dict(state.get("proposals", {})).items()
        }
        self.reviews = {
            str(key): InspirationReview.model_validate(value)
            for key, value in dict(state.get("reviews", {})).items()
        }
        self.materializations = {
            str(key): InspirationMaterialization.model_validate(value)
            for key, value in dict(state.get("materializations", {})).items()
        }
        self.materialized_strategies = {
            str(key): StrategyCard.model_validate(value)
            for key, value in dict(state.get("materialized_strategies", {})).items()
        }
        self.verified_proposals = {
            str(key): str(value)
            for key, value in dict(state.get("verified_proposals", {})).items()
        }
        self.candidate_decisions = {
            str(key): InspirationCandidateDecision.model_validate(value)
            for key, value in dict(state.get("candidate_decisions", {})).items()
        }
        self.call_reservations = {
            str(key): InspirationCallReservation.model_validate(value)
            for key, value in dict(state.get("call_reservations", {})).items()
        }
        self.meta_directives = {
            str(key): MetaDirective.model_validate(value)
            for key, value in dict(state.get("meta_directives", {})).items()
        }
        self.meta_directive_audits = {
            str(key): MetaDirectiveAudit.model_validate(value)
            for key, value in dict(state.get("meta_directive_audits", {})).items()
        }
        self.meta_directive_executions = {
            str(key): MetaDirectiveExecution.model_validate(value)
            for key, value in dict(state.get("meta_directive_executions", {})).items()
        }
        self.pending_directive_tasks = {
            str(key): InspirationTask.model_validate(value)
            for key, value in dict(state.get("pending_directive_tasks", {})).items()
        }
        self.outcome_ledger.restore_state(dict(state.get("outcomes", {})))
        self.verified_experiences = {
            str(key): VerifiedExperienceRecord.model_validate(value)
            for key, value in dict(state.get("verified_experiences", {})).items()
        }
        self.negative_analogy_records = {
            str(key): NegativeAnalogyRecord.model_validate(value)
            for key, value in dict(state.get("negative_analogy_records", {})).items()
        }
        for experience in self.verified_experiences.values():
            self.analogy_library.add_verified_record(experience.model_dump(mode="json"))
        for negative in self.negative_analogy_records.values():
            self.analogy_library.add_negative_record(negative.model_dump(mode="json"))
        restored_stats = dict(state.get("mechanism_stats", {}))
        for mechanism in InspirationMechanism:
            if mechanism.value in restored_stats:
                self.mechanism_stats[mechanism.value].update(
                    {
                        str(key): int(value)
                        for key, value in dict(restored_stats[mechanism.value]).items()
                    }
                )
        self.meta_strategist = PersistentMetaStrategist.from_state(
            dict(state.get("meta_strategist", {})), config=self.inspiration_config
        )
        self.surprise_explorer = SurpriseBudgetExplorer.from_state(
            dict(state.get("surprise_budget", {})),
            config=self.inspiration_config,
            max_total_calls=self.config.budget.max_total_calls,
            finalization_reserve_calls=_protected_finalization_calls(self.config),
        )
        snapshot = state.get("last_snapshot")
        self._last_snapshot = (
            InspirationSnapshot.model_validate(snapshot) if snapshot else None
        )
