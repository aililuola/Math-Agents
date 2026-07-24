from __future__ import annotations

from dataclasses import dataclass

from ..config import SystemConfig
from ..schemas import (
    ComputationDecision,
    ComputationDecisionStatus,
    ComputationMethod,
    ComputationPurpose,
    ExperimentSpec,
)
from .cache import ComputationLedger, ExperimentCache
from .contracts import validate_experiment_contract


@dataclass(frozen=True, slots=True)
class ComputationContext:
    path_id: str
    stalled_rounds: int = 0
    meta_review_approved: bool = False
    remaining_llm_calls: int = 0
    mandatory_calculation_gate: bool = False


class ComputationGate:
    """Applies the asymmetric, reasoning-first experiment admission policy."""

    _TYPED_METHODS = {
        ComputationMethod.SYMPY_SIMPLIFY,
        ComputationMethod.SYMPY_EQUIVALENT,
        ComputationMethod.POLYNOMIAL_FACTOR,
        ComputationMethod.MODULAR_EXHAUSTIVE,
        ComputationMethod.BOUNDED_INTEGER_SEARCH,
        ComputationMethod.GRAPH_CERTIFICATE,
        ComputationMethod.RECURRENCE_CHECK,
        ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
        ComputationMethod.CANDIDATE_PERIOD_CHECK,
        ComputationMethod.EXACT_GEOMETRY,
        ComputationMethod.NUMERIC_COUNTEREXAMPLE,
        ComputationMethod.LEAN_CHECK,
    }
    _BOUNDED_TYPED_PROBE_METHODS = _TYPED_METHODS - {
        ComputationMethod.NUMERIC_COUNTEREXAMPLE,
        ComputationMethod.LEAN_CHECK,
    }

    def __init__(
        self,
        config: SystemConfig,
        cache: ExperimentCache,
        ledger: ComputationLedger,
    ) -> None:
        self.config = config
        self.cache = cache
        self.ledger = ledger

    def decide(
        self, spec: ExperimentSpec, context: ComputationContext
    ) -> ComputationDecision:
        cfg = self.config.computation
        path_count = self.ledger.count_for_path(context.path_id)
        remaining = max(0, cfg.hard_experiments_per_path - path_count)

        def decision(
            status: ComputationDecisionStatus,
            reason: str,
            rule_id: str,
            *,
            cache_hit: bool = False,
            canonical_request_hash: str | None = None,
            requires_meta_review: bool = False,
        ) -> ComputationDecision:
            return ComputationDecision(
                experiment_id=spec.experiment_id,
                request_hash=spec.request_hash,
                decision=status,
                reason=reason,
                rule_id=rule_id,
                cache_hit=cache_hit,
                canonical_request_hash=canonical_request_hash,
                remaining_experiments=remaining,
                requires_meta_review=requires_meta_review,
            )

        if not cfg.enabled:
            return decision(
                ComputationDecisionStatus.REJECT,
                "Computation is disabled in this profile.",
                "computation.disabled",
            )
        contract_issues = validate_experiment_contract(spec)
        if contract_issues:
            return decision(
                ComputationDecisionStatus.REJECT,
                "Invalid typed-tool contract: " + "; ".join(contract_issues),
                "request.invalid_tool_contract",
            )
        cached_result = self.cache.get(spec.request_hash) if cfg.cache_results else None
        if cached_result is None and cfg.cache_results:
            cached_result = self.cache.get_equivalent(spec)
        required_followups = (
            2
            if spec.method == ComputationMethod.SANDBOXED_PYTHON
            and cached_result is None
            else 1
        )
        if (
            not context.mandatory_calculation_gate
            and context.remaining_llm_calls < required_followups
        ):
            return decision(
                ComputationDecisionStatus.DEFER,
                "Insufficient LLM budget remains to interpret the experiment result.",
                "budget.no_interpretation_call",
            )
        if cached_result is not None:
            return decision(
                ComputationDecisionStatus.ALLOW,
                "An identical completed experiment is available in the run cache.",
                "cache.hit",
                cache_hit=True,
                canonical_request_hash=cached_result.request_hash,
            )
        vague_target = spec.target_claim.casefold()
        vague_basis = spec.reasoning_basis.casefold()
        vague_markers = (
            "枚举看看",
            "看看规律",
            "找找规律",
            "see what happens",
            "look for a pattern",
            "find a pattern",
        )
        if len(spec.target_claim) < 8 or any(
            marker in vague_target for marker in vague_markers
        ):
            return decision(
                ComputationDecisionStatus.REJECT,
                "The request does not state a precise decidable target claim.",
                "request.vague_target",
            )
        decision_fields = {
            "why_computation_is_needed": spec.why_computation_is_needed,
            "decision_if_confirmed": spec.decision_if_confirmed,
            "decision_if_refuted": spec.decision_if_refuted,
            "noncomputational_alternative": spec.noncomputational_alternative,
        }
        missing_decision_field = next(
            (name for name, value in decision_fields.items() if len(value) < 12), None
        )
        if missing_decision_field is not None:
            return decision(
                ComputationDecisionStatus.REJECT,
                f"The request lacks a concrete decision use in {missing_decision_field}.",
                "request.no_decision_use",
            )
        if len(spec.reasoning_basis) < 12:
            return decision(
                ComputationDecisionStatus.REJECT,
                "The request lacks an auditable mathematical reasoning basis.",
                "request.no_reasoning_basis",
            )
        pattern_only_basis = any(marker in vague_basis for marker in vague_markers)
        bounded_typed_probe = (
            cfg.bounded_typed_probe_fast_path
            and cfg.typed_tools_enabled
            and spec.method in self._BOUNDED_TYPED_PROBE_METHODS
            and spec.exact_arithmetic
            and spec.max_cases <= cfg.bounded_typed_probe_max_cases
            and (
                spec.broad_search or spec.purpose == ComputationPurpose.DISCOVER_PATTERN
            )
        )
        if pattern_only_basis and not spec.broad_search:
            return decision(
                ComputationDecisionStatus.REJECT,
                "Pattern hunting must be declared as broad_search and cannot use the falsification fast path.",
                "reasoning_first.pattern_search_misclassified",
            )
        if (
            pattern_only_basis
            and context.stalled_rounds == 0
            and not bounded_typed_probe
        ):
            return decision(
                ComputationDecisionStatus.REJECT,
                "Initial exploration cannot replace a mathematical basis with pattern hunting.",
                "reasoning_first.computation_first",
            )
        if spec.max_cases > cfg.max_cases_per_experiment:
            return decision(
                ComputationDecisionStatus.REJECT,
                "The requested case count exceeds the per-experiment limit.",
                "budget.max_cases",
            )
        if self.ledger.total_cpu_seconds >= cfg.max_total_cpu_seconds:
            return decision(
                ComputationDecisionStatus.DEFER,
                "The run-level computation CPU budget is exhausted.",
                "budget.cpu_exhausted",
            )
        if path_count >= cfg.hard_experiments_per_path:
            return decision(
                ComputationDecisionStatus.REJECT,
                "The path has reached its hard experiment limit.",
                "budget.path_hard_limit",
            )
        if spec.method == ComputationMethod.SANDBOXED_PYTHON:
            if not cfg.sandboxed_python_enabled:
                return decision(
                    ComputationDecisionStatus.REJECT,
                    "Model-generated Python is disabled and typed tools must be used.",
                    "sandbox.disabled",
                )
            if not spec.typed_tool_gap or len(spec.typed_tool_gap) < 12:
                return decision(
                    ComputationDecisionStatus.REJECT,
                    "Sandboxed Python is a last resort and requires a precise typed-tool capability gap.",
                    "sandbox.typed_tool_first",
                )
        elif spec.method not in self._TYPED_METHODS or not cfg.typed_tools_enabled:
            return decision(
                ComputationDecisionStatus.REJECT,
                "The requested typed tool is unavailable.",
                "tool.unavailable",
            )
        if (
            spec.method == ComputationMethod.LEAN_CHECK
            and not self.config.verification.enable_lean
        ):
            return decision(
                ComputationDecisionStatus.REJECT,
                "Lean execution is disabled in the verification profile.",
                "tool.lean_disabled",
            )
        if (
            spec.method == ComputationMethod.NUMERIC_COUNTEREXAMPLE
            and spec.exact_arithmetic
        ):
            return decision(
                ComputationDecisionStatus.REJECT,
                "numeric_counterexample must declare exact_arithmetic=false; only a reproduced candidate is exact evidence.",
                "request.invalid_precision_claim",
            )
        if context.mandatory_calculation_gate:
            return decision(
                ComputationDecisionStatus.ALLOW,
                "Required by the deterministic route-critical calculation gate.",
                "critical_calculation.mandatory_gate",
            )
        if (
            path_count >= cfg.soft_experiments_per_path
            and not context.meta_review_approved
        ):
            return decision(
                ComputationDecisionStatus.DEFER,
                "The path exceeded its soft experiment quota and needs Meta-Reviewer approval.",
                "budget.path_soft_limit",
                requires_meta_review=True,
            )
        if bounded_typed_probe:
            return decision(
                ComputationDecisionStatus.ALLOW,
                (
                    "Allowed as a low-cost exact typed probe. Its finite output is "
                    "bounded evidence only and cannot prove an infinite pattern."
                ),
                "fast_path.bounded_typed_probe",
            )
        if spec.broad_search or spec.purpose == ComputationPurpose.DISCOVER_PATTERN:
            if context.stalled_rounds < cfg.broad_search_after_stalled_rounds:
                return decision(
                    ComputationDecisionStatus.DEFER,
                    "Broad search is deferred until the abstract route has stalled.",
                    "reasoning_first.not_stalled",
                    requires_meta_review=cfg.broad_search_requires_meta_review,
                )
            if (
                cfg.broad_search_requires_meta_review
                and not context.meta_review_approved
            ):
                return decision(
                    ComputationDecisionStatus.DEFER,
                    "Broad search requires explicit Meta-Reviewer approval.",
                    "reasoning_first.meta_required",
                    requires_meta_review=True,
                )
        if (
            spec.purpose == ComputationPurpose.FALSIFY_CLAIM
            and cfg.targeted_falsification_fast_path
            and not spec.broad_search
        ):
            return decision(
                ComputationDecisionStatus.ALLOW,
                "Allowed by the low-cost targeted falsification fast path.",
                "fast_path.targeted_falsification",
            )
        return decision(
            ComputationDecisionStatus.ALLOW,
            "The bounded computation has a clear mathematical and decision purpose.",
            "reasoning_first.necessary_targeted_check",
        )
