package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopFailedExecutionZerosDurableUsageBlackBoxTest {
  @TempDir Path temporaryDirectory;

  @Test
  void durableCheckpointUsageSurvivesAFailedResultProjection() throws Exception {
    var fixture = RunStateBlackBoxFixtures.legacyRun(temporaryDirectory, "usage-failure", 7, 70, 30);
    assertThat(fixture.repository().summary("usage-failure"))
        .containsEntry("total_calls", 7L)
        .containsEntry("total_tokens", 100L);
  }
}
