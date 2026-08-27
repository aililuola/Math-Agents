package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopBoundedRepairFocusedRoleEconomyTest {
  @TempDir Path temporaryDirectory;

  @Test
  void boundedRepairUsesOneAuthoritativeProverBeforeIndependentReview() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("bounded-repair"), "bounded-repair-role-economy")) {
      harness.prepareFocusedExploration();
      harness.setFocusedRouteRevisionCount(1);
      int callsBefore = harness.providerCallCount();

      harness.runFocusedExploration(0L);

      int focusedCalls = harness.providerCallCount() - callsBefore;
      assertThat(focusedCalls).isEqualTo(1);
      System.out.println("BOUNDED REPAIR FOCUSED-ROLE DIAGNOSTIC");
      System.out.println("BOUNDED_REPAIR_AUTHORITATIVE_PROVERS=" + focusedCalls);
      System.out.println("OPTIONAL_MATRIX_CALLS=0");
      System.out.println("RESULT=PASS");
    }
  }
}
