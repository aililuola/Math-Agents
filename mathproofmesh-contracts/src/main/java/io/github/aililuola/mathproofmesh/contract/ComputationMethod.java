package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ComputationMethod {
  SYMPY_SIMPLIFY("sympy_simplify"),
  SYMPY_EQUIVALENT("sympy_equivalent"),
  POLYNOMIAL_FACTOR("polynomial_factor"),
  MODULAR_EXHAUSTIVE("modular_exhaustive"),
  BOUNDED_INTEGER_SEARCH("bounded_integer_search"),
  GRAPH_CERTIFICATE("graph_certificate"),
  RECURRENCE_CHECK("recurrence_check"),
  BOUNDED_GREEDY_SEQUENCE("bounded_greedy_sequence"),
  CANDIDATE_PERIOD_CHECK("candidate_period_check"),
  EXACT_GEOMETRY("exact_geometry"),
  NUMERIC_COUNTEREXAMPLE("numeric_counterexample"),
  REAL_INEQUALITY("real_inequality"),
  NUMBER_THEORY_CHECK("number_theory_check"),
  EXACT_LINEAR_ALGEBRA("exact_linear_algebra"),
  FINITE_SET_MAP_CHECK("finite_set_map_check"),
  HYPERGRAPH_TRANSVERSAL("hypergraph_transversal"),
  SANDBOXED_PYTHON("sandboxed_python"),
  LEAN_CHECK("lean_check");

  private final String value;

  ComputationMethod(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static ComputationMethod fromValue(String value) {
    for (ComputationMethod candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown ComputationMethod value: " + value);
  }
}
