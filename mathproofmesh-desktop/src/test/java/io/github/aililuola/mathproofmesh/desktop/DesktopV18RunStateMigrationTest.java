package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopV18RunStateMigrationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void missingV18AnchorDefaultsEmptyAndNextCheckpointUsesCurrentSchema() throws Exception {
    Path runDirectory = temporaryDirectory.resolve("v18-run");
    DesktopSolveCheckpoint base;
    try (var harness = DesktopComputationIssue010CoordinatorHarness.open(runDirectory, "v18-run")) {
      base = harness.checkpointRoundTrip();
    }
    ObjectNode json = (ObjectNode) ContractObjectMapper.parseTree(ContractObjectMapper.write(base));
    json.put("schemaVersion", 18);
    json.remove("runStateAnchor");
    DesktopSolveCheckpoint version18 =
        ContractObjectMapper.read(json.toString(), DesktopSolveCheckpoint.class);
    assertThat(version18.runStateAnchor().authorityHash()).isEmpty();

    try (var restored = DesktopComputationIssue010CoordinatorHarness.open(runDirectory, "v18-run")) {
      restored.restore(version18);
      DesktopSolveCheckpoint current = restored.checkpointRoundTrip();
      assertThat(current.schemaVersion()).isEqualTo(DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION);
      assertThat(current.runStateAnchor()).isNotNull();
    }
  }
}
