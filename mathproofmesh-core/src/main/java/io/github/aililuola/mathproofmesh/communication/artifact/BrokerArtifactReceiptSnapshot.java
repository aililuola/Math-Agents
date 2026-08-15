package io.github.aililuola.mathproofmesh.communication.artifact;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor stores immutable defensive copies.")
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
