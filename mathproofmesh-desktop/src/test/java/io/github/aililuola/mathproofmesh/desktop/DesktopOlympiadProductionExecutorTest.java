package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadBenchmarkPlan;
import io.github.aililuola.mathproofmesh.orchestration.BudgetResourceVector;
import io.github.aililuola.mathproofmesh.orchestration.PricingSnapshot;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

final class DesktopOlympiadProductionExecutorTest {
  @Test
  void rejectsAPlanThatCannotReachItsInitialResearchQueue() {
    DesktopRuntimeLocator locator =
        new DesktopRuntimeLocator(DesktopLiveRunExecutionBackendTest.projectRoot(), null);
    SystemConfig base = locator.loadProfile("deepseek-v4-pro.yaml");
    PricingSnapshot pricing = new DesktopBudgetRuntime("benchmark-test", base).pricing();
    OlympiadBenchmarkPlan.RunSpec smoke = OlympiadBenchmarkPlan.fullSchedule().getFirst();
    SystemConfig configured =
        DesktopOlympiadProductionExecutor.benchmarkConfig(base, smoke, pricing);
    ObjectNode root = (ObjectNode) ContractObjectMapper.toTree(configured);
    ObjectNode budget = (ObjectNode) root.path("budget");
    budget.put("max_total_calls", 24);
    budget.put("max_total_tokens", 1_152_000);
    budget.put("max_cost_usd", 1.00224d);
    SystemConfig undersized = ContractObjectMapper.read(root, SystemConfig.class);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                DesktopOlympiadProductionExecutor.requireInitialExplorationCapacity(
                    undersized, smoke, pricing));

    assertEquals(
        "BENCHMARK_INITIAL_EXPLORATION_ENVELOPE_EXHAUSTED", failure.getMessage());
  }

  @Test
  void validatesEveryFrozenTierWithoutConsumingProtectedFinalizationReserve() {
    DesktopRuntimeLocator locator =
        new DesktopRuntimeLocator(DesktopLiveRunExecutionBackendTest.projectRoot(), null);
    SystemConfig base = locator.loadProfile("deepseek-v4-pro.yaml");
    PricingSnapshot pricing = new DesktopBudgetRuntime("benchmark-test", base).pricing();
    int checkedRuns = 0;

    for (OlympiadBenchmarkPlan.RunSpec spec : OlympiadBenchmarkPlan.fullSchedule()) {
      SystemConfig configured =
          DesktopOlympiadProductionExecutor.benchmarkConfig(base, spec, pricing);
      int verificationCalls =
          1
              + configured.budget().highRiskVerifierReplicas()
              + configured.scheduler().verificationCallSafetyMargin();
      int revisionCycles =
          Math.min(
              configured.scheduler().reserveRevisionCycles(),
              configured.budget().maxRevisions());
      int requestedReserve =
          1
              + verificationCalls
              + revisionCycles * (1 + verificationCalls)
              + configured.scheduler().finishTransitionBufferCalls();
      int finalizationReserve =
          Math.min(configured.budget().maxTotalCalls(), requestedReserve);
      int exploratoryCalls = configured.budget().maxTotalCalls() - finalizationReserve;
      int perCallOutputLimit =
          DesktopOlympiadProductionExecutor.benchmarkOutputTokenLimit(spec);
      DesktopBudgetRuntime budgetRuntime =
          new DesktopBudgetRuntime("benchmark-budget-test", configured);
      int triageOutputLimit =
          configured.runtime().stageOutputTokenLimits().get("triage");
      BudgetResourceVector firstTriageRequest =
          new BudgetResourceVector(
              1L,
              configured.budget().estimatedInputTokensPerCall(),
              triageOutputLimit,
              Math.addExact(
                  configured.budget().estimatedInputTokensPerCall(), triageOutputLimit),
              BigDecimal.ZERO);

      assertEquals(spec.tier().maximumCalls(), configured.budget().maxTotalCalls());
      assertEquals(spec.tier().maximumRounds(), configured.budget().maxRounds());
      assertEquals(spec.tier().maximumTokens(), configured.budget().maxTotalTokens());
      assertEquals(16_000, configured.budget().estimatedInputTokensPerCall());
      assertEquals(32_000, perCallOutputLimit);
      assertEquals(32_000, configured.runtime().jsonRepairMaxOutputTokens());
      assertTrue(
          configured.topology().inspiration().surpriseBudgetMinCalls() <= exploratoryCalls,
          () -> spec.identity() + " consumes the protected finalization reserve");
      assertEquals(
          Math.min(base.topology().inspiration().surpriseBudgetMinCalls(), exploratoryCalls),
          configured.topology().inspiration().surpriseBudgetMinCalls());
      assertTrue(
          configured.agents().stream()
              .allMatch(agent -> agent.maxOutputTokens() <= perCallOutputLimit),
          () -> spec.identity() + " retains an oversized agent output envelope");
      assertTrue(
          configured.runtime().stageOutputTokenLimits().values().stream()
              .allMatch(limit -> limit <= perCallOutputLimit),
          () -> spec.identity() + " retains an oversized stage output envelope");
      assertTrue(
          configured.continuation().maxOutputTokensPerSegment() <= perCallOutputLimit,
          () -> spec.identity() + " retains an oversized continuation output envelope");
      assertTrue(
          budgetRuntime
              .finishReserve()
              .plus(firstTriageRequest)
              .fitsWithin(budgetRuntime.limit()),
          () -> spec.identity() + " leaves no multidimensional budget for triage");
      checkedRuns++;
    }

    assertEquals(34, checkedRuns);
  }

  @Test
  void appliesOnlyFrozenTierBudgetCredentialRotationAndNoRawReasoningPersistence() {
    DesktopRuntimeLocator locator =
        new DesktopRuntimeLocator(DesktopLiveRunExecutionBackendTest.projectRoot(), null);
    SystemConfig base = locator.loadProfile("deepseek-v4-pro.yaml");
    PricingSnapshot pricing = new DesktopBudgetRuntime("benchmark-test", base).pricing();
    OlympiadBenchmarkPlan.RunSpec p09 =
        OlympiadBenchmarkPlan.fullSchedule().stream()
            .filter(run -> run.identity().equals("P09/T1"))
            .findFirst()
            .orElseThrow();

    SystemConfig configured =
        DesktopOlympiadProductionExecutor.benchmarkConfig(base, p09, pricing);

    assertEquals(48, configured.budget().maxTotalCalls());
    assertEquals(8, configured.budget().maxRounds());
    assertEquals(2_304_000, configured.budget().maxTotalTokens());
    assertEquals(16_000, configured.budget().estimatedInputTokensPerCall());
    assertEquals(
        0,
        new BigDecimal("2.00448")
            .compareTo(BigDecimal.valueOf(configured.budget().maxCostUsd())));
    assertFalse(configured.budget().scaleBudgetWithDifficulty());
    assertFalse(configured.runtime().saveRawProviderResponses());
    assertEquals(5, configured.runtime().maxParallelCalls());
    assertEquals(4, configured.concurrency().researchSlots());
    assertEquals(1, configured.concurrency().coordinationSlots());
    assertEquals(
        "DEEPSEEK_API_KEY_D", configured.agents().getFirst().apiKeyEnv());
    assertEquals(
        java.util.List.of(
            "DEEPSEEK_API_KEY_A",
            "DEEPSEEK_API_KEY_B",
            "DEEPSEEK_API_KEY_C",
            "DEEPSEEK_API_KEY_E"),
        configured.agents().subList(1, 5).stream().map(agent -> agent.apiKeyEnv()).toList());
    assertEquals(base.agents().getFirst().temperature(), configured.agents().getFirst().temperature());
    assertEquals(base.agents().getFirst().model(), configured.agents().getFirst().model());
    assertEquals("DEEPSEEK_AGENT_1_KEY", base.agents().getFirst().apiKeyEnv());
  }
}
