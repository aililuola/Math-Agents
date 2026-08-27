package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopV20ResearchAuthorityMutationMigrationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void missingAuthorityReceiptLedgerDefaultsEmptyAndUpgradesWithoutModelCalls()
      throws Exception {
    Path runDirectory = temporaryDirectory.resolve("v20-run");
    DesktopSolveCheckpoint current;
    try (var harness = DesktopComputationIssue010CoordinatorHarness.open(runDirectory, "v20-run")) {
      current = harness.checkpointRoundTrip();
    }
    ObjectNode json =
        (ObjectNode) ContractObjectMapper.parseTree(ContractObjectMapper.write(current));
    json.put("schemaVersion", 20);
    json.remove("researchAuthorityMutations");
    DesktopSolveCheckpoint version20 =
        ContractObjectMapper.read(json.toString(), DesktopSolveCheckpoint.class);

    assertThat(version20.researchAuthorityMutations().authorityMutations()).isEmpty();
    assertThat(version20.researchAuthorityMutations().mergeReceipts()).isEmpty();

    try (var restored = DesktopComputationIssue010CoordinatorHarness.open(runDirectory, "v20-run")) {
      restored.restore(version20);
      DesktopSolveCheckpoint upgraded = restored.checkpointRoundTrip();
      assertThat(upgraded.schemaVersion())
          .isEqualTo(DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION);
      assertThat(upgraded.researchAuthorityMutations()).isNotNull();
    }
  }
}
