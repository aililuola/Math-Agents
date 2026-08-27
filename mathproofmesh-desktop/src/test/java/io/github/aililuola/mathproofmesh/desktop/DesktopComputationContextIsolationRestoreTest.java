package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopComputationContextIsolationRestoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void exactCounterexampleAndFormalFactSurviveTwoRealCheckpointRestores() throws Exception {
    DesktopSolveCheckpoint checkpoint;
    DesktopComputationSemanticContextTestSupport.ContextFixture counterexample;
    DesktopComputationSemanticContextTestSupport.ContextFixture formal;
    try (var first =
        DesktopComputationIssue010CoordinatorHarness.openWithFakeFormalKernel(
            temporaryDirectory, "computation-context-restore")) {
      first.initializeRoute();
      first.setRound(5);
      counterexample =
          DesktopComputationSemanticContextTestSupport.bind(
              first,
              DesktopComputationSemanticContextTestSupport.withTargetClaim(
                  DesktopComputationIssue010Support.graphCounterexample(
                      "restore-counterexample", 43),
                  "For every x in D, the restored counterexample target property holds."),
              "restore-counterexample-claim");
      formal =
          DesktopComputationSemanticContextTestSupport.bind(
              first,
              DesktopComputationSemanticContextTestSupport.withTargetClaim(
                  DesktopComputationIssue010Support.formalCertificate("restore-formal"),
                  "For every x in D, the restored formal target property holds."),
              "restore-formal-claim");
      first.runComputation(counterexample.spec());
      first.runComputation(formal.spec());
      checkpoint = first.checkpointRoundTrip();
    }

    int counterexampleContextLosses;
    int formalContextLosses;
    int duplicateNegatives;
    int duplicateFacts;
    DesktopSolveCheckpoint secondCheckpoint;
    try (var restored =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "computation-context-restore")) {
      restored.restore(checkpoint);
      MessageEnvelope negative = restored.typedMemory().negatives().getFirst();
      MessageEnvelope fact = restored.typedMemory().facts().getFirst();
      counterexampleContextLosses =
          DesktopComputationSemanticContextTestSupport.contextLosses(
              negative, counterexample.binding());
      formalContextLosses =
          DesktopComputationSemanticContextTestSupport.contextLosses(
              fact, formal.binding());
      duplicateNegatives = Math.max(0, restored.typedMemory().negatives().size() - 1);
      duplicateFacts = Math.max(0, restored.typedMemory().facts().size() - 1);
      assertThat(restored.exactVerifiedFact(fact, formal.binding())).isTrue();
      secondCheckpoint = restored.checkpointRoundTrip();
    }

    try (var second =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "computation-context-restore")) {
      second.restore(secondCheckpoint);
      duplicateNegatives += Math.max(0, second.typedMemory().negatives().size() - 1);
      duplicateFacts += Math.max(0, second.typedMemory().facts().size() - 1);
      assertThat(
              DesktopComputationSemanticContextTestSupport.contextLosses(
                  second.typedMemory().negatives().getFirst(), counterexample.binding()))
          .isZero();
      assertThat(
              DesktopComputationSemanticContextTestSupport.contextLosses(
                  second.typedMemory().facts().getFirst(), formal.binding()))
          .isZero();
    }

    assertThat(counterexampleContextLosses).isZero();
    assertThat(formalContextLosses).isZero();
    assertThat(duplicateNegatives).isZero();
    assertThat(duplicateFacts).isZero();
    System.out.println(
        "POST_RESTORE_COUNTEREXAMPLE_CONTEXT_LOSSES=" + counterexampleContextLosses);
    System.out.println("POST_RESTORE_FORMAL_FACT_CONTEXT_LOSSES=" + formalContextLosses);
    System.out.println("POST_RESTORE_DUPLICATE_NEGATIVE_RECORDS=" + duplicateNegatives);
    System.out.println("POST_RESTORE_DUPLICATE_FACTS=" + duplicateFacts);
  }
}
