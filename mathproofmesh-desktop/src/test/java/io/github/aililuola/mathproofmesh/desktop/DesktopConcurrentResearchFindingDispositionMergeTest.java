package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.aililuola.mathproofmesh.research.ResearchFindingStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopConcurrentResearchFindingDispositionMergeTest {
  @TempDir Path tempDir;

  @Test
  void stablePrimaryWorkerOwnsExistingFindingDispositionAcrossFocusedEpoch() throws Exception {
    try (DesktopResearchCheckpointBlackBoxHarness harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            tempDir.resolve("run"),
            "concurrent-finding-dispositions",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.CONCURRENT_FINDING_DISPOSITIONS,
            "the shared candidate lemma")) {
      String findingId = harness.prepareFocusedRouteWithActiveFinding();
      String rootHash = harness.rootHash();
      String negativeHash = harness.negativeHash();

      assertThatCode(harness::explorePreparedRoutes).doesNotThrowAnyException();

      var finding = harness.researchLedger().finding(findingId);
      long dispositionAudits =
          harness.researchLedger().audit().stream()
              .filter(event -> event.findingId().equals(findingId))
              .filter(event -> !event.action().equals("capture_and_activate"))
              .count();
      assertThat(finding.status()).isEqualTo(ResearchFindingStatus.DEFERRED);
      assertThat(finding.version()).isEqualTo(1L);
      assertThat(dispositionAudits).isEqualTo(1L);
      assertThat(harness.providerCallCount()).isEqualTo(4);
      assertThat(harness.researchLedger().findings()).hasSize(5);
      assertThat(harness.researchLedger().snapshot().checkpoints()).hasSize(5);
      assertThat(harness.rootHash()).isEqualTo(rootHash);
      assertThat(harness.negativeHash()).isEqualTo(negativeHash);

      System.out.println("CONCURRENT RESEARCH FINDING MERGE DIAGNOSTIC");
      System.out.println("FOCUSED_WORKERS=" + harness.providerCallCount());
      System.out.println("CONFLICTING_SECONDARY_DISPOSITIONS=2");
      System.out.println("PRIMARY_FINDING_STATUS=" + finding.status());
      System.out.println("PRIMARY_FINDING_VERSION=" + finding.version());
      System.out.println("COMMITTED_DISPOSITION_AUDITS=" + dispositionAudits);
      System.out.println("WORKER_APPEND_ONLY_FINDINGS_PRESERVED=4");
      System.out.println("WORKER_APPEND_ONLY_CHECKPOINTS_PRESERVED=4");
      System.out.println("ROOT_HASH_CHANGES=0");
      System.out.println("NEGATIVE_REGISTRY_HASH_CHANGES=0");
      System.out.println("RESULT=PASS");
    }
  }
}
