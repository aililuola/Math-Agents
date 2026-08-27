package io.github.aililuola.mathproofmesh.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ServerRunStateRestartLossBlackBoxTest {
  @TempDir Path temporaryDirectory;

  @Test
  void statusSurvivesServiceRestartFromTheDurableRunDirectory() {
    Path root = temporaryDirectory.resolve("runs");
    RunApiService first =
        new RunApiService(new ApiObservability(new SimpleMeterRegistry()), root.toString(), 1);
    RunApiModels.RunView completed =
        first.solve(new SolveRequest("Prove the claim.", "restart-run", null, "smoke"));

    RunApiService restarted =
        new RunApiService(new ApiObservability(new SimpleMeterRegistry()), root.toString(), 1);

    assertThat(restarted.status("restart-run").status()).isEqualTo(completed.status());
  }
}
