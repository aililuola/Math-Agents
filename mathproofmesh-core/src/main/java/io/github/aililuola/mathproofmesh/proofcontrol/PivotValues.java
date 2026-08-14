package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.List;

final class PivotValues {
  private PivotValues() {}

  static String required(String value, String name) {
    String normalized = normalize(value);
    if (normalized == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return normalized;
  }

  static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.strip();
    return normalized.isEmpty() ? null : normalized;
  }

  static <T> List<T> copy(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
