package io.github.aililuola.mathproofmesh.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** User-writable desktop paths. Packaged resources are deliberately not resolved here. */
public record DesktopPaths(Path root, Path runs, Path config, Path logs, Path learning) {
  public DesktopPaths {
    root = normalize(root, "root");
    runs = normalize(runs, "runs");
    config = normalize(config, "config");
    logs = normalize(logs, "logs");
    learning = normalize(learning, "learning");
    if (!runs.startsWith(root)
        || !config.startsWith(root)
        || !logs.startsWith(root)
        || !learning.startsWith(root)) {
      throw new IllegalArgumentException("desktop paths must remain below the data root");
    }
  }

  public static DesktopPaths discover() {
    return discover(null);
  }

  public static DesktopPaths discover(Path requestedRoot) {
    Path selected = requestedRoot;
    if (selected == null) {
      String override = System.getenv("MATHPROOFMESH_DESKTOP_HOME");
      if (override != null && !override.isBlank()) {
        selected = Path.of(override);
      } else {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base =
            localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local")
                : Path.of(localAppData);
        selected = base.resolve("MathProofMesh");
      }
    }
    Path root = selected.toAbsolutePath().normalize();
    DesktopPaths paths =
        new DesktopPaths(
            root,
            root.resolve("runs"),
            root.resolve("config"),
            root.resolve("logs"),
            root.resolve("learning"));
    paths.ensure();
    return paths;
  }

  public void ensure() {
    try {
      Files.createDirectories(root);
      Files.createDirectories(runs);
      Files.createDirectories(config);
      Files.createDirectories(logs);
      Files.createDirectories(learning);
    } catch (IOException exception) {
      throw new IllegalStateException("desktop data directories could not be created", exception);
    }
  }

  public Path settingsFile() {
    return config.resolve("desktop-settings.json");
  }

  public Path credentialsFile() {
    return config.resolve("credentials.dpapi.json");
  }

  public Path logFile() {
    return logs.resolve("mathproofmesh-desktop.log");
  }

  public Path safeRunDirectory(String runId) {
    String safe = DesktopApiModel.safeRunId(runId);
    Path candidate = runs.resolve(safe).normalize();
    if (!candidate.startsWith(runs)) {
      throw new IllegalArgumentException("run path escapes the desktop run root");
    }
    return candidate;
  }

  private static Path normalize(Path path, String name) {
    return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
  }
}
