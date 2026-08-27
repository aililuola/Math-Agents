package io.github.aililuola.mathproofmesh.communication.artifact;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor stores immutable defensive copies.")
public record BrokerArtifactDeliverySnapshot(
    Map<String, BrokerArtifactDelivery> deliveries,
    Map<String, BrokerDeliveryBaseline> baselines,
    Map<String, List<String>> providerRequests,
    long version) {
  public BrokerArtifactDeliverySnapshot {
    deliveries = BrokerArtifactValues.map(deliveries);
    baselines = BrokerArtifactValues.map(baselines);
    providerRequests = providerRequests == null ? Map.of() : providerRequests.entrySet().stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(
            Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    if (version < 0L) throw new IllegalArgumentException("version must be nonnegative");
  }
  public static BrokerArtifactDeliverySnapshot empty() {
    return new BrokerArtifactDeliverySnapshot(Map.of(), Map.of(), Map.of(), 0L);
  }
}
