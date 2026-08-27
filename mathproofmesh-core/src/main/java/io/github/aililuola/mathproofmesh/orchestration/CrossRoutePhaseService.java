package io.github.aililuola.mathproofmesh.orchestration;

import java.util.List;

/** Prevents direct route-to-route transcript or claim sharing. */
public final class CrossRoutePhaseService {
  private final BrokerPhaseService broker;

  public CrossRoutePhaseService(BrokerPhaseService broker) {
    this.broker = java.util.Objects.requireNonNull(broker, "broker");
  }

  public List<BrokerPhaseService.BrokerPacket> share(
      List<BrokerPhaseService.ReviewedClaim> claims, String sourceDeltaId) {
    return broker.publish(claims, sourceDeltaId);
  }

  public static boolean directRouteBypassAllowed() {
    return false;
  }
}
