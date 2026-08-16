package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationReceipt;
import java.util.Comparator;
import java.util.List;

public record ComputationVerificationSnapshot(
    List<ComputationVerificationReceipt> receipts, String stableHash) {
  public ComputationVerificationSnapshot {
    receipts =
        receipts == null
            ? List.of()
            : receipts.stream()
                .sorted(Comparator.comparing(ComputationVerificationReceipt::receiptId))
                .toList();
    String expected = CanonicalJson.stableHash(receipts);
    stableHash = stableHash == null || stableHash.isBlank() ? expected : stableHash.strip();
    if (!ComputationJson.hashesEqual(expected, stableHash)) {
      throw new IllegalArgumentException("computation verification snapshot hash mismatch");
    }
  }

  public static ComputationVerificationSnapshot empty() {
    return new ComputationVerificationSnapshot(List.of(), null);
  }

  @Override
  public List<ComputationVerificationReceipt> receipts() {
    return List.copyOf(receipts);
  }
}
