package io.github.aililuola.mathproofmesh.runstate;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

final class RunStateHashes {
  private RunStateHashes() {}

  static String generatedOrVerified(String supplied, Object payload, String label) {
    String expected = CanonicalJson.stableHash(payload);
    if (supplied == null || supplied.isBlank()) {
      return expected;
    }
    String normalized = supplied.strip().toLowerCase(java.util.Locale.ROOT);
    if (!MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        normalized.getBytes(StandardCharsets.US_ASCII))) {
      throw new IllegalArgumentException(label + " hash mismatch");
    }
    return normalized;
  }

  static String required(String value, String label) {
    String normalized = Objects.requireNonNull(value, label).strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }

  static boolean equalHash(String left, String right) {
    return MessageDigest.isEqual(
        Objects.requireNonNull(left, "left").getBytes(StandardCharsets.US_ASCII),
        Objects.requireNonNull(right, "right").getBytes(StandardCharsets.US_ASCII));
  }

  static String optional(String value) {
    return value == null ? "" : value.strip();
  }
}
