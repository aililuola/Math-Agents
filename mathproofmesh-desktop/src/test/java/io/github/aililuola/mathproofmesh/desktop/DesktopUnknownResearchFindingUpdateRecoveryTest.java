package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopUnknownResearchFindingUpdateRecoveryTest {
  private static final int UNKNOWN_UPDATES = 4;

  @Test
  void unknownOptionalFindingUpdatesAreRejectedWithoutDiscardingTheValidResult(
      @TempDir Path directory) throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "unknown-research-finding-update",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.UNKNOWN_FINDING_UPDATE,
            "valid public finding survives an invented update id")) {
      harness.prepareProductionRoute();
      String rootHash = harness.rootHash();
      String negativeHash = harness.negativeHash();

      harness.explorePreparedRoutes();

      var ledger = harness.researchLedger();
      long rejectedUpdates =
          ledger.audit().stream()
              .filter(event -> "reject_unknown_finding_update".equals(event.action()))
              .count();
      long unknownFindingAuthorityMutations =
          ledger.findings().stream()
              .filter(record -> !record.findingId().startsWith("research_finding_"))
              .count();
      long publicFindingsPersisted = ledger.findings().size();
      int rootHashChanges = harness.rootHash().equals(rootHash) ? 0 : 1;
      int negativeHashChanges = harness.negativeHash().equals(negativeHash) ? 0 : 1;

      assertThat(rejectedUpdates).isEqualTo(UNKNOWN_UPDATES);
      assertThat(unknownFindingAuthorityMutations).isZero();
      assertThat(publicFindingsPersisted).isOne();
      assertThat(harness.providerCallCount()).isOne();
      assertThat(rootHashChanges).isZero();
      assertThat(negativeHashChanges).isZero();

      DesktopSolveCheckpoint checkpoint = harness.checkpointRoundTrip();
      try (var restored = harness.restored(checkpoint)) {
        assertThat(
                restored.researchLedger().audit().stream()
                    .filter(event -> "reject_unknown_finding_update".equals(event.action())))
            .hasSize(UNKNOWN_UPDATES);
        assertThat(restored.researchLedger().findings()).hasSize(1);
      }

      System.out.println("UNKNOWN RESEARCH FINDING UPDATE RECOVERY DIAGNOSTIC");
      print("UNKNOWN_FINDING_UPDATES", UNKNOWN_UPDATES);
      print("UNKNOWN_FINDING_UPDATE_REJECTIONS", rejectedUpdates);
      print("VALID_RESULT_APPLICATIONS", 1);
      print("PUBLIC_FINDINGS_PERSISTED", publicFindingsPersisted);
      print("UNKNOWN_FINDING_AUTHORITY_MUTATIONS", unknownFindingAuthorityMutations);
      print("ROOT_HASH_CHANGES", rootHashChanges);
      print("NEGATIVE_REGISTRY_HASH_CHANGES", negativeHashChanges);
      System.out.println("RESULT=PASS");
    }
  }

  private static void print(String name, long value) {
    System.out.println(name + "=" + value);
  }
}
