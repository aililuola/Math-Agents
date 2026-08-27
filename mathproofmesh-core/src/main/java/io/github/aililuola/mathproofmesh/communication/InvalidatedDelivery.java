package io.github.aililuola.mathproofmesh.communication;

public record InvalidatedDelivery(
    MessageDelivery delivery, String deliveryState, String invalidationReason) {

  public InvalidatedDelivery {
    delivery = java.util.Objects.requireNonNull(delivery, "delivery");
    if (!"invalidated".equals(deliveryState)) {
      throw new IllegalArgumentException("deliveryState must be invalidated");
    }
    if (invalidationReason == null || invalidationReason.isBlank()) {
      throw new IllegalArgumentException("invalidationReason is required");
    }
  }

  public static InvalidatedDelivery of(MessageDelivery delivery, String reason) {
    return new InvalidatedDelivery(delivery, "invalidated", reason);
  }
}
