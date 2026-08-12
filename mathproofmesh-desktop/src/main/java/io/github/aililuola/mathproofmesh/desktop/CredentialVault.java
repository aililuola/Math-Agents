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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** In-memory credentials with optional current-user DPAPI persistence on Windows. */
public final class CredentialVault {
  private static final Logger LOGGER = LoggerFactory.getLogger(CredentialVault.class);
  private static final Set<String> ALLOWED_KEYS =
      Set.of(
          "DEEPSEEK_AGENT_1_KEY",
          "DEEPSEEK_AGENT_2_KEY",
          "DEEPSEEK_AGENT_3_KEY",
          "DEEPSEEK_AGENT_4_KEY",
          "DEEPSEEK_AGENT_5_KEY");

  private final Path path;
  private final Path directory;
  private final ObjectMapper mapper;
  private final SecretProtector protector;
  private final Map<String, String> session = new ConcurrentHashMap<>();

  public CredentialVault(Path path, ObjectMapper mapper) {
    this(
        path,
        mapper,
        WindowsDpapiProtector.isWindows() ? new WindowsDpapiProtector() : null);
  }

  public CredentialVault(Path path, ObjectMapper mapper, SecretProtector protector) {
    this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    this.directory =
        Objects.requireNonNull(this.path.getParent(), "credential vault parent directory");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.protector = protector;
  }

  public synchronized void set(String name, String value, boolean persist) {
    String safeName = validateName(name);
    String normalized = Objects.requireNonNull(value, "value").trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("API key must not be blank");
    }
    session.put(safeName, normalized);
    if (!persist) {
      return;
    }
    if (protector == null) {
      throw new IllegalStateException(
          "persistent credential protection is unavailable; use an environment secret provider");
    }
    Map<String, String> items = loadItems();
    byte[] plaintext = normalized.getBytes(StandardCharsets.UTF_8);
    try {
      items.put(safeName, Base64.getEncoder().encodeToString(protector.protect(plaintext)));
    } finally {
      java.util.Arrays.fill(plaintext, (byte) 0);
    }
    writeItems(items);
  }

  public synchronized String get(String name) {
    String safeName = validateName(name);
    String current = session.get(safeName);
    if (current != null) {
      return current;
    }
    String encoded = loadItems().get(safeName);
    if (encoded != null && protector != null) {
      try {
        byte[] encrypted = Base64.getDecoder().decode(encoded);
        byte[] plaintext = protector.unprotect(encrypted);
        try {
          String resolved = new String(plaintext, StandardCharsets.UTF_8);
          session.put(safeName, resolved);
          return resolved;
        } finally {
          java.util.Arrays.fill(plaintext, (byte) 0);
        }
      } catch (RuntimeException exception) {
        LOGGER.warn("Stored desktop credential could not be decrypted");
      }
    }
    String environment = System.getenv(safeName);
    return environment == null || environment.isBlank() ? null : environment;
  }

  public synchronized void clear(String name) {
    String safeName = validateName(name);
    session.remove(safeName);
    Map<String, String> items = loadItems();
    if (items.remove(safeName) != null) {
      writeItems(items);
    }
  }

  public synchronized void clearAll() {
    session.clear();
    try {
      Files.deleteIfExists(path);
    } catch (IOException exception) {
      throw new IllegalStateException("credential vault could not be removed", exception);
    }
  }

  public synchronized Map<String, String> statuses() {
    Map<String, String> persisted = loadItems();
    List<String> names = new ArrayList<>(ALLOWED_KEYS);
    Collections.sort(names);
    Map<String, String> result = new LinkedHashMap<>();
    for (String name : names) {
      if (session.containsKey(name)) {
        result.put(name, "session");
      } else if (persisted.containsKey(name)) {
        result.put(name, "saved");
      } else if (System.getenv(name) != null) {
        result.put(name, "environment");
      } else {
        result.put(name, "missing");
      }
    }
    return Collections.unmodifiableMap(result);
  }

  public String protectionId() {
    return protector == null ? "environment-only" : protector.protectionId();
  }

  private Map<String, String> loadItems() {
    if (!Files.isRegularFile(path)) {
      return new LinkedHashMap<>();
    }
    try {
      JsonNode root = mapper.readTree(Files.readAllBytes(path));
      if (!root.isObject() || root.path("version").asInt() != 1) {
        return new LinkedHashMap<>();
      }
      if (protector != null
          && (!protector.protectionId().equals(root.path("protection").asText())
              || !protector.entropyId().equals(root.path("entropy").asText()))) {
        LOGGER.warn("Stored desktop credential metadata does not match this protector");
        return new LinkedHashMap<>();
      }
      JsonNode items = root.path("items");
      Map<String, String> result = new LinkedHashMap<>();
      if (items.isObject()) {
        for (Map.Entry<String, JsonNode> entry : items.properties()) {
          if (ALLOWED_KEYS.contains(entry.getKey()) && entry.getValue().isTextual()) {
            result.put(entry.getKey(), entry.getValue().asText());
          }
        }
      }
      return result;
    } catch (IOException exception) {
      LOGGER.warn("Unreadable desktop credential vault was ignored");
      return new LinkedHashMap<>();
    }
  }

  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_IN",
      justification =
          "The normalized vault path is a fixed child of DesktopPaths.config, not an HTTP value.")
  private void writeItems(Map<String, String> items) {
    if (protector == null) {
      throw new IllegalStateException("persistent credential protection is unavailable");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("version", 1);
    payload.put("protection", protector.protectionId());
    payload.put("entropy", protector.entropyId());
    payload.put("items", new LinkedHashMap<>(items));
    try {
      Files.createDirectories(directory);
      Path temporary = Files.createTempFile(directory, "." + path.getFileName(), ".tmp");
      try {
        Files.write(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload));
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
    } catch (IOException exception) {
      throw new IllegalStateException("credential vault could not be saved", exception);
    }
  }

  private static String validateName(String name) {
    String safe = Objects.requireNonNull(name, "name");
    if (!ALLOWED_KEYS.contains(safe)) {
      throw new IllegalArgumentException("unsupported credential name");
    }
    return safe;
  }
}
