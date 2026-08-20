package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopCallStageAtomicCredentialLeaseTest {
  @TempDir Path temporaryDirectory;

  @Test
  void ordinaryCallStageAtomicallySelectsAndReservesEveryAvailableCredential()
      throws Exception {
    try (DesktopComputationIssue010CoordinatorHarness harness =
        DesktopComputationIssue010CoordinatorHarness.openForConcurrency(
            temporaryDirectory.resolve("atomic-call-stage"), "atomic-call-stage")) {
      var run = harness.runAtomicOrdinaryStageCalls(4);
      long distinctLeasedAgents = run.agentIds().stream().distinct().count();
      int idleEligibleCredentialsWhileWaiting =
          Math.max(0, 4 - run.maximumActiveProviderCalls());
      long fixedAgentSelectionRace = Math.max(0L, 4L - distinctLeasedAgents);

      assertThat(run.agentIds()).hasSize(4);
      assertThat(distinctLeasedAgents).isEqualTo(4L);
      assertThat(run.maximumActiveProviderCalls()).isEqualTo(4);
      assertThat(idleEligibleCredentialsWhileWaiting).isZero();
      assertThat(fixedAgentSelectionRace).isZero();
      assertThat(run.leases().leases())
          .filteredOn(lease -> lease.workItemId().startsWith("atomic-stage-"))
          .hasSize(4)
          .extracting(lease -> lease.agentId())
          .doesNotHaveDuplicates();

      System.out.println("CALLSTAGE ATOMIC CREDENTIAL LEASE DIAGNOSTIC");
      System.out.println("CONCURRENT_STAGE_CALLS=4");
      System.out.println("DISTINCT_LEASED_AGENTS=" + distinctLeasedAgents);
      System.out.println(
          "MAX_ACTIVE_PROVIDER_CALLS=" + run.maximumActiveProviderCalls());
      System.out.println(
          "IDLE_ELIGIBLE_CREDENTIALS_WHILE_WAITING="
              + idleEligibleCredentialsWhileWaiting);
      System.out.println("FIXED_AGENT_SELECTION_RACE=" + fixedAgentSelectionRace);
      System.out.println("RESULT=PASS");
    }
  }
}
