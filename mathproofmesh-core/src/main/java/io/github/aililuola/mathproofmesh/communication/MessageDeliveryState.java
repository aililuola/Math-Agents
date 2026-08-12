package io.github.aililuola.mathproofmesh.communication;

public enum MessageDeliveryState {
  QUEUED("queued"),
  DELIVERED("delivered"),
  PROMPT_CONSUMED("prompt_consumed"),
  ACKNOWLEDGED("acknowledged"),
  EXPIRED("expired"),
  REJECTED("rejected"),
  DEFERRED("deferred");

  private final String wireValue;

  MessageDeliveryState(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }
}
