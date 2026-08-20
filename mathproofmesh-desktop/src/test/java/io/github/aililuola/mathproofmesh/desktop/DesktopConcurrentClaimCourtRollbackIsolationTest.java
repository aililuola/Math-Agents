package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopConcurrentClaimCourtRollbackIsolationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void oneProjectionFailureCannotRollBackSuccessfulSiblingClaims() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("rollback-isolation"), "rollback-isolation")) {
      harness.prepareIndependentClaimCourtBatch(4);
      harness.setFailurePoint(ClaimCourtFailurePoint.AFTER_FACT_PROJECTION_BEFORE_PERSIST);
      harness.integrateInstalledRound();

      Set<String> successfulClaims =
          Set.of("parallel-claim-1", "parallel-claim-2", "parallel-claim-3");
      String failedClaim = "parallel-claim-0";
      Set<String> lifecycle = harness.lifecycleClaimIds();
      Set<String> facts = harness.factMessageIds();
      Set<String> graph = harness.graphClaimMessageIds();

      long successfulSiblingClaimLosses =
          successfulClaims.stream().filter(claim -> !lifecycle.contains(claim)).count();
      long successfulSiblingFactLosses =
          successfulClaims.stream().filter(claim -> !facts.contains(claim)).count();
      long successfulSiblingGraphLosses =
          successfulClaims.stream().filter(claim -> !graph.contains(claim)).count();
      long crossCaseGlobalRollbacks =
          successfulSiblingClaimLosses
              + successfulSiblingFactLosses
              + successfulSiblingGraphLosses;

      assertThat(successfulSiblingClaimLosses).isZero();
      assertThat(successfulSiblingFactLosses).isZero();
      assertThat(successfulSiblingGraphLosses).isZero();
      assertThat(crossCaseGlobalRollbacks).isZero();
      assertThat(facts).doesNotContain(failedClaim);
      assertThat(graph).doesNotContain(failedClaim);

      System.out.println("CLAIM COURT ROLLBACK ISOLATION DIAGNOSTIC");
      System.out.println("CONCURRENT_CASES=4");
      System.out.println("SUCCESSFUL_CASES=3");
      System.out.println("FAILED_PROJECTION_CASES=1");
      System.out.println(
          "SUCCESSFUL_SIBLING_CLAIM_LOSSES=" + successfulSiblingClaimLosses);
      System.out.println(
          "SUCCESSFUL_SIBLING_FACT_LOSSES=" + successfulSiblingFactLosses);
      System.out.println(
          "SUCCESSFUL_SIBLING_GRAPH_LOSSES=" + successfulSiblingGraphLosses);
      System.out.println("CROSS_CASE_GLOBAL_ROLLBACKS=" + crossCaseGlobalRollbacks);
      System.out.println("RESULT=PASS");
    }
  }
}
