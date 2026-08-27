package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopV11SemanticPivotMigrationTest {
  @Test
  void versionElevenRestoresWithEmptyPivotStateAndNeverInfersLegacyRevisions(
      @TempDir Path directory) throws Exception {
    String runId = "semantic-pivot-v11-migration";
    var harness = DesktopSemanticPivotTestHarness.open(directory, runId);
    assertThat(harness.localRepair()).isTrue();
    int legacyRevisions = harness.revisionHistory().size();
    var before = harness.state();
    DesktopSolveCheckpoint current = harness.checkpoint();
    harness.close();

    ObjectNode json = (ObjectNode) ContractObjectMapper.toTree(current);
    json.put("schemaVersion", 11);
    json.remove("semanticPivots");
    ArrayNode routes = (ArrayNode) json.path("routes");
    routes.forEach(
        value -> {
          ObjectNode route = (ObjectNode) value;
          route.remove("activeSemanticPivotId");
          route.remove("semanticPivotIds");
          route.remove("activeStrategyEpochId");
          route.remove("retiredActiveClaimIds");
          route.remove("retiredStrategyFocusObligationIds");
          route.remove("activeMathematicalObjectIds");
          route.remove("activeDirectionSignature");
        });
    DesktopSolveCheckpoint legacy = ContractObjectMapper.read(json, DesktopSolveCheckpoint.class);

    try (DesktopSemanticPivotTestHarness restored =
        DesktopSemanticPivotTestHarness.restore(directory, runId, legacy)) {
      assertThat(restored.semanticPivots().ledger().records()).isEmpty();
      assertThat(restored.revisionHistory()).hasSize(legacyRevisions);
      assertThat(restored.state().activeStrategyId()).isEqualTo(before.activeStrategyId());
      assertThat(restored.state().rootHash()).isEqualTo(before.rootHash());
      assertThat(restored.state().negativeHash()).isEqualTo(before.negativeHash());
      DesktopSolveCheckpoint upgraded = restored.checkpoint();
      assertThat(upgraded.schemaVersion()).isEqualTo(DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION);
      assertThat(upgraded.semanticPivots().records()).isEmpty();
    }
  }
}
