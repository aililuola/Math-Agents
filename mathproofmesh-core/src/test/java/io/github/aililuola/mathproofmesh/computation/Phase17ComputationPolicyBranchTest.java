package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ComputationDecision;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Phase17ComputationPolicyBranchTest {

  @Test
  void constructorDisableContractTargetDecisionAndPrecisionGuardsAreCovered() {
    assertThatThrownBy(() -> new ComputationPolicy(null))
        .isInstanceOf(NullPointerException.class);
    ExperimentSpec base = integerSpec();

    assertRule(policy(limits(false, true, false, true, true, 2, 6, 120, 1000)),
        base, context(0, false, 5, 0, 0), usage(0, 0), true, "computation.disabled");
    assertRule(policy(ComputationLimits.defaultsEnabled()),
        ComputationFixtures.spec(ComputationMethod.SYMPY_SIMPLIFY, "{}"),
        context(0, false, 5, 0, 0), usage(0, 0), true, "request.invalid_tool_contract");
    assertRule(policy(ComputationLimits.defaultsEnabled()),
        copy(base, null, null, null, null, null, null, "search", null, null),
        context(0, false, 5, 0, 0), usage(0, 0), true, "request.vague_target");
    assertRule(policy(ComputationLimits.defaultsEnabled()),
        copy(base, null, null, null, null, "keep", "drop", null, null, null),
        context(0, false, 5, 0, 0), usage(0, 0), true, "request.no_decision_use");
    assertRule(policy(ComputationLimits.defaultsEnabled()),
        copy(
            base,
            null,
            null,
            null,
            null,
            "This is a sufficiently specific confirmed decision.",
            "drop",
            null,
            null,
            null),
        context(0, false, 5, 0, 0), usage(0, 0), true, "request.no_decision_use");

    ExperimentSpec numeric =
        ComputationFixtures.spec(
            ComputationMethod.NUMERIC_COUNTEREXAMPLE,
            "{\"lhs\":\"x\",\"rhs\":\"0\",\"variables\":[\"x\"],"
                + "\"ranges\":{\"x\":[0,1]}}");
    assertRule(policy(ComputationLimits.defaultsEnabled()),
        copy(numeric, null, null, null, true, null, null, null, null, null),
        context(0, false, 5, 0, 0), usage(0, 0), true, "request.invalid_precision_claim");
    assertRule(policy(ComputationLimits.defaultsEnabled()),
        numeric, context(0, false, 5, 0, 0), usage(0, 0), true,
        "reasoning_first.precise_check");
  }

  @Test
  void toolRegistrationCaseQuotaAndSandboxBranchesAreCovered() {
    ExperimentSpec base = integerSpec();
    assertRule(policy(limits(true, true, false, true, true, 2, 6, 120, 1)),
        base, context(0, false, 5, 0, 0), usage(0, 0), true, "budget.max_cases");
    assertRule(policy(limits(true, false, false, true, true, 2, 6, 120, 1000)),
        base, context(0, false, 5, 0, 0), usage(0, 0), true, "tool.typed_disabled");
    assertRule(policy(ComputationLimits.defaultsEnabled()),
        base, context(0, false, 5, 0, 0), usage(0, 0), false, "tool.unregistered");

    ExperimentSpec sandbox =
        ComputationFixtures.spec(ComputationMethod.SANDBOXED_PYTHON, "{\"input\":{}}");
    assertRule(policy(ComputationLimits.defaultsEnabled()),
        sandbox, context(0, false, 5, 0, 0), usage(0, 0), true, "sandbox.disabled");
    ComputationLimits sandboxEnabled =
        limits(true, true, true, true, true, 2, 6, 120, 1000);
    assertRule(policy(sandboxEnabled),
        sandbox, context(0, false, 5, 0, 0), usage(0, 0), true,
        "sandbox.typed_gap_required");
    assertRule(policy(sandboxEnabled),
        copy(sandbox, null, null, null, null, null, null, null, " ", null),
        context(0, false, 5, 0, 0), usage(0, 0), true,
        "sandbox.typed_gap_required");
    assertRule(policy(sandboxEnabled),
        copy(sandbox, null, null, null, null, null, null, null, "gap", null),
        context(0, false, 5, 0, 0), usage(0, 0), true, "reasoning_first.precise_check");
  }

  @Test
  void hardCpuInterpretationFastPathBroadAndSoftBudgetBranchesAreCovered() {
    ComputationPolicy defaults = policy(ComputationLimits.defaultsEnabled());
    ExperimentSpec base = integerSpec();
    assertRule(defaults, base, context(0, false, 5, 6, 0), usage(0, 0), true,
        "budget.hard_path_quota");
    assertRule(defaults, base, context(0, false, 5, 0, 0), usage(0, 120), true,
        "budget.cpu_exhausted");
    assertRule(defaults, base, context(0, false, 5, 0, 120), usage(0, 0), true,
        "budget.cpu_exhausted");
    assertRule(defaults, base, context(0, false, 0, 0, 0), usage(0, 0), true,
        "budget.no_interpretation_call");

    ExperimentSpec falsify =
        copy(base, null, null, ComputationPurpose.FALSIFY_CLAIM, null,
            null, null, null, null, false);
    assertRule(defaults, falsify, context(0, false, 5, 0, 0), usage(0, 0), true,
        "fast_path.targeted_falsification");
    ExperimentSpec probe =
        copy(base, null, null, ComputationPurpose.DISCOVER_PATTERN, null,
            null, null, null, null, true);
    assertRule(defaults, probe, context(0, false, 5, 0, 0), usage(0, 0), true,
        "fast_path.bounded_typed_probe");

    ComputationPolicy noFastPaths =
        policy(limits(true, true, false, false, false, 2, 6, 120, 1000));
    ExperimentSpec broad =
        copy(base, null, null, ComputationPurpose.DISCOVER_PATTERN, null,
            null, null, null, null, true);
    assertRule(noFastPaths, broad, context(0, false, 5, 0, 0), usage(0, 0), true,
        "reasoning_first.not_stalled");
    assertRule(noFastPaths, broad, context(2, false, 5, 0, 0), usage(0, 0), true,
        "reasoning_first.meta_required");
    assertRule(noFastPaths, broad, context(2, true, 5, 0, 0), usage(0, 0), true,
        "reasoning_first.precise_check");

    assertRule(noFastPaths, base, context(0, false, 5, 2, 0), usage(0, 0), true,
        "budget.soft_path_quota");
    assertRule(noFastPaths, base, context(0, true, 5, 2, 0), usage(0, 0), true,
        "reasoning_first.precise_check");
  }

  private static ExperimentSpec integerSpec() {
    return ComputationFixtures.spec(
        ComputationMethod.BOUNDED_INTEGER_SEARCH,
        "{\"target\":{\"lhs\":\"x\",\"rhs\":\"x\",\"relation\":\"eq\"}}",
        "{\"x\":{\"min\":0,\"max\":1}}");
  }

  private static ComputationPolicy policy(ComputationLimits limits) {
    return new ComputationPolicy(limits);
  }

  private static ComputationLimits limits(
      boolean enabled,
      boolean typed,
      boolean sandbox,
      boolean falsifyFast,
      boolean probeFast,
      int soft,
      int hard,
      double cpu,
      int maxCases) {
    return new ComputationLimits(
        enabled,
        typed,
        sandbox,
        falsifyFast,
        probeFast,
        25_000,
        soft,
        hard,
        cpu,
        maxCases,
        20_000,
        1,
        true,
        true);
  }

  private static ComputationContext context(
      int stalled, boolean meta, int calls, int experiments, double cpu) {
    return new ComputationContext("path", stalled, meta, calls, experiments, cpu);
  }

  private static ComputationLedger.Usage usage(int experiments, double cpu) {
    return new ComputationLedger.Usage(experiments, cpu);
  }

  private static void assertRule(
      ComputationPolicy policy,
      ExperimentSpec spec,
      ComputationContext context,
      ComputationLedger.Usage usage,
      boolean registered,
      String rule) {
    ComputationDecision decision =
        policy.evaluate(spec, context, usage, registered, Optional.empty());
    assertThat(decision.ruleId()).isEqualTo(rule);
  }

  private static ExperimentSpec copy(
      ExperimentSpec source,
      Integer maxCases,
      ComputationMethod method,
      ComputationPurpose purpose,
      Boolean exact,
      String confirmed,
      String refuted,
      String target,
      String typedGap,
      Boolean broad) {
    return new ExperimentSpec(
        source.arguments(),
        source.assumptions(),
        broad == null ? source.broadSearch() : broad,
        confirmed == null ? source.decisionIfConfirmed() : confirmed,
        refuted == null ? source.decisionIfRefuted() : refuted,
        source.domains(),
        exact == null ? source.exactArithmetic() : exact,
        null,
        source.experimentId() + "-copy",
        maxCases == null ? source.maxCases() : maxCases,
        method == null ? source.method() : method,
        source.noncomputationalAlternative(),
        source.parentCheckpointId(),
        source.pathId(),
        purpose == null ? source.purpose() : purpose,
        source.reasoningBasis(),
        null,
        source.requestedBy(),
        source.runtimeFingerprint(),
        source.seed(),
        target == null ? source.targetClaim() : target,
        typedGap == null ? source.typedToolGap() : typedGap,
        source.whyComputationIsNeeded());
  }
}
