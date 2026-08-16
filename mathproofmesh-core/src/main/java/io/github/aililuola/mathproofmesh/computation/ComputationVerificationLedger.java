package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationVerificationReceipt;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ComputationVerificationLedger {
  private final ConcurrentMap<String, ComputationVerificationReceipt> receipts =
      new ConcurrentHashMap<>();

  public ComputationVerificationReceipt record(ComputationVerificationReceipt receipt) {
    return receipts.merge(
        receipt.receiptId(),
        receipt,
        (existing, candidate) -> {
          if (!ComputationJson.hashesEqual(existing.receiptHash(), candidate.receiptHash())) {
            throw new IllegalStateException("verification receipt identity collision");
          }
          return existing;
        });
  }

  public Optional<ComputationVerificationReceipt> find(String receiptId) {
    return Optional.ofNullable(receipts.get(receiptId));
  }

  public Optional<ComputationVerificationReceipt> findByCertificateHash(String certificateHash) {
    return receipts.values().stream()
        .filter(value -> ComputationJson.hashesEqual(value.certificateHash(), certificateHash))
        .findFirst();
  }

  public ComputationVerificationSnapshot snapshot() {
    return new ComputationVerificationSnapshot(receipts.values().stream().toList(), null);
  }

  public void restore(ComputationVerificationSnapshot snapshot) {
    receipts.clear();
    if (snapshot != null) {
      snapshot.receipts().forEach(value -> receipts.put(value.receiptId(), value));
    }
  }
}
