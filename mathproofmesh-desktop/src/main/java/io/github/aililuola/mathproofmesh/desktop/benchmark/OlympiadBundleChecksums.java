package io.github.aililuola.mathproofmesh.desktop.benchmark;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Deterministic SHA-256 manifest writer and verifier for sanitized evidence bundles. */
public final class OlympiadBundleChecksums {
  private static final String CHECKSUM_FILE = "checksums.sha256";

  private OlympiadBundleChecksums() {}

  public static int write(Path root) {
    Path normalized = normalizedDirectory(root);
    List<Entry> entries = entries(normalized);
    StringBuilder content = new StringBuilder();
    entries.forEach(
        entry -> content.append(entry.sha256()).append("  ").append(entry.path()).append('\n'));
    writeAtomically(normalized.resolve(CHECKSUM_FILE), content.toString());
    return entries.size();
  }

  public static Verification verify(Path root) {
    Path normalized = normalizedDirectory(root);
    Path checksum = normalized.resolve(CHECKSUM_FILE);
    if (!Files.isRegularFile(checksum)) {
      return new Verification(0, 1);
    }
    try {
      List<String> lines = Files.readAllLines(checksum, StandardCharsets.UTF_8);
      int failures = 0;
      int checked = 0;
      for (String line : lines) {
        if (line.isBlank()) {
          continue;
        }
        int separator = line.indexOf("  ");
        if (separator != 64) {
          failures++;
          continue;
        }
        String expected = line.substring(0, separator);
        String relative = line.substring(separator + 2);
        Path target = normalized.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(normalized)
            || !Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                sha256(target).getBytes(StandardCharsets.US_ASCII))) {
          failures++;
        }
        checked++;
      }
      return new Verification(checked, failures);
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark checksum verification failed", exception);
    }
  }

  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_IN",
      justification =
          "Only regular files beneath the normalized benchmark bundle root are hashed, without "
              + "following links; the checksum path itself is excluded.")
  private static List<Entry> entries(Path root) {
    List<Entry> entries = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(root)) {
      paths.filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
          .map(Path::toAbsolutePath)
          .map(Path::normalize)
          .filter(path -> path.startsWith(root))
          .filter(path -> !CHECKSUM_FILE.equals(path.getFileName().toString()))
          .sorted()
          .forEach(
              path ->
                  entries.add(
                      new Entry(
                          root.relativize(path).toString().replace('\\', '/'), sha256(path))));
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark bundle could not be enumerated", exception);
    }
    return List.copyOf(entries);
  }

  private static String sha256(Path path) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    } catch (IOException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("benchmark evidence could not be hashed", exception);
    }
  }

  private static Path normalizedDirectory(Path root) {
    Path normalized = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    if (!Files.isDirectory(normalized, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("benchmark bundle root must be a directory");
    }
    return normalized;
  }

  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_OUT",
      justification =
          "The destination is the fixed checksums.sha256 child of a caller-owned normalized "
              + "benchmark bundle directory and is atomically replaced.")
  private static void writeAtomically(Path destination, String content) {
    Path parent = Objects.requireNonNull(destination.getParent(), "checksum parent");
    try {
      Path temporary = Files.createTempFile(parent, ".checksums.", ".tmp");
      try {
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
          Files.move(
              temporary,
              destination,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
          Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark checksum file could not be written", exception);
    }
  }

  private record Entry(String path, String sha256) {}

  public record Verification(int filesChecked, int failures) {
    public Verification {
      if (filesChecked < 0 || failures < 0) {
        throw new IllegalArgumentException("checksum verification counters must not be negative");
      }
    }

    public boolean passed() {
      return failures == 0;
    }
  }
}
