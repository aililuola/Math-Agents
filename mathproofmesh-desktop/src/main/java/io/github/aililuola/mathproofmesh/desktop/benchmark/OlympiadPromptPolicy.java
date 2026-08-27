package io.github.aililuola.mathproofmesh.desktop.benchmark;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Fail-closed prompt policy that keeps evaluation and solution metadata out of provider calls. */
public final class OlympiadPromptPolicy {
  private static final List<String> FORBIDDEN_FRAGMENTS =
      List.of(
          "evaluation_only",
          "运行结束后必须提取",
          "元数据（不得入模）",
          "benchmark curated classic",
          "benchmark-authored",
          "imo 1988 problem 6",
          "imo 2001 problem 2",
          "imo 2024 problem",
          "imo 2025 problem",
          "user-supplied legacy regression problem",
          "historical failure",
          "official solution",
          "external score");

  private OlympiadPromptPolicy() {}

  public static void validateCanonicalProblem(String problem) {
    String normalized = require(problem, "problem");
    rejectForbidden(normalized);
    if (normalized.length() > 20_000) {
      throw new IllegalArgumentException("canonical problem prompt is unexpectedly large");
    }
  }

  public static void validateNoForbiddenMetadata(String payload) {
    rejectForbidden(require(payload, "payload"));
  }

  public static void validateProviderPayload(
      String payload, OlympiadProblemCatalog.ProblemPrompt expected, boolean firstRequest) {
    String normalized = require(payload, "payload");
    Objects.requireNonNull(expected, "expected");
    rejectForbidden(normalized);
    if (!normalized.contains(expected.text())) {
      throw new IllegalStateException("provider payload does not contain the canonical problem");
    }
    if (firstRequest) {
      String hash = OlympiadProblemCatalog.sha256(expected.text());
      if (!MessageDigestSupport.sameAscii(hash, expected.sha256())) {
        throw new IllegalStateException("first provider request has a problem hash mismatch");
      }
    }
  }

  private static void rejectForbidden(String value) {
    String folded = value.toLowerCase(Locale.ROOT);
    for (String fragment : FORBIDDEN_FRAGMENTS) {
      if (folded.contains(fragment)) {
        throw new IllegalArgumentException("provider prompt contains forbidden benchmark metadata");
      }
    }
  }

  private static String require(String value, String field) {
    String normalized = Objects.requireNonNull(value, field).strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return normalized;
  }

  private static final class MessageDigestSupport {
    private MessageDigestSupport() {}

    private static boolean sameAscii(String left, String right) {
      return java.security.MessageDigest.isEqual(
          left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
  }
}
