package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopV19ConcurrencyMigrationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void missingConcurrencyLedgersDefaultEmptyAndNextCheckpointUsesCurrentSchema() throws Exception {
    Path runDirectory = temporaryDirectory.resolve("v19-run");
    DesktopSolveCheckpoint base;
    try (var harness = DesktopComputationIssue010CoordinatorHarness.open(runDirectory, "v19-run")) {
      base = harness.checkpointRoundTrip();
    }
    ObjectNode json = (ObjectNode) ContractObjectMapper.parseTree(ContractObjectMapper.write(base));
    json.put("schemaVersion", 19);
    json.remove("researchEpochs");
    json.remove("researchTasks");
    json.remove("researchResults");
    json.remove("researchAuthorityMutations");
    json.remove("agentLeases");
    json.remove("concurrencyTelemetry");

    DesktopSolveCheckpoint version19 =
        ContractObjectMapper.read(json.toString(), DesktopSolveCheckpoint.class);
    assertThat(version19.researchEpochs().epochs()).isEmpty();
    assertThat(version19.researchTasks().tasks()).isEmpty();
    assertThat(version19.researchResults().artifacts()).isEmpty();
    assertThat(version19.researchAuthorityMutations().authorityMutations()).isEmpty();
    assertThat(version19.agentLeases().leases()).isEmpty();
    assertThat(version19.concurrencyTelemetry().events()).isEmpty();

    try (var restored = DesktopComputationIssue010CoordinatorHarness.open(runDirectory, "v19-run")) {
      restored.restore(version19);
      DesktopSolveCheckpoint current = restored.checkpointRoundTrip();
      assertThat(current.schemaVersion()).isEqualTo(DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION);
      assertThat(current.researchEpochs()).isNotNull();
      assertThat(current.researchTasks()).isNotNull();
      assertThat(current.researchResults()).isNotNull();
      assertThat(current.researchAuthorityMutations()).isNotNull();
      assertThat(current.agentLeases()).isNotNull();
      assertThat(current.concurrencyTelemetry()).isNotNull();
    }
  }
}
