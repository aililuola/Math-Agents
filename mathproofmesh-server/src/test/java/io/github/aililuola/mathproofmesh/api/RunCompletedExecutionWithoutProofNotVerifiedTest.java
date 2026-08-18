package io.github.aililuola.mathproofmesh.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.runstate.RunExecutionStatus;
import io.github.aililuola.mathproofmesh.runstate.RunMathematicalStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunCompletedExecutionWithoutProofNotVerifiedTest {
  @TempDir Path temporaryDirectory;

  @Test
  void completedExecutionDoesNotManufactureMathematicalAuthority() {
    RunExecutionBackend backend =
        (request, runId, traceId, runDirectory, progress) ->
            new RunExecutionBackend.RunExecutionResult(
                "completed", "report", "execution completed", List.of(), List.of(), "", 1);
    RunApiService service =
        new RunApiService(
            new ApiObservability(new SimpleMeterRegistry()),
            temporaryDirectory.toString(),
            1,
            backend);

    RunApiModels.RunView view =
        service.solve(new SolveRequest("Prove P.", "completed-no-proof", null, "smoke"));

    assertThat(view.executionStatus()).isEqualTo(RunExecutionStatus.SUCCEEDED);
    assertThat(view.mathStatus()).isNotEqualTo(RunMathematicalStatus.VERIFIED);
    assertThat(view.status()).isEqualTo("unverified");
  }
}
