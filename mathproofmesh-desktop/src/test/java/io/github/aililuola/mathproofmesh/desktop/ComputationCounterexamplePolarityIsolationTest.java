package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeBlockedException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ComputationCounterexamplePolarityIsolationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void computationCounterexampleBlocksOnlyTheBoundClaimPolarity() throws Exception {
    try (var harness =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "counterexample-polarity-isolation")) {
      harness.initializeRoute();
      var fixture =
          DesktopComputationSemanticContextTestSupport.bind(
              harness,
              DesktopComputationIssue010Support.graphCounterexample(
                  "counterexample-polarity-isolation", 61),
              "counterexample-polarity-claim");
      harness.runComputation(fixture.spec());

      int positiveExactBlocks =
          factPromotionBlocked(
                  harness,
                  DesktopNegativeKnowledgePolarityTestSupport.verifiedFact(
                      fixture.binding(), "positive", "positive"))
              ? 1
              : 0;
      int negativePolarityFalseBlocks =
          factPromotionBlocked(
                  harness,
                  DesktopNegativeKnowledgePolarityTestSupport.verifiedFact(
                      fixture.binding(), "negative", "negative"))
              ? 1
              : 0;

      assertThat(positiveExactBlocks).isOne();
      assertThat(negativePolarityFalseBlocks).isZero();
      System.out.println("POSITIVE_EXACT_REENTRY_BLOCKS=" + positiveExactBlocks);
      System.out.println("NEGATIVE_POLARITY_FALSE_BLOCKS=" + negativePolarityFalseBlocks);
    }
  }

  private static boolean factPromotionBlocked(
      DesktopComputationIssue010CoordinatorHarness harness,
      io.github.aililuola.mathproofmesh.contract.MessageEnvelope fact)
      throws ReflectiveOperationException {
    try {
      harness.typedMemory().addFact(fact, "independent-referee", 0);
      return false;
    } catch (NegativeKnowledgeBlockedException expected) {
      return true;
    }
  }
}
