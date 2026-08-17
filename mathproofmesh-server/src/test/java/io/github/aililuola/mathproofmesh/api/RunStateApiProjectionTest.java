package io.github.aililuola.mathproofmesh.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunStateApiProjectionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void apiViewProjectsAllFiveCanonicalDimensions() {
    RunApiService service = service(temporaryDirectory);
    RunApiModels.RunView view =
        service.solve(new SolveRequest("Prove P.", "api-projection", null, "smoke"));
    assertThat(view.authorityStateHash()).hasSize(64);
    assertThat(view.executionStatus()).isNotNull();
    assertThat(view.mathStatus()).isNotNull();
    assertThat(view.usageStatus()).isNotNull();
    assertThat(view.campaignStatus()).isNotNull();
    assertThat(view.reportStatus()).isNotNull();
  }

  static RunApiService service(Path root) {
    return new RunApiService(new ApiObservability(new SimpleMeterRegistry()), root.toString(), 1);
  }
}
