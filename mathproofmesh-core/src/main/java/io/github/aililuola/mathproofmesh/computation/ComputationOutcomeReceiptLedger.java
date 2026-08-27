package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationOutcomeApplicationReceipt;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ComputationOutcomeReceiptLedger {
  private final ConcurrentMap<String, ComputationOutcomeApplicationReceipt> receipts =
      new ConcurrentHashMap<>();

  public ComputationOutcomeApplicationReceipt record(
      ComputationOutcomeApplicationReceipt receipt) {
    return receipts.merge(
        receipt.applicationId(),
        receipt,
        (existing, candidate) -> {
          if (!existing.receiptHash().equals(candidate.receiptHash())) {
            throw new IllegalStateException("outcome application identity collision");
          }
          return existing;
        });
  }

  public Optional<ComputationOutcomeApplicationReceipt> find(String applicationId) {
    return Optional.ofNullable(receipts.get(applicationId));
  }

  public ComputationOutcomeReceiptSnapshot snapshot() {
    return new ComputationOutcomeReceiptSnapshot(receipts.values().stream().toList(), null);
  }

  public void restore(ComputationOutcomeReceiptSnapshot snapshot) {
    receipts.clear();
    if (snapshot != null) {
      snapshot.receipts().forEach(value -> receipts.put(value.applicationId(), value));
    }
  }
}
