package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopCounterexampleWrongTargetNoRefutationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void counterexampleRefutesOnlyItsIsolatedComputationQuestion() throws Exception {
    int wrongTargetRefutations;
    try (var harness =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "counterexample-wrong-target")) {
      harness.initializeRoute();
      var spec = DesktopComputationIssue010Support.graphCounterexample("wrong-target-cx", 1);
      harness.addObligation("unbound-counterexample-target", spec.targetClaim());
      harness.focus("unbound-counterexample-target");
      int negativesBefore = harness.typedMemory().negatives().size();

      var trace = harness.runComputation(spec);

      wrongTargetRefutations =
          "open".equals(harness.obligation("unbound-counterexample-target").status()) ? 0 : 1;
      assertThat(wrongTargetRefutations).isZero();
      assertThat(trace.targetBinding().isolatedComputationQuestion()).isTrue();
      assertThat(harness.obligation(trace.targetBinding().obligationId()).status())
          .isEqualTo("refuted");
      assertThat(harness.typedMemory().negatives()).hasSize(negativesBefore);
    }

    System.out.println("WRONG_TARGET_REFUTATIONS=" + wrongTargetRefutations);
  }
}
