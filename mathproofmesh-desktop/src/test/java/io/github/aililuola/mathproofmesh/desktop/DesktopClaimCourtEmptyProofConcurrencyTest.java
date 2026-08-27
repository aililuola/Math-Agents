package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimCourtEmptyProofConcurrencyTest {
  @TempDir Path temporaryDirectory;

  @Test
  void concurrentEmptyProofClaimsKeepClaimScopedRevisionIdentities() throws Exception {
    int claimCount = 3;
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("empty-proof-concurrency"),
            "claim-court-empty-proof-concurrency")) {
      harness.prepareEmptyProofClaimCourtBatch(claimCount);
      harness.integrateInstalledRound();

      long courtCases = harness.claimCourt().records().size();
      long revisions = harness.claimProofRevisions().records().size();
      long distinctRevisionIds =
          harness.claimProofRevisions().records().stream()
              .map(revision -> revision.revisionId())
              .distinct()
              .count();

      assertThat(courtCases).isEqualTo(claimCount);
      assertThat(revisions).isEqualTo(claimCount);
      assertThat(distinctRevisionIds).isEqualTo(claimCount);

      System.out.println("CLAIM COURT EMPTY-PROOF CONCURRENCY DIAGNOSTIC");
      System.out.println("CONCURRENT_EMPTY_PROOF_CLAIMS=" + claimCount);
      System.out.println("COURT_CASES=" + courtCases);
      System.out.println("PROOF_REVISIONS=" + revisions);
      System.out.println("DISTINCT_REVISION_IDS=" + distinctRevisionIds);
      System.out.println("REVISION_ID_COLLISIONS=" + (revisions - distinctRevisionIds));
      System.out.println("RESULT=PASS");
    }
  }
}
