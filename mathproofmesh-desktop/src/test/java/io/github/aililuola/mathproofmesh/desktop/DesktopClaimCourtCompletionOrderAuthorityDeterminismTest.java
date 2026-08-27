package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimCourtCompletionOrderAuthorityDeterminismTest {
  @TempDir Path temporaryDirectory;

  @Test
  void completionOrderCannotChangeCommittedMathematicalAuthority() throws Exception {
    List<Map<String, Long>> schedules =
        List.of(
            delays(5L, 40L, 80L, 120L),
            delays(120L, 80L, 40L, 5L),
            delays(40L, 120L, 5L, 80L));
    List<DesktopClaimSalvageTestHarness.ConcurrencyAuthorityHashes> hashes =
        new ArrayList<>();
    List<List<String>> completionOrders = new ArrayList<>();

    for (int run = 0; run < schedules.size(); run++) {
      try (DesktopClaimSalvageTestHarness harness =
          DesktopClaimSalvageTestHarness.open(
              temporaryDirectory.resolve("completion-order-" + run),
              "claim-court-order-determinism")) {
        harness.prepareIndependentClaimCourtBatch(4);
        harness.setClaimCourtDelays(schedules.get(run));
        harness.integrateInstalledRound();
        hashes.add(harness.concurrencyAuthorityHashes());
        completionOrders.add(harness.claimCourtCompletionOrder());
      }
    }

    long claimLifecycleHashChanges = distinct(hashes.stream().map(value -> value.claimLifecycle()).toList()) - 1L;
    long typedMemoryHashChanges = distinct(hashes.stream().map(value -> value.typedMemory()).toList()) - 1L;
    long proofGraphHashChanges = distinct(hashes.stream().map(value -> value.proofGraph()).toList()) - 1L;
    long attemptArtifactHashChanges = distinct(hashes.stream().map(value -> value.attemptArtifacts()).toList()) - 1L;
    long epochCommitHashChanges = distinct(hashes.stream().map(value -> value.epochCommit()).toList()) - 1L;

    assertThat(completionOrders.stream().distinct().count()).isGreaterThan(1L);
    assertThat(claimLifecycleHashChanges).isZero();
    assertThat(typedMemoryHashChanges).isZero();
    assertThat(proofGraphHashChanges).isZero();
    assertThat(attemptArtifactHashChanges).isZero();
    assertThat(epochCommitHashChanges).isZero();

    System.out.println("CLAIM COURT COMPLETION ORDER DETERMINISM DIAGNOSTIC");
    System.out.println("COMPLETION_ORDERS_EXECUTED=" + completionOrders.size());
    System.out.println("DISTINCT_COMPLETION_ORDERS=" + completionOrders.stream().distinct().count());
    System.out.println("CLAIM_LIFECYCLE_HASH_CHANGES=" + claimLifecycleHashChanges);
    System.out.println("TYPED_MEMORY_HASH_CHANGES=" + typedMemoryHashChanges);
    System.out.println("PROOF_GRAPH_HASH_CHANGES=" + proofGraphHashChanges);
    System.out.println("ATTEMPT_ARTIFACT_HASH_CHANGES=" + attemptArtifactHashChanges);
    System.out.println("EPOCH_COMMIT_HASH_CHANGES=" + epochCommitHashChanges);
    System.out.println("RESULT=PASS");
  }

  private static Map<String, Long> delays(long zero, long one, long two, long three) {
    return Map.of(
        "parallel-claim-0", zero,
        "parallel-claim-1", one,
        "parallel-claim-2", two,
        "parallel-claim-3", three);
  }

  private static long distinct(List<String> values) {
    return values.stream().distinct().count();
  }
}
