package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.MechanismOperationDeclaration;
import io.github.aililuola.mathproofmesh.contract.MechanismOperationKind;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCandidateStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopMechanismOperationTopologyReplenishmentPromptTest {
  @TempDir Path temporaryDirectory;

  @Test
  void reachabilityFailuresProduceSetSemanticsAndTopologySafeReplacementTemplates()
      throws Exception {
    List<StrategyCard> invalid =
        DesktopStrategyPortfolioTestHarness.fourIndependent("invalid-topology").stream()
            .map(DesktopMechanismOperationTopologyReplenishmentPromptTest::withInvalidLayerChain)
            .toList();
    List<StrategyCard> valid =
        DesktopStrategyPortfolioTestHarness.fourIndependent("valid-topology");

    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(
            temporaryDirectory.resolve("run"),
            "mechanism-topology-replenishment",
            List.of(
                new StrategySet("Invalid selector-layer interpretation.", List.of(), invalid),
                new StrategySet("Topology-safe replacement declarations.", List.of(), valid)))) {
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
      var rejected =
          harness.candidates().snapshot().records().values().stream()
              .filter(record -> record.strategyId().startsWith("invalid-topology-"))
              .filter(record -> record.status() == StrategyCandidateStatus.REJECTED_INVALID)
              .toList();
      List<String> admittedIds =
          harness.admittedStrategies().stream().map(StrategyCard::strategyId).toList();
      List<String> routeIds = harness.routeStrategyIds();
      long invalidRouteLeaks =
          routeIds.stream().filter(id -> id.startsWith("invalid-topology-")).count();

      assertThat(rejected)
          .hasSize(invalid.size())
          .allSatisfy(
              record ->
                  assertThat(record.detail())
                      .contains("mechanism operation output is not reachable from an input"));
      assertThat(prompt)
          .contains("mechanism_operation_topology_failures")
          .contains("MECHANISM_OPERATION_REACHABILITY_MISMATCH")
          .contains("mechanism_operation_topology_contract")
          .contains("selectors_expand_to_sets")
          .contains("safe_generation_templates")
          .contains("DIRECT_TARGETS_TO_ALL_INTERMEDIATES_NOT_A_LAYER")
          .contains("@roots", "@direct_targets", "@all_intermediates", "@main_goal");
      assertThat(admittedIds)
          .isNotEmpty()
          .allMatch(id -> id.startsWith("valid-topology-"));
      assertThat(routeIds).allMatch(id -> id.startsWith("valid-topology-"));
      assertThat(invalidRouteLeaks).isZero();
      assertThat(harness.rootGoal().sourceStatement())
          .isEqualTo(DesktopStrategyPortfolioTestHarness.SOURCE);

      System.out.println("MECHANISM OPERATION TOPOLOGY RECOVERY DIAGNOSTIC");
      System.out.println("REACHABILITY_REJECTIONS=" + rejected.size());
      System.out.println("TOPOLOGY_FEEDBACK_PROMPTS=1");
      System.out.println("VALID_REPLACEMENT_ADMISSIONS=" + admittedIds.size());
      System.out.println("INVALID_ROUTE_LEAKS=" + invalidRouteLeaks);
      System.out.println("ROOT_HASH_CHANGES=0");
      System.out.println("RESULT=PASS");
    }
  }

  private static StrategyCard withInvalidLayerChain(StrategyCard source) {
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
        List.of(
            new MechanismOperationDeclaration(
                "roots-to-direct-targets",
                MechanismOperationKind.REDUCTION,
                List.of("@roots"),
                List.of("@direct_targets")),
            new MechanismOperationDeclaration(
                "invalid-layer-step",
                MechanismOperationKind.ALGEBRAIC_TRANSFORMATION,
                List.of("@direct_targets"),
                List.of("@all_intermediates")),
            new MechanismOperationDeclaration(
                "intermediates-to-goal",
                MechanismOperationKind.DIRECT,
                List.of("@all_intermediates"),
                List.of("@main_goal"))),
        source.criticalClaimContextBindings());
  }
}
