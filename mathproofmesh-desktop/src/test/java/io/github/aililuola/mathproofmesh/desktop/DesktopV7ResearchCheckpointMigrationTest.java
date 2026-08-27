package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopV7ResearchCheckpointMigrationTest {
  @Test
  void missingV8ResearchProjectionDefaultsEmptyWithoutTraceReanalysis(@TempDir Path directory)
      throws Exception {
    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "v7-research-migration",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.NORMAL,
            "new v8 finding")) {
      harness.runProductionExploration();
      DesktopSolveCheckpoint current = harness.checkpointRoundTrip();
      ObjectNode json = (ObjectNode) ContractObjectMapper.toTree(current);
      json.put("schemaVersion", 7);
      json.remove("researchCheckpoints");
      json.withArray("routes")
          .forEach(
              route -> {
                ObjectNode object = (ObjectNode) route;
                object.remove("latestResearchCheckpointId");
                object.remove("activeResearchFindingIds");
                object.remove("lastCheckpointedProviderCallId");
                object.remove("checkpointRecoveryCount");
                object.remove("pendingFindingReconciliation");
              });
      DesktopSolveCheckpoint versionSeven =
          ContractObjectMapper.read(
              ContractObjectMapper.write(json), DesktopSolveCheckpoint.class);
      String rootHash = harness.rootHash();
      String negativeHash = harness.negativeHash();

      try (var restored = harness.restored(versionSeven)) {
        assertThat(restored.researchLedger().snapshot())
            .isEqualTo(ResearchCheckpointSnapshot.empty());
        assertThat(restored.rootHash()).isEqualTo(rootHash);
        assertThat(restored.negativeHash()).isEqualTo(negativeHash);
      }
      assertThat(
              Files.readString(
                  directory.resolve("reports/reasoning_traces.txt"), StandardCharsets.UTF_8))
          .contains("new v8 finding");
    }
  }
}
