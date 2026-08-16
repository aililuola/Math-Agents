package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationBackendKind;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopNativeComputationProductionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void desktopArtifactPipelineRunsNativeLinearSetAndHypergraphCapabilities() {
    var broker = DesktopComputationIssue010Support.broker("desktop-native", temporaryDirectory, new InMemoryComputationCache());
    DesktopComputationIssue010Support.run(broker, DesktopComputationIssue010Support.linearAlgebra("linear", 1), "linear", 0);
    DesktopComputationIssue010Support.run(broker, DesktopComputationIssue010Support.finiteMap("map"), "map", 0);
    DesktopComputationIssue010Support.run(broker, DesktopComputationIssue010Support.hypergraph("hyper"), "hyper", 0);
    assertThat(broker.executionService().executions().records()).hasSize(3);
    assertThat(broker.executionService().executions().records())
        .allMatch(record -> record.backend() == ComputationBackendKind.NATIVE_JAVA);
    assertThat(broker.executionService().verifications().snapshot().receipts()).hasSize(3);
  }
}
