package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum EvidenceStrength {
  HEURISTIC("heuristic"),
  BOUNDED_EVIDENCE("bounded_evidence"),
  COUNTEREXAMPLE("counterexample"),
  EXHAUSTIVE_CERTIFICATE("exhaustive_certificate"),
  FORMAL_CERTIFICATE("formal_certificate");

  private final String value;

  EvidenceStrength(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static EvidenceStrength fromValue(String value) {
    for (EvidenceStrength candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown EvidenceStrength value: " + value);
  }
}
