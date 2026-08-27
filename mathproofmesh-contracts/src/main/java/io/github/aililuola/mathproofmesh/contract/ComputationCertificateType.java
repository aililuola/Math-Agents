package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum ComputationCertificateType {
  EXACT_WITNESS,
  FINITE_EXHAUSTIVE_COVERAGE,
  ALGEBRAIC_IDENTITY,
  GRAPH_CERTIFICATE,
  LINEAR_ALGEBRA_CERTIFICATE,
  SET_MAP_CERTIFICATE,
  HYPERGRAPH_TRANSVERSAL_CERTIFICATE,
  FORMAL_KERNEL_CERTIFICATE,
  BOUNDED_OBSERVATION;

  @JsonValue
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  @JsonCreator
  public static ComputationCertificateType fromValue(String value) {
    try {
      return valueOf(value.toUpperCase(Locale.ROOT));
    } catch (RuntimeException exception) {
      throw new ContractValidationException(
          "unknown ComputationCertificateType value: " + value, exception);
    }
  }
}
