package io.github.aililuola.mathproofmesh.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public record ProviderRequest(
    List<ChatMessage> messages,
    double temperature,
    int maxOutputTokens,
    boolean jsonMode,
    String schemaName,
    JsonNode schema,
    Boolean thinkingEnabled,
    String reasoningEffort,
    boolean streaming,
    String userId,
    BooleanSupplier cancelled) {

  public ProviderRequest {
    messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    if (messages.isEmpty()) {
      throw new IllegalArgumentException("messages must not be empty");
    }
    if (!Double.isFinite(temperature) || temperature < 0.0d || temperature > 2.0d) {
      throw new IllegalArgumentException("temperature must be between 0 and 2");
    }
    if (maxOutputTokens < 1) {
      throw new IllegalArgumentException("maxOutputTokens must be positive");
    }
    schemaName = normalize(schemaName);
    schema = schema == null ? null : schema.deepCopy();
    reasoningEffort = normalize(reasoningEffort);
    userId = normalize(userId);
    cancelled = cancelled == null ? () -> false : cancelled;
  }

  @Override
  public List<ChatMessage> messages() {
    return List.copyOf(messages);
  }

  @Override
  public JsonNode schema() {
    return schema == null ? null : schema.deepCopy();
  }

  public static ProviderRequest json(
      List<ChatMessage> messages, int maxOutputTokens, boolean streaming) {
    return new ProviderRequest(
        messages,
        0.0d,
        maxOutputTokens,
        true,
        null,
        null,
        null,
        null,
        streaming,
        null,
        null);
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.strip();
    return normalized.isEmpty() ? null : normalized;
  }
}
