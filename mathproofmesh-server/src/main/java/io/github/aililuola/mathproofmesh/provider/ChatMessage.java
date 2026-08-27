package io.github.aililuola.mathproofmesh.provider;

import java.util.Objects;

public record ChatMessage(String role, String content) {
  public ChatMessage {
    role = requireText(role, "role");
    content = Objects.requireNonNull(content, "content");
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
