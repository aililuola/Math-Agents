package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationContractRepairStatus;
import io.github.aililuola.mathproofmesh.contract.ComputationDecision;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionStatus;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.List;
import java.util.Optional;

/** Reasoning-first admission gate for bounded computation. */
public final class ComputationPolicy {
  private static final java.util.regex.Pattern VAGUE_TARGET =
      java.util.regex.Pattern.compile(
          "look for a pattern|enumerate and see|^search$",
          java.util.regex.Pattern.CASE_INSENSITIVE
              | java.util.regex.Pattern.UNICODE_CASE);

  private final ComputationLimits limits;

  public ComputationPolicy(ComputationLimits limits) {
    this.limits = java.util.Objects.requireNonNull(limits, "limits");
  }

  public ComputationDecision evaluate(
      ExperimentSpec spec,
      ComputationContext context,
      ComputationLedger.Usage recordedUsage,
      boolean registered,
      Optional<ExperimentResult> cached) {
    int used = Math.max(context.experimentsUsed(), recordedUsage.experiments());
    int remaining = Math.max(0, limits.hardExperimentsPerPath() - used);
    if (!limits.enabled()) {
      return decision(
          spec,
          ComputationDecisionStatus.REJECT,
          "computation.disabled",
          "Computation is disabled.",
          remaining,
          false,
          null,
          false);
    }
    List<String> contractIssues = ContractsFunctions.validateExperimentContract(spec);
    if (!contractIssues.isEmpty()) {
      return decision(
          spec,
          ComputationDecisionStatus.REJECT,
          "request.invalid_tool_contract",
          "Invalid typed-tool contract: " + String.join("; ", contractIssues),
          remaining,
          false,
          null,
          false);
    }
    if (isVague(spec.targetClaim())) {
      return decision(
          spec,
          ComputationDecisionStatus.REJECT,
          "request.vague_target",
          "The computation target must state one precise mathematical claim.",
          remaining,
          false,
          null,
          false);
    }
    if (!decisionUseIsSpecific(spec)) {
      return decision(
          spec,
          ComputationDecisionStatus.REJECT,
          "request.no_decision_use",
          "Both confirmed and refuted outcomes must change a concrete proof decision.",
          remaining,
          false,
          null,
          false);
    }
    if (spec.method() == ComputationMethod.NUMERIC_COUNTEREXAMPLE
        && spec.exactArithmetic()) {
      return decision(
          spec,
          ComputationDecisionStatus.REJECT,
          "request.invalid_precision_claim",
          "numeric_counterexample is sampled and cannot claim exact arithmetic.",
          remaining,
          false,
          null,
          false);
    }
    if (spec.maxCases() > limits.maxCasesPerExperiment()) {
      return decision(
          spec,
          ComputationDecisionStatus.REJECT,
          "budget.max_cases",
          "The request exceeds max_cases_per_experiment.",
          remaining,
          false,
          null,
          false);
    }
    if (!limits.typedToolsEnabled()) {
      return decision(
          spec,
          ComputationDecisionStatus.REJECT,
          "tool.typed_disabled",
          "Typed computation tools are disabled.",
          remaining,
          false,
          null,
          false);
    }
    if (!registered) {
      return decision(
          spec,
          ComputationDecisionStatus.REJECT,
          "tool.unregistered",
          "No registered handler is available for " + spec.method().value() + ".",
          remaining,
          false,
          null,
          false);
    }
    if (spec.method() == ComputationMethod.SANDBOXED_PYTHON) {
      if (!limits.sandboxedPythonEnabled()) {
        return decision(
            spec,
            ComputationDecisionStatus.REJECT,
            "sandbox.disabled",
            "Arbitrary sandboxed Python is disabled by default.",
            remaining,
            false,
            null,
            false);
      }
      if (spec.typedToolGap() == null || spec.typedToolGap().isBlank()) {
        return decision(
            spec,
            ComputationDecisionStatus.REJECT,
            "sandbox.typed_gap_required",
            "sandboxed_python requires a concrete typed_tool_gap.",
            remaining,
            false,
            null,
            false);
      }
    }
    if (cached.isPresent()) {
      return decision(
          spec,
          ComputationDecisionStatus.ALLOW,
          "cache.canonical_hit",
          "A run-scoped canonical result is available.",
          remaining,
          true,
          cached.get().requestHash(),
          false);
    }
    if (remaining == 0) {
      return decision(
          spec,
          ComputationDecisionStatus.REJECT,
          "budget.hard_path_quota",
          "The route has exhausted its hard experiment quota.",
          remaining,
          false,
          null,
          false);
    }
    if (recordedUsage.cpuSeconds() >= limits.maxTotalCpuSeconds()
        || context.cpuSecondsUsed() >= limits.maxTotalCpuSeconds()) {
      return decision(
          spec,
          ComputationDecisionStatus.DEFER,
          "budget.cpu_exhausted",
          "The run has exhausted its computation CPU budget.",
          remaining,
          false,
          null,
          false);
    }
    if (context.remainingLlmCalls() == 0) {
      return decision(
          spec,
          ComputationDecisionStatus.DEFER,
          "budget.no_interpretation_call",
          "One model call must remain for the same explorer to interpret the result.",
          remaining,
          false,
          null,
          false);
    }
    if (limits.targetedFalsificationFastPath()
        && spec.purpose() == ComputationPurpose.FALSIFY_CLAIM
        && !spec.broadSearch()) {
      return decision(
          spec,
          ComputationDecisionStatus.ALLOW,
          "fast_path.targeted_falsification",
          "A precise bounded falsification may run before route stagnation.",
          remaining,
          false,
          null,
          false);
    }
    if (limits.boundedTypedProbeFastPath()
        && spec.purpose() == ComputationPurpose.DISCOVER_PATTERN
        && spec.maxCases() <= limits.boundedTypedProbeMaxCases()
        && isDeterministicTypedProbe(spec.method())) {
      return decision(
          spec,
          ComputationDecisionStatus.ALLOW,
          "fast_path.bounded_typed_probe",
          "A low-cost exact typed probe may run before route stagnation.",
          remaining,
          false,
          null,
          false);
    }
    if (spec.broadSearch()
        && context.stalledRounds() < limits.broadSearchAfterStalledRounds()) {
      return decision(
          spec,
          ComputationDecisionStatus.DEFER,
          "reasoning_first.not_stalled",
          "Broad search remains deferred until the route has stalled.",
          remaining,
          false,
          null,
          limits.broadSearchRequiresMetaReview());
    }
    if (spec.broadSearch()
        && limits.broadSearchRequiresMetaReview()
        && !context.metaReviewApproved()) {
      return decision(
          spec,
          ComputationDecisionStatus.DEFER,
          "reasoning_first.meta_required",
          "Broad search requires explicit meta-review approval.",
          remaining,
          false,
          null,
          true);
    }
    if (used >= limits.softExperimentsPerPath() && !context.metaReviewApproved()) {
      return decision(
          spec,
          ComputationDecisionStatus.DEFER,
          "budget.soft_path_quota",
          "The route exceeded its soft experiment quota and requires meta review.",
          remaining,
          false,
          null,
          true);
    }
    return decision(
        spec,
        ComputationDecisionStatus.ALLOW,
        "reasoning_first.precise_check",
        "The request is precise, bounded, typed, and decision-relevant.",
        remaining,
        false,
        null,
        false);
  }

  private static boolean isVague(String target) {
    String normalized = target == null ? "" : target.strip();
    return normalized.length() < 16
        || VAGUE_TARGET.matcher(normalized).find();
  }

  private static boolean decisionUseIsSpecific(ExperimentSpec spec) {
    return spec.decisionIfConfirmed().trim().length() >= 12
        && spec.decisionIfRefuted().trim().length() >= 12;
  }

  private static boolean isDeterministicTypedProbe(ComputationMethod method) {
    return switch (method) {
      case MODULAR_EXHAUSTIVE,
          BOUNDED_INTEGER_SEARCH,
          GRAPH_CERTIFICATE,
          RECURRENCE_CHECK,
          BOUNDED_GREEDY_SEQUENCE,
          CANDIDATE_PERIOD_CHECK,
          EXACT_GEOMETRY,
          NUMBER_THEORY_CHECK,
          EXACT_LINEAR_ALGEBRA,
          FINITE_SET_MAP_CHECK,
          HYPERGRAPH_TRANSVERSAL -> true;
      default -> false;
    };
  }

  private static ComputationDecision decision(
      ExperimentSpec spec,
      ComputationDecisionStatus status,
      String rule,
      String reason,
      int remaining,
      boolean cacheHit,
      String canonicalRequestHash,
      boolean metaRequired) {
    return new ComputationDecision(
        cacheHit,
        canonicalRequestHash,
        null,
        ComputationContractRepairStatus.NOT_NEEDED,
        null,
        status,
        spec.experimentId(),
        null,
        reason,
        remaining,
        spec.requestHash(),
        metaRequired,
        rule);
  }
}
