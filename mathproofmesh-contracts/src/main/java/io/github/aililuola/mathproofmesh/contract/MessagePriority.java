package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MessagePriority {
  CRITICAL("critical"),
  HIGH("high"),
  NORMAL("normal"),
  LOW("low");

  private final String value;

  MessagePriority(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static MessagePriority fromValue(String value) {
    for (MessagePriority candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown MessagePriority value: " + value);
  }
}
