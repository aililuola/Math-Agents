package io.github.aililuola.mathproofmesh.communication;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.List;

public final class DeliveryKey {
  private DeliveryKey() {}

  public static String of(String messageId, String targetRouteId) {
    if (messageId == null || messageId.isBlank()) {
      throw new IllegalArgumentException("messageId is required");
    }
    if (targetRouteId == null || targetRouteId.isBlank()) {
      throw new IllegalArgumentException("targetRouteId is required");
    }
    return CanonicalJson.stableHash(List.of(messageId, targetRouteId));
  }
}
