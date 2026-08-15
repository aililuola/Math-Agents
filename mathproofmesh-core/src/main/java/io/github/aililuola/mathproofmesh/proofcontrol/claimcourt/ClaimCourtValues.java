package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

final class ClaimCourtValues {
  private ClaimCourtValues() {}

  static String required(String value, String name) {
    String normalized = value == null ? "" : normalize(value).strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return normalized;
  }

  static String nullable(String value) {
    if (value == null) {
      return null;
    }
    String normalized = normalize(value).strip();
    return normalized.isEmpty() ? null : normalized;
  }

  static String normalizedSemanticText(String value) {
    return required(value, "semantic text")
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", " ")
        .strip();
  }

  static <T> List<T> copy(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  private static String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC);
  }
}
