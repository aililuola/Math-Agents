package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum DeliveryState {
  QUEUED("queued"),
  SCHEDULED("scheduled"),
  PRESENTED("presented"),
  ACKNOWLEDGED("acknowledged"),
  USED("used"),
  EXPIRED_WITHOUT_OPPORTUNITY("expired_without_opportunity"),
  INVALIDATED("invalidated");

  private final String value;

  DeliveryState(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static DeliveryState fromValue(String value) {
    for (DeliveryState candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown DeliveryState value: " + value);
  }
}
