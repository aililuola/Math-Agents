package io.github.aililuola.mathproofmesh.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Canonical binding for a retry that continues an interrupted public output stream. */
public final class PublicOutputContinuation {
  private static final String HEADER =
      "The previous stream disconnected. Continue from the exact public prefix below without "
          + "repeating it.\nPUBLIC_OUTPUT_PREFIX_SHA256: ";
  private static final String PREFIX_MARKER = "\nPUBLIC_OUTPUT_PREFIX:\n";
  private static final String TRAILER = "\nDo not reconstruct hidden chain-of-thought.";

  private PublicOutputContinuation() {}

  public static String create(String prefixHash, String publicPrefix) {
    String hash = requireHash(prefixHash);
    String prefix = requirePrefix(publicPrefix);
    if (!sameHash(hash, sha256(prefix))) {
      throw new IllegalArgumentException("public output prefix hash does not match its content");
    }
    return HEADER + hash + PREFIX_MARKER + prefix + TRAILER;
  }

  public static void requireValid(String value) {
    String candidate = Objects.requireNonNull(value, "value");
    if (!candidate.startsWith(HEADER) || !candidate.endsWith(TRAILER)) {
      throw new IllegalStateException("provider retry has an unbound continuation message");
    }
    int hashStart = HEADER.length();
    int marker = candidate.indexOf(PREFIX_MARKER, hashStart);
    if (marker < 0) {
      throw new IllegalStateException("provider retry has no public prefix binding");
    }
    String hash = requireHash(candidate.substring(hashStart, marker));
    int prefixStart = marker + PREFIX_MARKER.length();
    String prefix =
        requirePrefix(candidate.substring(prefixStart, candidate.length() - TRAILER.length()));
    if (!sameHash(hash, sha256(prefix))) {
      throw new IllegalStateException("provider retry public prefix hash mismatch");
    }
  }

  private static String requireHash(String value) {
    String hash = Objects.requireNonNull(value, "prefixHash");
    if (!hash.matches("[0-9a-f]{64}")) {
      throw new IllegalStateException("provider retry public prefix hash is not SHA-256");
    }
    return hash;
  }

  private static String requirePrefix(String value) {
    String prefix = Objects.requireNonNull(value, "publicPrefix");
    if (prefix.isEmpty()) {
      throw new IllegalStateException("provider retry public prefix must not be empty");
    }
    return prefix;
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the JDK", exception);
    }
  }

  private static boolean sameHash(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }
}
