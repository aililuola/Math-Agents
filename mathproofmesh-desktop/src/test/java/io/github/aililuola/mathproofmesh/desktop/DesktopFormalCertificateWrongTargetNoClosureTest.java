package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopFormalCertificateWrongTargetNoClosureTest {
  @TempDir Path temporaryDirectory;

  @Test
  void formalCertificateStillRequiresAnExactServerOwnedTargetBinding() throws Exception {
    int wrongTargetClosures;
    try (var harness =
        DesktopComputationIssue010CoordinatorHarness.openWithFakeFormalKernel(
            temporaryDirectory, "formal-wrong-target")) {
      harness.initializeRoute();
      var spec = DesktopComputationIssue010Support.formalCertificate("wrong-target-formal");
      harness.addObligation("unbound-formal-target", spec.targetClaim());
      harness.focus("unbound-formal-target");
      int factsBefore = harness.typedMemory().facts().size();

      var trace = harness.runComputation(spec);

      wrongTargetClosures =
          "open".equals(harness.obligation("unbound-formal-target").status()) ? 0 : 1;
      assertThat(wrongTargetClosures).isZero();
      assertThat(trace.targetBinding().isolatedComputationQuestion()).isTrue();
      assertThat(harness.obligation(trace.targetBinding().obligationId()).status())
          .isEqualTo("closed");
      assertThat(harness.typedMemory().facts()).hasSize(factsBefore);
    }

    System.out.println("WRONG_TARGET_FORMAL_CERTIFICATE_CLOSURES=" + wrongTargetClosures);
    System.out.println("WRONG_TARGET_CERTIFICATE_CLOSURES=" + wrongTargetClosures);
  }
}
