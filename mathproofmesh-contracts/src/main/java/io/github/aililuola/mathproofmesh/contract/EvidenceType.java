package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum EvidenceType {
  UNVERIFIED_IDEA("unverified_idea"),
  NUMERICAL_HEURISTIC("numerical_heuristic"),
  BOUNDED_EXPERIMENT("bounded_experiment"),
  EXACT_SYMBOLIC_IDENTITY("exact_symbolic_identity"),
  COMPLETE_FINITE_ENUMERATION("complete_finite_enumeration"),
  SAT_SMT_CERTIFICATE("sat_smt_certificate"),
  COUNTEREXAMPLE("counterexample"),
  NATURAL_PROOF_AUDITED("natural_proof_audited"),
  FORMAL_KERNEL_CERTIFICATE("formal_kernel_certificate");

  private final String value;

  EvidenceType(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static EvidenceType fromValue(String value) {
    for (EvidenceType candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown EvidenceType value: " + value);
  }
}
