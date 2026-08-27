package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopRunStateLegacyMigrationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void missingCanonicalStateMigratesOnceWithoutProviderAccess() throws Exception {
    var fixture = RunStateBlackBoxFixtures.legacyRun(temporaryDirectory, "legacy-migration", 7, 10, 20);
    var first = fixture.repository().summary("legacy-migration");
    var second = fixture.repository().summary("legacy-migration");
    assertThat(first.get("authority_state_hash")).isEqualTo(second.get("authority_state_hash"));
    assertThat(fixture.runDirectory().resolve("structured/run_state.json")).isRegularFile();
  }
}
