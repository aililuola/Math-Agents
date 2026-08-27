package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CliDesktopResumeParityTest {
  @TempDir Path temporaryDirectory;

  @Test
  void test_cli_resume_can_reuse_desktop_paths_and_credentials() {
    DesktopPaths paths = DesktopTestSupport.paths(temporaryDirectory.resolve("desktop"));
    DesktopSettings settings = new DesktopSettings("proof_control_active", false, true);
    new SettingsStore(paths.settingsFile(), DesktopTestSupport.MAPPER).save(settings);
    CredentialVault vault = DesktopTestSupport.vault(paths);
    IntStream.rangeClosed(1, 5)
        .forEach(
            index ->
                vault.set(
                    "DEEPSEEK_AGENT_" + index + "_KEY", "secret-" + index, false));

    DesktopConfigService.PreparedDesktopConfig prepared =
        new DesktopConfigService(paths, vault).build(settings.selectedProfile(), settings);

    assertEquals(paths.root(), prepared.projectRoot());
    assertEquals(paths.runs(), prepared.runRoot());
    assertEquals(paths.learning(), prepared.learningRoot());
    assertFalse(prepared.sandboxEnabled());
    assertEquals(5, prepared.injectedCredentials().size());
  }
}
