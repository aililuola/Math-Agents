package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadBenchmarkPlan;
import io.github.aililuola.mathproofmesh.orchestration.BudgetResourceVector;
import io.github.aililuola.mathproofmesh.orchestration.PricingSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DesktopOlympiadInitialExplorationBudgetCompatibilityTest {
  private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);

  @Test
  void everyTierCanFundWorstCaseAdmissionInitialRoutesAndProtectedFinalization() {
    DesktopRuntimeLocator locator =
        new DesktopRuntimeLocator(DesktopLiveRunExecutionBackendTest.projectRoot(), null);
    SystemConfig base = locator.loadProfile("deepseek-v4-pro.yaml");
    PricingSnapshot pricing = new DesktopBudgetRuntime("benchmark-pricing", base).pricing();
    List<String> incompatibleRuns = new ArrayList<>();
    int checkedRuns = 0;

    for (OlympiadBenchmarkPlan.RunSpec spec : OlympiadBenchmarkPlan.fullSchedule()) {
      SystemConfig configured =
          DesktopOlympiadProductionExecutor.benchmarkConfig(base, spec, pricing);
      DesktopBudgetRuntime runtime =
          new DesktopBudgetRuntime("initial-exploration-" + spec.identity(), configured);
      int admissionCalls =
          3 + Math.multiplyExact(2, configured.budget().strategiesToGenerate());
      long admissionInput =
          Math.multiplyExact(
              (long) admissionCalls, configured.budget().estimatedInputTokensPerCall());
      long admissionOutput =
          Math.multiplyExact(
              (long) admissionCalls,
              DesktopOlympiadProductionExecutor.benchmarkOutputTokenLimit(spec));
      BudgetResourceVector admission =
          new BudgetResourceVector(
              admissionCalls,
              admissionInput,
              admissionOutput,
              Math.addExact(admissionInput, admissionOutput),
              cost(admissionInput, admissionOutput, pricing));
      BudgetResourceVector required =
          admission
              .plus(
                  runtime.estimateInitialExploration(
                      configured.budget().initialPaths()))
              .plus(runtime.finishReserve());
      if (!required.fitsWithin(runtime.limit())) {
        incompatibleRuns.add(spec.identity());
      }
      checkedRuns++;
    }

    System.out.println("OLYMPIAD INITIAL EXPLORATION BUDGET COMPATIBILITY DIAGNOSTIC");
    System.out.println("RUNS_CHECKED=" + checkedRuns);
    System.out.println("WORST_CASE_PRE_ROUTE_ADMISSION_CALLS=15");
    System.out.println("INITIAL_ROUTES=3");
    System.out.println("INCOMPATIBLE_RUNS=" + incompatibleRuns.size());
    System.out.println("INCOMPATIBLE_IDENTITIES=" + incompatibleRuns);
    System.out.println("RESULT=" + (incompatibleRuns.isEmpty() ? "PASS" : "FAIL"));

    assertEquals(List.of(), incompatibleRuns);
  }

  private static BigDecimal cost(long input, long output, PricingSnapshot pricing) {
    return BigDecimal.valueOf(input)
        .multiply(pricing.inputPerMillion())
        .add(BigDecimal.valueOf(output).multiply(pricing.outputPerMillion()))
        .divide(ONE_MILLION, 12, RoundingMode.CEILING)
        .stripTrailingZeros();
  }
}
