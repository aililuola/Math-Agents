package io.github.aililuola.mathproofmesh.communication.artifact;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class BrokerArtifactValues {
  private BrokerArtifactValues() {}

  static String required(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.strip();
  }

  static String nullable(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  static <T> List<T> list(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  static <T> Set<T> set(Set<T> values) {
    return values == null ? Set.of() : Set.copyOf(values);
  }

  static <K, V> Map<K, V> map(Map<K, V> values) {
    return values == null ? Map.of() : Map.copyOf(values);
  }
}
