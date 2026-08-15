package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Bounded operation vocabulary for auditable strategy mechanism declarations. */
public enum MechanismOperationKind {
  DIRECT("direct"),
  INDUCTION("induction"),
  CONTRADICTION("contradiction"),
  EXTREMAL_SELECTION("extremal_selection"),
  MINIMAL_COUNTEREXAMPLE("minimal_counterexample"),
  DECOMPOSITION("decomposition"),
  CONSTRUCTION("construction"),
  DUALIZATION("dualization"),
  REDUCTION("reduction"),
  ALGEBRAIC_TRANSFORMATION("algebraic_transformation"),
  COUNTING("counting"),
  SPECTRAL_ARGUMENT("spectral_argument"),
  PROBABILISTIC_ARGUMENT("probabilistic_argument"),
  UNKNOWN("unknown");

  private final String value;

  MechanismOperationKind(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static MechanismOperationKind fromValue(String value) {
    for (MechanismOperationKind candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown MechanismOperationKind value: " + value);
  }
}
