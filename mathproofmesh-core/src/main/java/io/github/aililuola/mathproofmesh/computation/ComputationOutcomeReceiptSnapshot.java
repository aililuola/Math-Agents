package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ComputationOutcomeApplicationReceipt;
import java.util.Comparator;
import java.util.List;

public record ComputationOutcomeReceiptSnapshot(
    List<ComputationOutcomeApplicationReceipt> receipts, String stableHash) {
  public ComputationOutcomeReceiptSnapshot {
    receipts =
        receipts == null
            ? List.of()
            : receipts.stream()
                .sorted(Comparator.comparing(ComputationOutcomeApplicationReceipt::applicationId))
                .toList();
    String expected = CanonicalJson.stableHash(receipts);
    stableHash = stableHash == null || stableHash.isBlank() ? expected : stableHash.strip();
    if (!ComputationJson.hashesEqual(expected, stableHash)) {
      throw new IllegalArgumentException("computation outcome snapshot hash mismatch");
    }
  }

  public static ComputationOutcomeReceiptSnapshot empty() {
    return new ComputationOutcomeReceiptSnapshot(List.of(), null);
  }

  @Override
  public List<ComputationOutcomeApplicationReceipt> receipts() {
    return List.copyOf(receipts);
  }
}
