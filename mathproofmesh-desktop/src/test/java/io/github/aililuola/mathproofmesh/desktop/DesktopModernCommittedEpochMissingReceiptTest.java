package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochStatus;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopModernCommittedEpochMissingReceiptTest {
  @TempDir Path temporaryDirectory;

  @Test
  void modernCommittedEpochWithoutReceiptsRemainsQuarantined() throws Exception {
    String runId = "modern-committed-epoch-missing-receipt";
    DesktopSolveCheckpoint committed;
    try (DesktopClaimSalvageTestHarness source =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory.resolve("source"), runId)) {
      source.prepareMixedClaimCourtBatch();
      source.integrateInstalledRound();
      committed = source.readPersistedCheckpoint();
    }

    ObjectNode json =
        (ObjectNode) ContractObjectMapper.parseTree(ContractObjectMapper.write(committed));
    json.remove("researchAuthorityMutations");
    DesktopSolveCheckpoint missingReceipts =
        ContractObjectMapper.read(json.toString(), DesktopSolveCheckpoint.class);
    long modernCommittedWithoutReceipt =
        missingReceipts.researchEpochs().epochs().stream()
            .filter(epoch -> epoch.status() == ResearchEpochStatus.COMMITTED)
            .count();
    long quarantines = 0L;
    try (DesktopClaimSalvageTestHarness restored =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory.resolve("restored"), runId)) {
      assertThatThrownBy(() -> restored.restore(missingReceipts))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("QUARANTINED_PARTIAL_AUTHORITY_COMMIT");
      quarantines++;
    }

    assertThat(modernCommittedWithoutReceipt).isEqualTo(1L);
    assertThat(quarantines).isEqualTo(1L);
    System.out.println("MODERN COMMITTED EPOCH MISSING-RECEIPT DIAGNOSTIC");
    System.out.println(
        "MODERN_COMMITTED_EPOCHS_WITHOUT_RECEIPT=" + modernCommittedWithoutReceipt);
    System.out.println("MODERN_MISSING_RECEIPT_QUARANTINES=" + quarantines);
    System.out.println("RESULT=PASS");
  }
}
