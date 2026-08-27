package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopFiniteCertificateWrongTargetNoClosureTest {
  @TempDir Path temporaryDirectory;

  @Test
  void finiteCertificateClosesOnlyItsIsolatedComputationQuestion() throws Exception {
    int wrongTargetClosures;
    try (var harness =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "finite-wrong-target")) {
      harness.initializeRoute();
      var spec = DesktopComputationIssue010Support.finiteMap("wrong-target-finite");
      harness.addObligation("unbound-finite-target", spec.targetClaim());
      harness.focus("unbound-finite-target");
      int factsBefore = harness.typedMemory().facts().size();

      var trace = harness.runComputation(spec);

      wrongTargetClosures =
          "open".equals(harness.obligation("unbound-finite-target").status()) ? 0 : 1;
      assertThat(wrongTargetClosures).isZero();
      assertThat(trace.targetBinding().isolatedComputationQuestion()).isTrue();
      assertThat(harness.obligation(trace.targetBinding().obligationId()).status())
          .isEqualTo("closed");
      assertThat(harness.typedMemory().facts()).hasSize(factsBefore);
    }

    System.out.println("WRONG_TARGET_FINITE_CERTIFICATE_CLOSURES=" + wrongTargetClosures);
  }
}
