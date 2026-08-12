package io.github.aililuola.mathproofmesh.compatibility;

import java.util.Objects;
import java.util.regex.Pattern;

public record LegacyFileEntry(String relativePath, long sizeBytes, String sha256) {
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  public LegacyFileEntry {
    relativePath = Objects.requireNonNull(relativePath, "relativePath");
    sha256 = Objects.requireNonNull(sha256, "sha256");
    if (relativePath.isBlank()
        || relativePath.startsWith("/")
        || relativePath.startsWith("\\")
        || relativePath.contains("../")
        || relativePath.contains("..\\")) {
      throw new IllegalArgumentException("relativePath must be a confined POSIX path");
    }
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("sizeBytes cannot be negative");
    }
    if (!SHA256.matcher(sha256).matches()) {
      throw new IllegalArgumentException("sha256 must be lowercase hexadecimal");
    }
  }
}
