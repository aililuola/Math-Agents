package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopComputationSimilarityTargetBypassTest {
  @TempDir Path temporaryDirectory;

  @Test
  void evenIdenticalTextCannotReplaceAnExplicitSemanticBinding() throws Exception {
    int similarityOnlyBindings;
    try (var harness =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "similarity-binding")) {
      harness.initializeRoute();
      var spec = DesktopComputationIssue010Support.finiteMap("similarity-only");
      harness.addObligation("textually-identical-obligation", spec.targetClaim());

      var trace = harness.runComputation(spec);

      similarityOnlyBindings =
          trace.targetBinding().isolatedComputationQuestion()
                  && !trace.targetBinding().obligationId().equals("textually-identical-obligation")
              ? 0
              : 1;
      assertThat(similarityOnlyBindings).isZero();
      assertThat(harness.obligation("textually-identical-obligation").status()).isEqualTo("open");
    }

    System.out.println("SIMILARITY_ONLY_AUTHORITY_BINDINGS=" + similarityOnlyBindings);
  }
}
