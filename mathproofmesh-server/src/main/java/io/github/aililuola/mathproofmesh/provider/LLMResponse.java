package io.github.aililuola.mathproofmesh.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Objects;

public record LLMResponse(
    String text,
    String model,
    String provider,
    long inputTokens,
    long outputTokens,
    double latencyMs,
    String requestId,
    String finishReason,
    boolean streaming,
    JsonNode metadata) {

  public LLMResponse {
    text = Objects.requireNonNull(text, "text");
    model = requireText(model, "model");
    provider = requireText(provider, "provider");
    if (inputTokens < 0 || outputTokens < 0) {
      throw new IllegalArgumentException("token counts must not be negative");
    }
    if (!Double.isFinite(latencyMs) || latencyMs < 0.0d) {
      throw new IllegalArgumentException("latencyMs must be finite and non-negative");
    }
    requestId = normalize(requestId);
    finishReason = normalize(finishReason);
    metadata =
        metadata == null
            ? JsonNodeFactory.instance.objectNode()
            : metadata.deepCopy();
  }

  @Override
  public JsonNode metadata() {
    return metadata.deepCopy();
  }

  public long totalTokens() {
    return Math.addExact(inputTokens, outputTokens);
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
}
