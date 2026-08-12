package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.config.SystemConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DesktopRuntimeLocatorTest {
  @Test
  void developmentRuntimeLoadsLockedProfileSidecarAndPython() {
    Path root = projectRoot();
    DesktopRuntimeLocator locator = new DesktopRuntimeLocator(root, null);

    SystemConfig config = locator.loadProfile("proof-control-active.yaml");

    assertEquals(5, config.agents().size());
    assertTrue(
        config.agents().stream()
            .allMatch(
                agent ->
                    "deepseek-v4-pro".equals(agent.model())
                        && "max".equals(agent.reasoningEffort())
                        && agent.thinkingEnabled()));
    assertTrue(Files.isRegularFile(locator.sidecarService()));
    assertTrue(Files.isRegularFile(locator.sandboxValidator()));
    assertTrue(Files.isRegularFile(locator.pythonExecutable()));
    assertThrows(
        IllegalArgumentException.class,
        () -> locator.loadProfile("untrusted-profile.yaml"));
  }

  private static Path projectRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("config/proof-control-active.yaml"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("project root was not found");
  }
}
