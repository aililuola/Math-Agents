from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING, Any, Iterable

from ..activity import ActivityStream
from ..communication.route_registry import RouteRegistry
from ..config import SystemConfig
from ..memory import TypedMemory
from ..proof_graph.store import ProofGraphStore
from ..schemas import (
    BidirectionalFrontierState,
    ClaimStatus,
    ComposedInspiration,
    ConstructionProposal,
    EvidenceType,
    InspirationAssignmentPlan,
    InspirationCallReservation,
    InspirationCandidateDecision,
    InspirationContextMode,
    InspirationCreditTarget,
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
    MechanismChainSignature,
    MemoryTier,
    MessageEnvelope,
    MessageType,
    NoveltySignature,
    ObligationKind,
    ProblemContract,
    ProofObligation,
    RepresentationCandidate,
    ReverseGoalPlan,
    RouteRole,
    StrategyCard,
    SurpriseMutationDirective,
    VerifiedExperienceRecord,
    NegativeAnalogyRecord,
    stable_hash,
)
from ..store import ArtifactStore
from .analogy_agent import AnalogyAgent
from .assignment import InspirationAssignmentPlanner
from .composer import InspirationComposer
from .construction_inventor import AuxiliaryConstructionInventor
from .cross_run_learning import CrossRunLearningStore
from .domain_operators import DomainOperatorRegistry
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
from .surprise_mutation import ControlledMutationPlanner
from .trigger_policy import InspirationSnapshot, TriggerPolicy

if TYPE_CHECKING:
    from ..communication.broker import MessageBroker


_CREDITABLE_MATERIALIZATION_ACTIONS = frozenset(
    {
        "attached",
        "route_created",
        "computation_requested",
        "bridge_requested",
        "obligation_only",
        "composition_source",
    }
)
_ROUTE_CREDIT_ACTIONS = frozenset({"attached", "route_created", "composition_source"})


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
        self.proof_control_context_provider = None
        self.project_root = (
            Path(project_root) if project_root is not None else Path.cwd()
        )
        self.trigger_policy = TriggerPolicy(self.inspiration_config)
        self.domain_operator_registry = DomainOperatorRegistry()
        self.switchboard = RepresentationSwitchboard(self.domain_operator_registry)
        root = self.project_root
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
        self.construction_inventor = AuxiliaryConstructionInventor(
            self.domain_operator_registry
        )
        self.invariant_agent = InvariantHypothesisAgent()
        self.reverse_goal_analyzer = ReverseGoalAnalyzer(self.inspiration_config)
        self.mutation_planner = ControlledMutationPlanner(self.domain_operator_registry)
        self.composer = InspirationComposer(self.inspiration_config)
        self.meta_strategist = PersistentMetaStrategist(self.inspiration_config)
        self.meta_controller = MetaDirectiveController(
            self.inspiration_config, self.route_registry
        )
        self.outcome_ledger = InspirationOutcomeLedger(self.inspiration_config)
        self.experience_distiller = VerifiedExperienceDistiller(self.inspiration_config)
        self.cross_run_store = CrossRunLearningStore(
            self.inspiration_config,
            project_root=root,
        )
        final_reserve = _protected_finalization_calls(config)
        self.surprise_explorer = SurpriseBudgetExplorer(
            self.inspiration_config,
            max_total_calls=config.budget.max_total_calls,
            finalization_reserve_calls=final_reserve,
        )
        self.novelty_gate = NoveltyGate(self.inspiration_config)
        self.referee = InspirationReferee(self.inspiration_config)
        self.mechanism_normalizer = MechanismNormalizer()
        self.assignment_planner = InspirationAssignmentPlanner(self.inspiration_config)
        self.triggers: dict[str, InspirationTrigger] = {}
        self.tasks: dict[str, InspirationTask] = {}
        self.proposal_assignment_plans: dict[str, InspirationAssignmentPlan] = {}
        self.proposals: dict[str, InspirationProposal] = {}
        self.mechanism_chain_signatures: dict[str, MechanismChainSignature] = {}
        self.reviews: dict[str, InspirationReview] = {}
        self.materializations: dict[str, InspirationMaterialization] = {}
        self.credit_targets: dict[str, InspirationCreditTarget] = {}
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
        self.domain_operator_selections: dict[str, list[str]] = {}
        self.mutation_directives: dict[str, SurpriseMutationDirective] = {}
        self.frontier_states: dict[str, BidirectionalFrontierState] = {}
        self.compositions: dict[str, ComposedInspiration] = {}
        self.pending_composed_proposals: dict[str, InspirationProposal] = {}
        self.quick_falsification_passed: set[str] = set()
        self.cross_run_loaded_experience_ids: set[str] = set()
        self.cross_run_loaded_negative_ids: set[str] = set()
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
        self._load_cross_run_learning()
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

    def _record_mechanism_chain(
        self,
        proposal_id: str,
        signature: NoveltySignature,
    ) -> None:
        chain = MechanismChainSignature.from_novelty_signature(signature)
        if chain.complete:
            self.mechanism_chain_signatures[proposal_id] = chain

    def _load_cross_run_learning(self) -> None:
        if not self.cross_run_store.enabled:
            return
        try:
            experiences = self.cross_run_store.load_experiences()
            negatives = self.cross_run_store.load_negatives()
            historical_outcomes = self.cross_run_store.load_outcomes()
        except (OSError, ValueError, TypeError) as exc:
            self.cross_run_store.diagnostics.append(
                f"cross-run learning load rejected: {exc}"
            )
            return
        for experience in experiences:
            self.analogy_library.add_verified_record(experience.model_dump(mode="json"))
            self.cross_run_loaded_experience_ids.add(experience.record_id)
        for negative in negatives:
            self.analogy_library.add_negative_record(negative.model_dump(mode="json"))
            self.cross_run_loaded_negative_ids.add(negative.record_id)
        self.outcome_ledger.load_historical(historical_outcomes)
        self._event(
            "cross_run_learning_loaded",
            "Approved cross-run learning loaded",
            "Historical records affect retrieval and scheduling only, never proof status.",
            {
                "experience_count": len(experiences),
                "negative_count": len(negatives),
                "outcome_count": len(historical_outcomes),
                "diagnostics": list(self.cross_run_store.diagnostics),
            },
        )

    def persist_cross_run_learning(self, *, run_verified: bool) -> dict[str, int]:
        """Persist only typed public outcomes; no prompt or private reasoning is stored."""

        try:
            result = self.cross_run_store.persist(
                experiences=self.verified_experiences.values(),
                negatives=self.negative_analogy_records.values(),
                outcomes=self.outcome_ledger.outcomes.values(),
                run_verified=run_verified,
            )
        except (OSError, TimeoutError, ValueError, TypeError) as exc:
            self._event(
                "cross_run_learning_failed",
                "Cross-run learning persistence failed",
                str(exc),
                {"run_verified": run_verified},
            )
            return {"experiences": 0, "negatives": 0, "outcomes": 0}
        if self.cross_run_store.enabled:
            self._event(
                "cross_run_learning_persisted",
                "Approved cross-run learning persisted",
                "Only verified experiences, scoped negative transfers, and public outcome metrics were stored.",
                {"run_verified": run_verified, **result},
            )
        return result

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
                self._record_mechanism_chain(
                    proposal.proposal_id,
                    proposal.novelty_signature,
                )
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
            if task.mechanism == InspirationMechanism.REPRESENTATION_SWITCH:
                artifact = self._normalize_domain_operator_artifact(
                    task,
                    artifact,
                    snapshot,
                    proposal_slot=proposal_slot,
                )
            proposal = (
                self._from_surprise(
                    task,
                    artifact,
                    snapshot,
                    proposal_slot=proposal_slot,
                )
                if task.mechanism == InspirationMechanism.SURPRISE_EXPLORATION
                else self._from_representation(task, artifact, snapshot)
            )
        elif isinstance(artifact, ConstructionProposal):
            if task.mechanism == InspirationMechanism.AUXILIARY_CONSTRUCTION:
                artifact = self._normalize_domain_operator_artifact(
                    task,
                    artifact,
                    snapshot,
                    proposal_slot=proposal_slot,
                )
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
        if task.mechanism == InspirationMechanism.SURPRISE_EXPLORATION:
            mutation = self.surprise_mutation_directive(
                task,
                snapshot,
                proposal_slot=proposal_slot,
            )
            if mutation is not None:
                if proposal.mutation is not None and proposal.mutation != mutation:
                    self._event(
                        "surprise_mutation_normalized",
                        "Surprise mutation restored to admitted directive",
                        "The model-returned mutation differed from the seeded control artifact.",
                        {
                            "proposal_id": proposal.proposal_id,
                            "expected_directive_id": mutation.directive_id,
                            "returned_directive_id": proposal.mutation.directive_id,
                        },
                    )
                signature_payload = proposal.novelty_signature.model_dump(mode="json")
                signature_payload["mechanism_tags"] = list(
                    dict.fromkeys(
                        [
                            *signature_payload["mechanism_tags"],
                            "surprise_exploration",
                            mutation.operator_id,
                        ]
                    )
                )
                signature_payload["key_transformations"] = list(
                    dict.fromkeys(
                        [
                            *signature_payload["key_transformations"],
                            mutation.operator_id,
                        ]
                    )
                )
                signature_payload["normalized_hash"] = ""
                proposal = proposal.model_copy(
                    update={
                        "mutation": mutation,
                        "novelty_signature": NoveltySignature.model_validate(
                            signature_payload
                        ),
                    }
                )
        payload = proposal.model_dump(mode="json")
        raw_chain = MechanismChainSignature.from_novelty_signature(
            proposal.novelty_signature
        )
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
        if raw_chain.complete:
            self.mechanism_chain_signatures[normalized.proposal_id] = raw_chain
        else:
            self._record_mechanism_chain(
                normalized.proposal_id,
                normalized.novelty_signature,
            )
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
        if not snapshot.problem_hash:
            snapshot = snapshot.model_copy(
                update={"problem_hash": self.problem.integrity_hash}
            )
        trigger = self.triggers.get(proposal.trigger_id)
        if trigger is None:
            return
        credit_route_ids, credit_obligation_ids = self._initial_credit_scope(
            proposal, trigger
        )
        kinds: list[ObligationKind] = []
        graph_obligations = {
            item.obligation_id: item for item in self.proof_graph.obligations
        }
        for obligation_id in credit_obligation_ids:
            raw = snapshot.obligation_kinds.get(obligation_id)
            if raw is None and obligation_id in graph_obligations:
                raw = graph_obligations[obligation_id].kind.value
            if raw is None:
                continue
            try:
                kinds.append(ObligationKind(raw))
            except ValueError:
                continue
        debt_before = sum(
            snapshot.proof_debt_by_route.get(route_id, 0.0)
            for route_id in credit_route_ids
        )
        self.outcome_ledger.register(
            proposal,
            snapshot=snapshot,
            trigger=trigger,
            obligation_kinds=kinds,
            proof_debt_before=debt_before,
            credit_route_ids=credit_route_ids,
            credit_obligation_ids=credit_obligation_ids,
        )

    def _initial_credit_scope(
        self,
        proposal: InspirationProposal,
        trigger: InspirationTrigger,
    ) -> tuple[list[str], list[str]]:
        route_ids = list(
            dict.fromkeys(proposal.target_route_ids or trigger.affected_route_ids)
        )
        task = self.tasks.get(proposal.task_id or "")
        known_obligations = {
            item.obligation_id for item in self.proof_graph.obligations
        }
        obligation_ids = list(
            dict.fromkeys(
                [
                    *proposal.generated_obligations,
                    *proposal.novelty_signature.targeted_obligation_ids,
                    *(task.target_obligation_ids if task is not None else []),
                    *(
                        item
                        for item in trigger.evidence_refs
                        if item in known_obligations
                    ),
                ]
            )
        )
        return route_ids, obligation_ids

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

    def register_assignment_plan(
        self, plan: InspirationAssignmentPlan
    ) -> InspirationAssignmentPlan:
        self.proposal_assignment_plans[plan.task_id] = plan
        self._event(
            "inspiration_proposer_assignment_planned",
            "Inspiration proposer population assigned",
            plan.deferred_reason
            or "Distinct live agents were assigned before budget admission.",
            plan.model_dump(mode="json"),
        )
        self._checkpoint()
        return plan

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

    def domain_operator_catalog(
        self,
        task: InspirationTask,
        snapshot: InspirationSnapshot,
    ) -> list[dict[str, object]]:
        if not self.inspiration_config.domain_operator_plugins_enabled:
            return []
        families = {
            InspirationMechanism.REPRESENTATION_SWITCH: ("representation",),
            InspirationMechanism.AUXILIARY_CONSTRUCTION: ("construction",),
            InspirationMechanism.SURPRISE_EXPLORATION: ("mutation",),
        }.get(task.mechanism, ())
        if not families:
            return []
        forbidden = [
            tag
            for signature in snapshot.route_signatures
            for tag in (
                *signature.representation_tags,
                *signature.mechanism_tags,
                *signature.key_transformations,
            )
        ]
        operators = self.domain_operator_registry.select(
            self.problem,
            domain=snapshot.domain,
            families=families,
            forbidden=forbidden,
            limit=self.inspiration_config.domain_operator_max_prompt_items,
        )
        if task.task_id not in self.domain_operator_selections:
            operator_ids = [item.operator_id for item in operators]
            self.domain_operator_selections[task.task_id] = operator_ids
            self._event(
                "domain_operator_catalog_selected",
                "Domain operator catalog selected",
                task.reason,
                {
                    "task_id": task.task_id,
                    "mechanism": task.mechanism.value,
                    "operator_ids": operator_ids,
                },
            )
            self._checkpoint()
        return self.domain_operator_registry.prompt_payload(operators)

    def _normalize_domain_operator_artifact(
        self,
        task: InspirationTask,
        artifact: RepresentationCandidate | ConstructionProposal,
        snapshot: InspirationSnapshot,
        *,
        proposal_slot: int,
    ) -> RepresentationCandidate | ConstructionProposal:
        if not self.inspiration_config.domain_operator_plugins_enabled:
            return artifact
        catalog = self.domain_operator_catalog(task, snapshot)
        allowed_ids = [str(item["operator_id"]) for item in catalog]
        if not allowed_ids:
            return artifact
        requested = artifact.operator_id
        selected_id = requested if requested in allowed_ids else None
        if selected_id is None:
            selected_id = allowed_ids[proposal_slot % len(allowed_ids)]
        operator = self.domain_operator_registry.get(selected_id)
        if operator is None:
            raise ValueError(f"admitted domain operator is unavailable: {selected_id}")

        signature = artifact.novelty_signature.model_dump(mode="json")
        signature["representation_tags"] = list(
            dict.fromkeys(
                [
                    *signature["representation_tags"],
                    *operator.representation_tags,
                ]
            )
        )
        signature["mechanism_tags"] = list(
            dict.fromkeys(
                [
                    *signature["mechanism_tags"],
                    operator.operator_id,
                    *operator.mechanism_tags,
                ]
            )
        )
        signature["core_objects"] = list(
            dict.fromkeys([*signature["core_objects"], *operator.object_tags])
        )
        signature["key_transformations"] = list(
            dict.fromkeys(
                [
                    *signature["key_transformations"],
                    operator.operator_id,
                ]
            )
        )
        signature["normalized_hash"] = ""
        payload = artifact.model_dump(mode="json")
        payload.update(
            {
                "operator_id": operator.operator_id,
                "operator_preconditions": list(operator.preconditions),
                "generated_obligations": list(operator.generated_obligations),
                "reversibility_requirements": list(operator.reversibility_requirements),
                "novelty_signature": NoveltySignature.model_validate(
                    signature
                ).model_dump(mode="json"),
            }
        )
        if isinstance(artifact, RepresentationCandidate):
            payload["new_candidate_tools"] = list(
                dict.fromkeys(
                    [*artifact.new_candidate_tools, *operator.suggested_tools]
                )
            )
            payload["fast_failure_tests"] = list(
                dict.fromkeys(
                    [*artifact.fast_failure_tests, *operator.fast_failure_tests]
                )
            )
            payload["failure_risks"] = list(
                dict.fromkeys([*artifact.failure_risks, *operator.known_failure_modes])
            )
            payload["known_failure_modes"] = list(operator.known_failure_modes)
            normalized: RepresentationCandidate | ConstructionProposal = (
                RepresentationCandidate.model_validate(payload)
            )
        else:
            payload["suggested_tools"] = list(
                dict.fromkeys([*artifact.suggested_tools, *operator.suggested_tools])
            )
            payload["falsification_tests"] = list(
                dict.fromkeys(
                    [*artifact.falsification_tests, *operator.fast_failure_tests]
                )
            )
            payload["failure_conditions"] = list(
                dict.fromkeys(
                    [*artifact.failure_conditions, *operator.known_failure_modes]
                )
            )
            normalized = ConstructionProposal.model_validate(payload)
        if requested != selected_id:
            self._event(
                "domain_operator_normalized",
                "Domain operator restored to admitted catalog",
                "A missing or unrecognized operator was replaced deterministically.",
                {
                    "task_id": task.task_id,
                    "proposal_slot": proposal_slot,
                    "requested_operator_id": requested,
                    "selected_operator_id": selected_id,
                },
            )
        return normalized

    def surprise_mutation_directive(
        self,
        task: InspirationTask,
        snapshot: InspirationSnapshot,
        *,
        proposal_slot: int,
    ) -> SurpriseMutationDirective | None:
        if not self.inspiration_config.controlled_surprise_mutation_enabled:
            return None
        directive = self.mutation_planner.plan(
            self.problem,
            task_id=task.task_id,
            proposal_slot=proposal_slot,
            target_obligation_ids=(
                task.target_obligation_ids or snapshot.open_obligation_ids
            ),
            domain=snapshot.domain,
            existing_signatures=snapshot.route_signatures,
        )
        if directive.directive_id not in self.mutation_directives:
            self.mutation_directives[directive.directive_id] = directive
            self._event(
                "surprise_mutation_directive_created",
                "Controlled surprise mutation admitted",
                directive.transformation,
                directive.model_dump(mode="json"),
            )
            self._checkpoint()
        return directive

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
            supplied_review = supplied.get(proposal.proposal_id)
            if (
                supplied_review is not None
                and supplied_review.review_status == "deferred"
            ):
                continue
            local_review = self.referee.review(
                proposal,
                reviewer_agent_id=reviewer_agent_id,
                open_obligation_ids=snapshot.open_obligation_ids,
                existing_signatures=existing,
                immediate_counterexamples=counterexamples.get(proposal.proposal_id, []),
                hidden_assumptions=assumptions.get(proposal.proposal_id, []),
            )
            review = supplied_review or local_review
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

    def record_quick_falsification(
        self,
        proposal_id: str,
        *,
        passed: bool,
        reason: str,
    ) -> None:
        if proposal_id not in self.proposals:
            return
        if passed:
            self.quick_falsification_passed.add(proposal_id)
        else:
            self.quick_falsification_passed.discard(proposal_id)
        self._event(
            "inspiration_quick_falsification_passed"
            if passed
            else "inspiration_quick_falsification_failed",
            "Inspiration quick falsification recorded",
            reason,
            {"proposal_id": proposal_id, "passed": passed},
        )
        self._checkpoint()

    def queue_compositions(
        self,
        proposals: Iterable[InspirationProposal],
        reviews: Iterable[InspirationReview],
        state: InspirationSnapshot | dict[str, Any] | None = None,
    ) -> list[ComposedInspiration]:
        snapshot = self._coerce_snapshot(state)
        existing_sources = {
            tuple(sorted(item.source_proposal_ids))
            for item in self.compositions.values()
        }
        composed = self.composer.compose(
            proposals,
            reviews,
            quick_falsification_passed=self.quick_falsification_passed,
            proof_graph=self.proof_graph,
            existing_composition_sources=existing_sources,
        )
        for item in composed:
            source_proposals = [
                self.proposals[source_id] for source_id in item.source_proposal_ids
            ]
            trigger_id = source_proposals[0].trigger_id
            target_routes = list(
                dict.fromkeys(
                    route_id
                    for proposal in source_proposals
                    for route_id in proposal.target_route_ids
                )
            )
            task = InspirationTask(
                task_id=f"inspiration_task_{item.composition_id}",
                trigger_id=trigger_id,
                mechanism=InspirationMechanism.INSPIRATION_COMPOSITION,
                target_route_ids=target_routes,
                target_obligation_ids=item.target_obligation_ids,
                reason=(
                    "Combine complementary independently reviewed mechanisms: "
                    + ", ".join(item.combined_mechanism)
                ),
                max_proposals=1,
            )
            proposal = self._proposal(
                task,
                item.novelty_signature,
                snapshot,
                statement=item.first_executable_step,
                rationale=(
                    "Bridge interfaces compose complementary inspiration mechanisms."
                ),
                composition=item,
                target_routes=target_routes,
                estimated_cost=item.estimated_cost,
            ).model_copy(
                update={
                    "source_agent_id": "inspiration_composer",
                    "proposal_id": f"inspiration_{item.composition_id}",
                }
            )
            self.compositions[item.composition_id] = item
            self.pending_composed_proposals[task.task_id] = proposal
            if self.inspiration_config.mode == "active":
                self.pending_directive_tasks[task.task_id] = task
            self._event(
                "inspiration_composition_queued",
                "Complementary inspiration composition queued",
                item.first_executable_step,
                {
                    "composition": item.model_dump(mode="json"),
                    "task_id": task.task_id,
                    "proposal_id": proposal.proposal_id,
                    "requires_independent_review": True,
                },
            )
        if composed:
            self._checkpoint()
        return composed

    def defer_composed_sources(
        self,
        reviews: Iterable[InspirationReview],
        compositions: Iterable[ComposedInspiration],
    ) -> list[InspirationReview]:
        source_ids = {
            source_id
            for composition in compositions
            for source_id in composition.source_proposal_ids
        }
        updated: list[InspirationReview] = []
        for review in reviews:
            if review.proposal_id in source_ids and review.recommendation in {
                "create_new_route",
                "attach_to_existing_route",
            }:
                review = review.model_copy(update={"recommendation": "store_insight"})
                self.reviews[review.proposal_id] = review
            updated.append(review)
        return updated

    def pending_composition_for_task(
        self,
        task_id: str,
        state: InspirationSnapshot | dict[str, Any] | None = None,
    ) -> InspirationProposal | None:
        proposal = self.pending_composed_proposals.get(task_id)
        if proposal is None or proposal.proposal_id in self.proposals:
            return proposal
        snapshot = self._coerce_snapshot(state)
        self.proposals[proposal.proposal_id] = proposal
        self._record_mechanism_chain(
            proposal.proposal_id,
            proposal.novelty_signature,
        )
        self._register_outcome(proposal, snapshot)
        self.mechanism_stats[InspirationMechanism.INSPIRATION_COMPOSITION.value][
            "proposal_count"
        ] += 1
        self._event(
            "inspiration_composition_admitted",
            "Composed inspiration admitted for independent review",
            proposal.statement,
            {
                "task_id": task_id,
                "proposal_id": proposal.proposal_id,
            },
        )
        self._checkpoint()
        return proposal

    def requeue_tasks(
        self,
        tasks: Iterable[InspirationTask],
        *,
        reasons: dict[str, str] | None = None,
    ) -> None:
        changed = False
        reasons = reasons or {}
        for task in tasks:
            if task.task_id not in self.tasks:
                continue
            self.tasks.pop(task.task_id, None)
            self.pending_directive_tasks[task.task_id] = task
            changed = True
            self._event(
                "inspiration_task_deferred",
                "Inspiration task deferred before model execution",
                reasons.get(task.task_id, "scheduler capacity was unavailable"),
                task.model_dump(mode="json"),
            )
        if changed:
            self._checkpoint()

    def materialize(
        self,
        reviews: Iterable[InspirationReview],
        state: InspirationSnapshot | dict[str, Any] | None = None,
    ) -> list[InspirationMaterialization]:
        snapshot = self._coerce_snapshot(state)
        decisions: list[InspirationMaterialization] = []
        for review in reviews:
            if review.review_status != "completed":
                continue
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
            if proposal.task_id is not None:
                self.pending_composed_proposals.pop(proposal.task_id, None)
            self.outcome_ledger.record_materialization(
                proposal.proposal_id,
                action=decision.action,
                refuted=(decision.action == "rejected"),
            )
            self._register_credit_target(proposal, decision)
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

    def _register_credit_target(
        self,
        proposal: InspirationProposal,
        decision: InspirationMaterialization,
        *,
        emit_event: bool = True,
    ) -> InspirationCreditTarget:
        outcome = self.outcome_ledger.outcomes.get(proposal.proposal_id)
        existing = self.credit_targets.get(proposal.proposal_id)
        action = (
            "obligation_only"
            if decision.action == "stored_insight" and decision.obligation_ids
            else decision.action
        )
        route_ids = list(
            dict.fromkeys(
                [
                    *(outcome.credit_route_ids if outcome is not None else []),
                    *(existing.route_ids if existing is not None else []),
                    *proposal.target_route_ids,
                    *([decision.route_id] if decision.route_id else []),
                ]
            )
        )
        obligation_ids = list(
            dict.fromkeys(
                [
                    *(outcome.credit_obligation_ids if outcome is not None else []),
                    *(existing.obligation_ids if existing is not None else []),
                    *proposal.generated_obligations,
                    *decision.obligation_ids,
                ]
            )
        )
        target = InspirationCreditTarget(
            proposal_id=proposal.proposal_id,
            route_ids=route_ids,
            obligation_ids=obligation_ids,
            message_ids=list(
                dict.fromkeys(
                    [
                        *(existing.message_ids if existing is not None else []),
                        *decision.message_ids,
                    ]
                )
            ),
            materialization_action=action,
        )
        self.credit_targets[proposal.proposal_id] = target

        if proposal.composition is not None and action in {
            "attached",
            "route_created",
            "obligation_only",
        }:
            for source_id in proposal.composition.source_proposal_ids:
                source = self.credit_targets.get(source_id)
                source_outcome = self.outcome_ledger.outcomes.get(source_id)
                self.credit_targets[source_id] = InspirationCreditTarget(
                    proposal_id=source_id,
                    route_ids=list(
                        dict.fromkeys(
                            [
                                *(
                                    source.route_ids
                                    if source is not None
                                    else (
                                        source_outcome.credit_route_ids
                                        if source_outcome is not None
                                        else []
                                    )
                                ),
                                *route_ids,
                            ]
                        )
                    ),
                    obligation_ids=list(
                        dict.fromkeys(
                            [
                                *(
                                    source.obligation_ids
                                    if source is not None
                                    else (
                                        source_outcome.credit_obligation_ids
                                        if source_outcome is not None
                                        else []
                                    )
                                ),
                                *obligation_ids,
                            ]
                        )
                    ),
                    message_ids=list(
                        dict.fromkeys(
                            [
                                *(source.message_ids if source is not None else []),
                                *decision.message_ids,
                            ]
                        )
                    ),
                    materialization_action="composition_source",
                )
        if emit_event:
            self._event(
                "inspiration_credit_target_registered",
                "Inspiration credit target registered",
                proposal.statement,
                target.model_dump(mode="json"),
            )
        return target

    def _generate_task(
        self, task: InspirationTask, snapshot: InspirationSnapshot
    ) -> list[InspirationProposal]:
        if task.mechanism == InspirationMechanism.INSPIRATION_COMPOSITION:
            pending = self.pending_composed_proposals.get(task.task_id)
            return [pending] if pending is not None else []
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
                use_domain_operators=(
                    self.inspiration_config.domain_operator_plugins_enabled
                ),
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
                use_domain_operators=(
                    self.inspiration_config.domain_operator_plugins_enabled
                ),
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
                        facts=self._admitted_inspiration_facts(),
                        round_index=snapshot.round_index,
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
                use_domain_operators=(
                    self.inspiration_config.domain_operator_plugins_enabled
                ),
            )
            candidates.reverse()
            return [
                self._from_surprise(
                    task,
                    item,
                    snapshot,
                    proposal_slot=index,
                )
                for index, item in enumerate(candidates[: task.max_proposals])
            ]
        return []

    def _admitted_inspiration_facts(self) -> list[MessageEnvelope]:
        if self.broker is not None:
            return list(self.broker.admitted_facts())
        if self.config.topology.mode == "hierarchical_sparse":
            return []
        return list(self.typed_memory.facts)

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
        mutation: SurpriseMutationDirective | None = None,
        composition: ComposedInspiration | None = None,
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
            mutation=mutation,
            composition=composition,
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
        if self.inspiration_config.bidirectional_frontier_enabled:
            try:
                target = self.proof_graph.get_obligation(item.target_obligation_id)
            except KeyError:
                target = None
            if target is not None:
                item = self.reverse_goal_analyzer.enrich_agent_plan(
                    item,
                    target,
                    facts=self._admitted_inspiration_facts(),
                    round_index=snapshot.round_index,
                )
                self.frontier_states[item.target_obligation_id] = (
                    self.reverse_goal_analyzer.state(
                        item,
                        round_index=snapshot.round_index,
                    )
                )
                self._event(
                    "bidirectional_frontier_updated",
                    "Forward and backward proof frontiers updated",
                    item.goal,
                    self.frontier_states[item.target_obligation_id].model_dump(
                        mode="json"
                    ),
                )
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
        *,
        proposal_slot: int = 0,
    ) -> InspirationProposal:
        mutation = self.surprise_mutation_directive(
            task,
            snapshot,
            proposal_slot=proposal_slot,
        )
        payload = item.novelty_signature.model_dump(mode="json")
        payload["mechanism_tags"] = list(
            dict.fromkeys(
                [
                    *payload["mechanism_tags"],
                    "surprise_exploration",
                    *([mutation.operator_id] if mutation is not None else []),
                ]
            )
        )
        if mutation is not None:
            payload["key_transformations"] = list(
                dict.fromkeys(
                    [
                        *payload["key_transformations"],
                        mutation.operator_id,
                    ]
                )
            )
        payload["normalized_hash"] = ""
        signature = NoveltySignature.model_validate(payload)
        return self._proposal(
            task,
            signature,
            snapshot,
            statement=(
                f"{mutation.transformation}. {item.rewritten_problem_view}"
                if mutation is not None
                else item.rewritten_problem_view
            ),
            rationale=(
                "Protected surprise budget applies a replayable controlled mutation"
                if mutation is not None
                else "Protected surprise budget tests a mechanism outside current routes"
            ),
            representation=item,
            mutation=mutation,
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
        if review.recommendation == "store_insight":
            return InspirationMaterialization(
                proposal_id=proposal.proposal_id,
                action="stored_insight",
                reason="the independently reviewed proposal remains an Insight",
            )
        obligation_ids = self._attach_obligations(proposal)
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
            message_id = self._publish_typed(
                proposal, route.route_id, snapshot.round_index
            )
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
                message_ids=[message_id] if message_id is not None else [],
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
        message_id: str | None = None
        if route_id:
            message_id = self._publish_typed(proposal, route_id, snapshot.round_index)
        return InspirationMaterialization(
            proposal_id=proposal.proposal_id,
            action="attached" if route_id else "stored_insight",
            route_id=route_id,
            obligation_ids=obligation_ids,
            message_ids=[message_id] if message_id is not None else [],
            reason="proposal remains an Insight pending ordinary proof validation",
        )

    def _attach_obligations(self, proposal: InspirationProposal) -> list[str]:
        if proposal.composition is not None:
            targets = [
                self.proof_graph.get_obligation(item_id)
                for item_id in proposal.composition.target_obligation_ids
                if item_id
                in {item.obligation_id for item in self.proof_graph.obligations}
            ]
            if not targets:
                return []
            anchor = targets[0]
            created: list[str] = []
            for statement in proposal.composition.new_obligations:
                identifier = (
                    "obl_composed_"
                    + stable_hash((proposal.composition.composition_id, statement))[:12]
                )
                obligation = ProofObligation(
                    obligation_id=identifier,
                    problem_hash=anchor.problem_hash,
                    route_ids=list(
                        dict.fromkeys(
                            route_id for item in targets for route_id in item.route_ids
                        )
                    ),
                    kind=ObligationKind.LEMMA,
                    statement=statement,
                    normalized_statement=" ".join(statement.casefold().split()),
                    assumptions=list(anchor.assumptions),
                    quantifiers=list(anchor.quantifiers),
                    dependency_ids=[],
                    status="open",
                    priority=min(1.0, anchor.priority + 0.1),
                    centrality=anchor.centrality,
                )
                created.append(
                    self.proof_graph.add_obligation(obligation).obligation_id
                )
            return created
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

    def attribute_verified_fact(
        self,
        evidence_message_id: str,
        *,
        source_route_id: str | None,
        closed_obligation_ids: Iterable[str] = (),
        dependency_message_ids: Iterable[str] = (),
        direct_proposal_ids: Iterable[str] = (),
    ) -> list[str]:
        """Credit every explicitly mapped proposal that contributed to a Fact."""

        closed = set(closed_obligation_ids)
        dependencies = set(dependency_message_ids)
        matched = {
            proposal_id
            for proposal_id in direct_proposal_ids
            if proposal_id in self.proposals
        }
        for proposal_id, target in self.credit_targets.items():
            if (
                proposal_id not in self.proposals
                or target.materialization_action
                not in _CREDITABLE_MATERIALIZATION_ACTIONS
            ):
                continue
            route_match = bool(
                source_route_id
                and target.materialization_action in _ROUTE_CREDIT_ACTIONS
                and source_route_id in target.route_ids
            )
            obligation_match = bool(closed & set(target.obligation_ids))
            message_match = bool(dependencies & set(target.message_ids))
            if route_match or obligation_match or message_match:
                matched.add(proposal_id)

        for proposal_id in sorted(matched):
            if proposal_id not in self.credit_targets:
                proposal = self.proposals[proposal_id]
                outcome = self.outcome_ledger.outcomes.get(proposal_id)
                materialization = self.materializations.get(proposal_id)
                self.credit_targets[proposal_id] = InspirationCreditTarget(
                    proposal_id=proposal_id,
                    route_ids=list(
                        dict.fromkeys(
                            [
                                *(
                                    outcome.credit_route_ids
                                    if outcome is not None
                                    else proposal.target_route_ids
                                ),
                                *([source_route_id] if source_route_id else []),
                            ]
                        )
                    ),
                    obligation_ids=list(
                        dict.fromkeys(
                            [
                                *(
                                    outcome.credit_obligation_ids
                                    if outcome is not None
                                    else proposal.generated_obligations
                                ),
                                *closed,
                            ]
                        )
                    ),
                    materialization_action=(
                        materialization.action
                        if materialization is not None
                        else "route_created"
                    ),
                )
            self.mark_verified(
                proposal_id,
                evidence_message_id,
                closed_obligation_ids=closed,
            )
        if matched:
            self._event(
                "inspiration_fact_credit_attributed",
                "Verified Fact attributed to inspiration",
                evidence_message_id,
                {
                    "evidence_message_id": evidence_message_id,
                    "proposal_ids": sorted(matched),
                    "source_route_id": source_route_id,
                    "closed_obligation_ids": sorted(closed),
                    "dependency_message_ids": sorted(dependencies),
                },
            )
        return sorted(matched)

    def mark_verified(
        self,
        proposal_id: str,
        evidence_message_id: str,
        *,
        closed_obligation_ids: Iterable[str] | None = None,
    ) -> None:
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
        credit_target = self.credit_targets.get(proposal_id)
        if (
            credit_target is not None
            and evidence_message_id in credit_target.message_ids
        ):
            return
        if (
            credit_target is None
            and self.verified_proposals.get(proposal_id) == evidence_message_id
        ):
            return
        self.verified_proposals[proposal_id] = evidence_message_id
        proposal = self.proposals[proposal_id]
        mechanism = proposal.mechanism.value
        stats = self.mechanism_stats[mechanism]
        stats["verified_count"] += 1
        stats["consecutive_no_verified_gain"] = 0
        snapshot = self._last_snapshot
        outcome_before = self.outcome_ledger.outcomes.get(proposal_id)
        target_routes = (
            outcome_before.credit_route_ids
            if outcome_before is not None
            else proposal.target_route_ids
        )
        proof_debt_after = sum(
            self.proof_graph.proof_debt(route_id)
            for route_id in target_routes
            if any(route.route_id == route_id for route in self.route_registry.routes)
        )
        eligible_obligations = set(
            credit_target.obligation_ids
            if credit_target is not None
            else (
                outcome_before.credit_obligation_ids
                if outcome_before is not None
                else proposal.generated_obligations
            )
        )
        if closed_obligation_ids is None:
            closed_obligations = [
                item.obligation_id
                for item in self.proof_graph.obligations
                if item.obligation_id in eligible_obligations
                and item.status == "closed"
            ]
        else:
            closed_obligations = sorted(
                eligible_obligations & set(closed_obligation_ids)
            )
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
        if credit_target is not None:
            self.credit_targets[proposal_id] = credit_target.model_copy(
                update={
                    "message_ids": list(
                        dict.fromkeys([*credit_target.message_ids, evidence_message_id])
                    )
                }
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

    def mark_final_citations(
        self,
        *,
        route_ids: Iterable[str] = (),
        obligation_ids: Iterable[str] = (),
        message_ids: Iterable[str] = (),
        direct_proposal_ids: Iterable[str] = (),
    ) -> list[str]:
        routes = set(route_ids)
        obligations = set(obligation_ids)
        messages = set(message_ids)
        cited = {
            proposal_id
            for proposal_id in direct_proposal_ids
            if proposal_id in self.verified_proposals
        }
        for proposal_id, target in self.credit_targets.items():
            if proposal_id not in self.verified_proposals:
                continue
            route_match = bool(
                target.materialization_action in _ROUTE_CREDIT_ACTIONS
                and routes & set(target.route_ids)
            )
            obligation_match = bool(obligations & set(target.obligation_ids))
            message_match = bool(messages & set(target.message_ids))
            if route_match or obligation_match or message_match:
                cited.add(proposal_id)
        for proposal_id in sorted(cited):
            self.mark_proposal_cited(proposal_id)
        return sorted(cited)

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
    ) -> str | None:
        if self.broker is None:
            return None
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
                return None
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
        decision = self.broker.publish(
            message, referee_agent_id=None, current_round=current_round
        )
        if not decision.accepted:
            return None
        return decision.duplicate_of or message.message_id

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
            self.store.write_json(
                "inspiration",
                "bidirectional_frontiers",
                {
                    key: value.model_dump(mode="json")
                    for key, value in self.frontier_states.items()
                },
            )
            self.store.write_json(
                "inspiration",
                "surprise_mutation_directives",
                {
                    key: value.model_dump(mode="json")
                    for key, value in self.mutation_directives.items()
                },
            )
            self.store.write_json(
                "inspiration",
                "compositions",
                {
                    key: value.model_dump(mode="json")
                    for key, value in self.compositions.items()
                },
            )
            self.store.write_json(
                "inspiration",
                "credit_targets",
                {
                    key: value.model_dump(mode="json")
                    for key, value in self.credit_targets.items()
                },
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
            "proposal_assignment_plans": {
                key: value.model_dump(mode="json")
                for key, value in self.proposal_assignment_plans.items()
            },
            "proposals": {
                key: value.model_dump(mode="json")
                for key, value in self.proposals.items()
            },
            "mechanism_chain_signatures": {
                key: value.model_dump(mode="json")
                for key, value in self.mechanism_chain_signatures.items()
            },
            "reviews": {
                key: value.model_dump(mode="json")
                for key, value in self.reviews.items()
            },
            "materializations": {
                key: value.model_dump(mode="json")
                for key, value in self.materializations.items()
            },
            "credit_targets": {
                key: value.model_dump(mode="json")
                for key, value in self.credit_targets.items()
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
            "domain_operator_selections": {
                key: list(value)
                for key, value in self.domain_operator_selections.items()
            },
            "mutation_directives": {
                key: value.model_dump(mode="json")
                for key, value in self.mutation_directives.items()
            },
            "frontier_states": {
                key: value.model_dump(mode="json")
                for key, value in self.frontier_states.items()
            },
            "compositions": {
                key: value.model_dump(mode="json")
                for key, value in self.compositions.items()
            },
            "pending_composed_proposals": {
                key: value.model_dump(mode="json")
                for key, value in self.pending_composed_proposals.items()
            },
            "quick_falsification_passed": sorted(self.quick_falsification_passed),
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
            "cross_run_learning": {
                "enabled": self.cross_run_store.enabled,
                "path": str(self.cross_run_store.root),
                "loaded_experience_ids": sorted(self.cross_run_loaded_experience_ids),
                "loaded_negative_ids": sorted(self.cross_run_loaded_negative_ids),
                "diagnostics": list(self.cross_run_store.diagnostics),
            },
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
        self.proposal_assignment_plans = {
            str(key): InspirationAssignmentPlan.model_validate(value)
            for key, value in dict(state.get("proposal_assignment_plans", {})).items()
        }
        self.proposals = {
            str(key): InspirationProposal.model_validate(value)
            for key, value in dict(state.get("proposals", {})).items()
        }
        self.mechanism_chain_signatures = {
            str(key): MechanismChainSignature.model_validate(value)
            for key, value in dict(state.get("mechanism_chain_signatures", {})).items()
        }
        if not self.mechanism_chain_signatures:
            for proposal in self.proposals.values():
                self._record_mechanism_chain(
                    proposal.proposal_id,
                    proposal.novelty_signature,
                )
        self.reviews = {
            str(key): InspirationReview.model_validate(value)
            for key, value in dict(state.get("reviews", {})).items()
        }
        self.materializations = {
            str(key): InspirationMaterialization.model_validate(value)
            for key, value in dict(state.get("materializations", {})).items()
        }
        self.credit_targets = {
            str(key): InspirationCreditTarget.model_validate(value)
            for key, value in dict(state.get("credit_targets", {})).items()
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
        self.domain_operator_selections = {
            str(key): [str(item) for item in value]
            for key, value in dict(state.get("domain_operator_selections", {})).items()
        }
        self.mutation_directives = {
            str(key): SurpriseMutationDirective.model_validate(value)
            for key, value in dict(state.get("mutation_directives", {})).items()
        }
        self.frontier_states = {
            str(key): BidirectionalFrontierState.model_validate(value)
            for key, value in dict(state.get("frontier_states", {})).items()
        }
        self.compositions = {
            str(key): ComposedInspiration.model_validate(value)
            for key, value in dict(state.get("compositions", {})).items()
        }
        self.pending_composed_proposals = {
            str(key): InspirationProposal.model_validate(value)
            for key, value in dict(state.get("pending_composed_proposals", {})).items()
        }
        self.quick_falsification_passed = {
            str(value) for value in state.get("quick_falsification_passed", [])
        }
        self.outcome_ledger.restore_state(dict(state.get("outcomes", {})))
        self._migrate_credit_state()
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
        cross_run = dict(state.get("cross_run_learning", {}))
        self.cross_run_loaded_experience_ids.update(
            str(value) for value in cross_run.get("loaded_experience_ids", [])
        )
        self.cross_run_loaded_negative_ids.update(
            str(value) for value in cross_run.get("loaded_negative_ids", [])
        )

    def _migrate_credit_state(self) -> None:
        """Backfill explicit attribution for checkpoints written before v0.7."""

        for proposal_id, outcome in list(self.outcome_ledger.outcomes.items()):
            proposal = self.proposals.get(proposal_id)
            if proposal is None:
                continue
            trigger = self.triggers.get(proposal.trigger_id)
            if trigger is None:
                continue
            route_ids, obligation_ids = self._initial_credit_scope(proposal, trigger)
            self.outcome_ledger.outcomes[proposal_id] = outcome.model_copy(
                update={
                    "credit_route_ids": (outcome.credit_route_ids or route_ids),
                    "credit_obligation_ids": (
                        outcome.credit_obligation_ids or obligation_ids
                    ),
                }
            )
        for proposal_id, materialization in self.materializations.items():
            proposal = self.proposals.get(proposal_id)
            if proposal is not None:
                self._register_credit_target(
                    proposal,
                    materialization,
                    emit_event=False,
                )
        for proposal_id, evidence_message_id in self.verified_proposals.items():
            target = self.credit_targets.get(proposal_id)
            if target is None:
                continue
            self.credit_targets[proposal_id] = target.model_copy(
                update={
                    "message_ids": list(
                        dict.fromkeys([*target.message_ids, evidence_message_id])
                    )
                }
            )
