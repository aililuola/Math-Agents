package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopComputationWrongFocusedObligationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void routeFocusCannotCreateMathematicalAuthorityBinding() throws Exception {
    int wrongFocusBindings;
    try (var harness =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "wrong-focus-binding")) {
      harness.initializeRoute();
      var spec = DesktopComputationIssue010Support.finiteMap("wrong-focus");
      harness.addObligation("wrong-focused-obligation", spec.targetClaim());
      harness.focus("wrong-focused-obligation");

      var trace = harness.runComputation(spec);

      wrongFocusBindings =
          trace.targetBinding().isolatedComputationQuestion()
                  && !trace.targetBinding().obligationId().equals("wrong-focused-obligation")
              ? 0
              : 1;
      assertThat(wrongFocusBindings).isZero();
      assertThat(harness.obligation("wrong-focused-obligation").status()).isEqualTo("open");
    }

    System.out.println("WRONG_FOCUS_OBLIGATION_BINDINGS=" + wrongFocusBindings);
  }
}
