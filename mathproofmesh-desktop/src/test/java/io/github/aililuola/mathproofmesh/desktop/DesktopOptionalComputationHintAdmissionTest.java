package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationHint;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopOptionalComputationHintAdmissionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void optionalFalsificationHintsDoNotAssignAMandatoryToolSpecialist() throws Exception {
    List<StrategyCard> strategies =
        DesktopStrategyPortfolioTestHarness.fourIndependent("hint-only").stream()
            .map(DesktopOptionalComputationHintAdmissionTest::withOptionalHint)
            .toList();
    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(
            temporaryDirectory, "optional-computation-hint")) {
      harness.freeze();
      harness.setStrategies(strategies);
      harness.generateAndAdmit();

      List<String> admittedRoutes = harness.state().routeIds();
      List<String> toolSpecialists = harness.routeToolSpecialistAgentIds();
      assertThat(admittedRoutes).hasSize(4);
      assertThat(toolSpecialists).hasSize(4).allMatch(String::isEmpty);

      System.out.println("OPTIONAL COMPUTATION HINT ADMISSION DIAGNOSTIC");
      System.out.println("HINT_ONLY_STRATEGIES=" + strategies.size());
      System.out.println("ADMITTED_ROUTES=" + admittedRoutes.size());
      System.out.println(
          "TOOL_SPECIALISTS_ASSIGNED="
              + toolSpecialists.stream().filter(agentId -> !agentId.isEmpty()).count());
      System.out.println("RESULT=PASS");
    }
  }

  private static StrategyCard withOptionalHint(StrategyCard source) {
    return new StrategyCard(
        source.assignedAgentId(),
        source.bottleneck(),
        source.calculationChecks(),
        source.calculationEvidenceRefs(),
        List.of(
            new ComputationHint(
                false,
                "Discard the route only if this search finds a counterexample.",
                ComputationPurpose.FALSIFY_CLAIM,
                ComputationMethod.BOUNDED_INTEGER_SEARCH,
                source.criticalClaims().getFirst().statement())),
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
        source.criticalClaimContextBindings());
  }
}
