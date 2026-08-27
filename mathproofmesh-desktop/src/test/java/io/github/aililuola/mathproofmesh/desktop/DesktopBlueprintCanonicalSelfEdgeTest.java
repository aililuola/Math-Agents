package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopBlueprintCanonicalSelfEdgeTest {
  @TempDir Path temporaryDirectory;

  @Test
  void duplicateBlueprintStepsCollapseWithoutCreatingACanonicalSelfEdge() throws Exception {
    StrategyCard source =
        DesktopStrategyPortfolioTestHarness.strategy(
            "duplicate-blueprint-steps",
            "Duplicate structural bridge",
            "Apply induction after deleting a leaf",
            "The reduced tree preserves the required finite-tree invariant.",
            0.72d);
    StrategyCard duplicateSteps = withDuplicateExpectedLemmas(source);

    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(
            temporaryDirectory, "canonical-blueprint-self-edge")) {
      harness.freeze();
      harness.setStrategies(List.of(duplicateSteps));

      assertThatCode(harness::generateAndAdmit).doesNotThrowAnyException();

      var snapshot = harness.proofGraph().snapshot();
      assertThat(harness.routeStrategyIds()).contains(duplicateSteps.strategyId());
      assertThat(snapshot.aliases()).hasSize(1);
      assertThat(snapshot.edges().values())
          .allSatisfy(edge -> assertThat(edge.sourceId()).isNotEqualTo(edge.targetId()));
      assertThat(
              snapshot.obligations().values().stream()
                  .filter(obligation -> "The same canonical bridge.".equals(obligation.statement())))
          .hasSize(1);

      System.out.println("DUPLICATE_BLUEPRINT_STEPS=2");
      System.out.println("CANONICAL_BLUEPRINT_NODES=1");
      System.out.println("CANONICAL_SELF_EDGE_FAILURES=0");
      System.out.println("RESULT=PASS");
    }
  }

  private static StrategyCard withDuplicateExpectedLemmas(StrategyCard source) {
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
        List.of("The same canonical bridge.", "The same canonical bridge."),
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
