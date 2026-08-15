package io.github.aililuola.mathproofmesh.communication.artifact;

import java.util.Map;

public record BrokerArtifactReceiptSnapshot(
    Map<String, BrokerArtifactReceipt> receipts, long version) {
  public BrokerArtifactReceiptSnapshot {
    receipts = BrokerArtifactValues.map(receipts);
    if (version < 0L) throw new IllegalArgumentException("version must be nonnegative");
  }
  public static BrokerArtifactReceiptSnapshot empty() {
    return new BrokerArtifactReceiptSnapshot(Map.of(), 0L);
  }
}
