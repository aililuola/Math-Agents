package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CriticalClaimContextBinding;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCandidateStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopInvalidStrategyBindingReplenishmentPromptTest {
  @TempDir Path temporaryDirectory;

  @Test
  void productionGapPromptCarriesTypedBindingFailuresAndAdmitsOnlyValidReplacementRoutes()
      throws Exception {
    List<StrategyCard> invalid = new ArrayList<>();
    List<StrategyCard> source = DesktopStrategyPortfolioTestHarness.fourIndependent("invalid");
    for (int index = 0; index < source.size(); index++) {
      invalid.add(
          index % 2 == 0
              ? withInvalidClaimNode(source.get(index), "invented-blueprint-node-" + index)
              : withInvalidLocalAssumptionSelector(source.get(index)));
    }
    List<StrategyCard> valid = DesktopStrategyPortfolioTestHarness.fourIndependent("valid");
    StrategySet initial =
        new StrategySet("Invalid model-proposed blueprint references.", List.of(), invalid);
    StrategySet replacement =
        new StrategySet("Server-selector-bound replacement routes.", List.of(), valid);

    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(
            temporaryDirectory.resolve("run"),
            "invalid-binding-replenishment",
            List.of(initial, replacement))) {
      harness.freeze();
      harness.generateAndAdmit();

      ProviderRequest replenishment =
          harness.providerRequests().stream()
              .filter(
                  request ->
                      request.messages().stream()
                          .anyMatch(
                              message ->
                                  message
                                      .content()
                                      .contains(
                                          "\"generation_mode\":\"portfolio_gap_replenishment\"")))
              .findFirst()
              .orElseThrow();
      String prompt = replenishment.messages().getLast().content();
      List<String> admittedIds =
          harness.admittedStrategies().stream().map(StrategyCard::strategyId).toList();
      List<String> routeIds = harness.routeStrategyIds();
      var rejected =
          harness.candidates().snapshot().records().values().stream()
              .filter(record -> record.strategyId().startsWith("invalid-"))
              .filter(record -> record.status() == StrategyCandidateStatus.REJECTED_INVALID)
              .toList();
      long inventedNodeRejections =
          rejected.stream()
              .filter(record -> record.strategyId().endsWith("extremal")
                  || record.strategyId().endsWith("counting"))
              .count();
      long misScopedSelectorRejections = rejected.size() - inventedNodeRejections;
      long replenishmentPrompts =
          harness.providerRequests().stream()
              .filter(
                  request ->
                      request.messages().stream()
                          .anyMatch(
                              message ->
                                  message
                                      .content()
                                      .contains(
                                          "\"generation_mode\":\"portfolio_gap_replenishment\"")))
              .count();
      long invalidRouteLeaks =
          routeIds.stream().filter(id -> id.startsWith("invalid-")).count();

      assertThat(prompt)
          .contains("invalid_strategy_contract_errors")
          .contains("invalid_strategy_ids")
          .contains("invented-blueprint-node-0")
          .contains("@all_intermediates")
          .contains("mechanism_operation_selectors")
          .contains("critical_claim_node_selector")
          .contains("claim_local_assumption_selectors");
      assertThat(admittedIds)
          .isNotEmpty()
          .allMatch(id -> id.startsWith("valid-"));
      assertThat(routeIds).allMatch(id -> id.startsWith("valid-"));
      assertThat(rejected).hasSize(invalid.size());
      assertThat(inventedNodeRejections).isEqualTo(2L);
      assertThat(misScopedSelectorRejections).isEqualTo(2L);
      assertThat(replenishmentPrompts).isEqualTo(1L);
      assertThat(invalidRouteLeaks).isZero();
      assertThat(harness.rootGoal().sourceStatement())
          .isEqualTo(DesktopStrategyPortfolioTestHarness.SOURCE);

      System.out.println("STRATEGY BINDING CONTRACT RECOVERY DIAGNOSTIC");
      System.out.println("INVALID_STRATEGY_CONTRACT_ERRORS=" + rejected.size());
      System.out.println("INVENTED_NODE_REJECTIONS=" + inventedNodeRejections);
      System.out.println("MISSCOPED_SELECTOR_REJECTIONS=" + misScopedSelectorRejections);
      System.out.println("REPLENISHMENT_PROMPTS=" + replenishmentPrompts);
      System.out.println("VALID_REPLACEMENT_ADMISSIONS=" + admittedIds.size());
      System.out.println("INVALID_ROUTE_LEAKS=" + invalidRouteLeaks);
      System.out.println("ROOT_HASH_CHANGES=0");
      System.out.println("RESULT=PASS");
    }
  }

  private static StrategyCard withInvalidClaimNode(StrategyCard source, String nodeId) {
    CriticalClaimContextBinding binding = source.criticalClaimContextBindings().getFirst();
    return withBindings(
        source,
        List.of(
            new CriticalClaimContextBinding(
                binding.claimId(),
                nodeId,
                binding.localAssumptionNodeIds(),
                binding.localAssumptions(),
                binding.quantifiers(),
                binding.variableBindings(),
                binding.scopeLimitations(),
                binding.polarity())));
  }

  private static StrategyCard withInvalidLocalAssumptionSelector(StrategyCard source) {
    CriticalClaimContextBinding binding = source.criticalClaimContextBindings().getFirst();
    return withBindings(
        source,
        List.of(
            new CriticalClaimContextBinding(
                binding.claimId(),
                binding.claimBlueprintNodeId(),
                List.of("@all_intermediates"),
                binding.localAssumptions(),
                binding.quantifiers(),
                binding.variableBindings(),
                binding.scopeLimitations(),
                binding.polarity())));
  }

  private static StrategyCard withBindings(
      StrategyCard source, List<CriticalClaimContextBinding> bindings) {
    return new StrategyCard(
        source.assignedAgentId(),
        source.bottleneck(),
        source.calculationChecks(),
        source.calculationEvidenceRefs(),
        source.computationHints(),
        source.coreIdea(),
        source.criticalClaims(),
        source.estimatedCost(),
        source.estimatedSuccess(),
        source.expectedLemmas(),
        source.falsificationTest(),
        source.independenceBasis(),
        source.inspirationProposalId(),
        source.keyOriginalStep(),
        source.parentStrategyIds(),
        source.prerequisites(),
        source.strategyId(),
        source.tags(),
        source.title(),
        source.mechanismOperations(),
        bindings);
  }
}
