package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ReceiptStatus {
  ACCEPTED("accepted"),
  REJECTED("rejected"),
  DUPLICATE("duplicate"),
  EXPIRED("expired"),
  DEFERRED("deferred");

  private final String value;

  ReceiptStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static ReceiptStatus fromValue(String value) {
    for (ReceiptStatus candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown ReceiptStatus value: " + value);
  }
}
