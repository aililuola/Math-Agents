package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Strict, atomic storage for non-secret desktop settings. */
public final class SettingsStore {
  private final Path path;
  private final Path directory;
  private final ObjectMapper mapper;

  public SettingsStore(Path path, ObjectMapper mapper) {
    this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    this.directory =
        Objects.requireNonNull(this.path.getParent(), "desktop settings parent directory");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  public DesktopSettings load() {
    if (!Files.isRegularFile(path)) {
      return DesktopSettings.defaults();
    }
    try {
      JsonNode root = mapper.readTree(Files.readAllBytes(path));
      if (!root.isObject()) {
        return DesktopSettings.defaults();
      }
      return new DesktopSettings(
          root.path("selected_profile").asText("smoke"),
          root.path("sandbox_enabled").asBoolean(true),
          root.path("remember_credentials").asBoolean(true));
    } catch (IOException | IllegalArgumentException exception) {
      return DesktopSettings.defaults();
    }
  }

  public void save(DesktopSettings settings) {
    Objects.requireNonNull(settings, "settings");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("selected_profile", settings.selectedProfile());
    payload.put("sandbox_enabled", settings.sandboxEnabled());
    payload.put("remember_credentials", settings.rememberCredentials());
    try {
      writeAtomically(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload));
    } catch (IOException exception) {
      throw new IllegalStateException("desktop settings could not be saved", exception);
    }
  }

  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_IN",
      justification =
          "The normalized settings path is a fixed child of DesktopPaths.config, not an HTTP value.")
  private void writeAtomically(byte[] bytes) throws IOException {
    Files.createDirectories(directory);
    Path temporary = Files.createTempFile(directory, "." + path.getFileName(), ".tmp");
    try {
      Files.write(temporary, bytes);
      try {
        Files.move(
            temporary,
            path,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public String redactedSnapshot() {
    DesktopSettings settings = load();
    return new String(
        ("{\"selected_profile\":\""
                + settings.selectedProfile()
                + "\",\"sandbox_enabled\":"
                + settings.sandboxEnabled()
                + ",\"remember_credentials\":"
                + settings.rememberCredentials()
                + "}")
            .getBytes(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8);
  }
}
