package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationExecutionState;
import io.github.aililuola.mathproofmesh.computation.ComputationResultArtifact;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopComputationArtifactRoundTripTest {
  @TempDir Path temporaryDirectory;

  @Test
  void contentAddressedArtifactsSurviveStateSerializationAndASecondStoreInstance() {
    String runId = "artifact-round-trip";
    var first = DesktopComputationIssue010Support.broker(runId, temporaryDirectory, new InMemoryComputationCache());
    var outcome = DesktopComputationIssue010Support.run(first, DesktopComputationIssue010Support.linearAlgebra("linear", 3), "linear", 0);
    String json = ContractObjectMapper.write(first.executionService().snapshot());
    var state = ContractObjectMapper.read(json, ComputationExecutionState.class);
    var second = DesktopComputationIssue010Support.broker(runId, temporaryDirectory, new InMemoryComputationCache());
    second.executionService().restore(state);
    var restored = second.executionService().artifacts()
        .read(outcome.artifacts().result().reference(), ComputationResultArtifact.class)
        .orElseThrow();
    var original = first.executionService().artifacts()
        .read(outcome.artifacts().result().reference(), ComputationResultArtifact.class)
        .orElseThrow();
    assertThat(restored.artifactHash()).isEqualTo(original.artifactHash());
    assertThat(restored.artifactHash()).hasSize(64);
    assertThat(second.executionService().snapshot().artifacts().stableHash())
        .isEqualTo(state.artifacts().stableHash());
  }
}
