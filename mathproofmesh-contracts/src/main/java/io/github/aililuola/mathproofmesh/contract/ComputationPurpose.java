package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ComputationPurpose {
  FALSIFY_CLAIM("falsify_claim"),
  CHECK_DERIVED_IDENTITY("check_derived_identity"),
  TEST_BOUNDARY_CASES("test_boundary_cases"),
  VERIFY_FINITE_REDUCTION("verify_finite_reduction"),
  VALIDATE_CONSTRUCTED_EXAMPLE("validate_constructed_example"),
  DISCOVER_PATTERN("discover_pattern");

  private final String value;

  ComputationPurpose(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static ComputationPurpose fromValue(String value) {
    for (ComputationPurpose candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown ComputationPurpose value: " + value);
  }
}
