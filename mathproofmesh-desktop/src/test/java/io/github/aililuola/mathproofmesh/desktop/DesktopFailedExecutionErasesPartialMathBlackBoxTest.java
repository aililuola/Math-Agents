package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopFailedExecutionErasesPartialMathBlackBoxTest {
  @TempDir Path temporaryDirectory;

  @Test
  void checkpointProgressSurvivesExecutionFailure() throws Exception {
    var fixture = RunStateBlackBoxFixtures.legacyRun(temporaryDirectory, "math-failure", 2, 10, 10);
    assertThat(fixture.repository().summary("math-failure"))
        .containsEntry("math_status", "PARTIAL_UNVERIFIED");
  }
}
