package io.github.aililuola.mathproofmesh.config;

import java.util.List;
import java.util.Objects;

public record ConfigFieldConstraint(
    Class<? extends ConfigModel> recordType,
    String field,
    Kind kind,
    List<?> values
) {
  public ConfigFieldConstraint {
    Objects.requireNonNull(recordType, "recordType");
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(kind, "kind");
    values = List.copyOf(values);
  }

  public enum Kind {
    MINIMUM,
    EXCLUSIVE_MINIMUM,
    MAXIMUM,
    MINIMUM_LENGTH,
    MAXIMUM_LENGTH,
    ONE_OF,
    ITEMS_ONE_OF,
    MAP_VALUES_ONE_OF
  }
}
