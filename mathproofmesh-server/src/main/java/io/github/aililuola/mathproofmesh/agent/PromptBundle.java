package io.github.aililuola.mathproofmesh.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record PromptBundle<T>(
    String stage,
    String system,
    String user,
    Class<T> responseType,
    double temperature,
    int maxOutputTokens,
    boolean streaming,
    JsonNode responseSchema) {

  public PromptBundle {
    stage = requireText(stage, "stage");
    system = requireText(system, "system");
    user = requireText(user, "user");
    responseType = Objects.requireNonNull(responseType, "responseType");
    if (!Double.isFinite(temperature) || temperature < 0.0d || temperature > 2.0d) {
      throw new IllegalArgumentException("temperature must be between 0 and 2");
    }
    if (maxOutputTokens < 1) {
      throw new IllegalArgumentException("maxOutputTokens must be positive");
    }
    responseSchema = responseSchema == null ? null : responseSchema.deepCopy();
  }

  @Override
  public JsonNode responseSchema() {
    return responseSchema == null ? null : responseSchema.deepCopy();
  }

  private static String requireText(String value, String label) {
    Objects.requireNonNull(value, label);
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }
}
