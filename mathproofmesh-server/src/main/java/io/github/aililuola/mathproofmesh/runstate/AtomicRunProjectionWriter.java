package io.github.aililuola.mathproofmesh.runstate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;

final class AtomicRunProjectionWriter {
  private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();

  private AtomicRunProjectionWriter() {}

  static String write(Path target, Map<String, Object> payload) {
    try {
      Path parent = Objects.requireNonNull(target.getParent(), "projection parent");
      Files.createDirectories(parent);
      byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
      Path temporary = Files.createTempFile(parent, ".run-projection-", ".tmp");
      try {
        Files.write(temporary, bytes);
        try {
          Files.move(
              temporary,
              target,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
          Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
      return CanonicalJson.stableHash(payload);
    } catch (IOException exception) {
      throw new IllegalStateException("run projection could not be written", exception);
    }
  }
}
