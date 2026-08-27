package io.github.aililuola.mathproofmesh.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.Objects;

public record ProviderCallTransition(
    String runId,
    String callId,
    ProviderCallState expected,
    ProviderCallState target,
    long inputTokens,
    long outputTokens,
    BigDecimal costUsd,
    double latencyMs,
    String responseArtifactHash,
    String requestId,
    int retryCount,
    BigDecimal possibleDuplicateCostUsd,
    JsonNode ambiguityPayload) {

  public ProviderCallTransition {
    runId = requireText(runId, "runId");
    callId = requireText(callId, "callId");
    expected = Objects.requireNonNull(expected, "expected");
    target = Objects.requireNonNull(target, "target");
    if (!expected.canTransitionTo(target)) {
      throw new IllegalArgumentException(
          "illegal provider call transition " + expected + " -> " + target);
    }
    if (inputTokens < 0 || outputTokens < 0 || retryCount < 0) {
      throw new IllegalArgumentException("provider call counters must not be negative");
    }
    costUsd = nonNegative(costUsd, "costUsd");
    possibleDuplicateCostUsd =
        nonNegative(possibleDuplicateCostUsd, "possibleDuplicateCostUsd");
    if (!Double.isFinite(latencyMs) || latencyMs < 0.0d) {
      throw new IllegalArgumentException("latencyMs must be finite and non-negative");
    }
    responseArtifactHash = normalize(responseArtifactHash);
    requestId = normalize(requestId);
    ambiguityPayload =
        ambiguityPayload == null
            ? JsonNodeFactory.instance.objectNode()
            : ambiguityPayload.deepCopy();
  }

  @Override
  public JsonNode ambiguityPayload() {
    return ambiguityPayload.deepCopy();
  }

  public static ProviderCallTransition state(
      String runId,
      String callId,
      ProviderCallState expected,
      ProviderCallState target) {
    return new ProviderCallTransition(
        runId,
        callId,
        expected,
        target,
        0L,
        0L,
        BigDecimal.ZERO,
        0.0d,
        null,
        null,
        0,
        BigDecimal.ZERO,
        null);
  }

  private static String requireText(String value, String label) {
    Objects.requireNonNull(value, label);
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.strip();
    return normalized.isEmpty() ? null : normalized;
  }

  private static BigDecimal nonNegative(BigDecimal value, String label) {
    Objects.requireNonNull(value, label);
    if (value.signum() < 0) {
      throw new IllegalArgumentException(label + " must not be negative");
    }
    return value;
  }
}
