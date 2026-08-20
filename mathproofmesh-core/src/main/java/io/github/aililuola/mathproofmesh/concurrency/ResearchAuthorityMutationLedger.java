package io.github.aililuola.mathproofmesh.concurrency;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Durable, idempotent receipts for completed epoch authority transactions. */
public final class ResearchAuthorityMutationLedger {
  private final Map<String, ResearchAuthorityMutationReceipt> authorityMutations =
      new LinkedHashMap<>();
  private final Map<String, ResearchMergeReceipt> mergeReceipts = new LinkedHashMap<>();
  private long version;

  public synchronized ResearchAuthorityMutationReceipt recordAuthorityMutation(
      ResearchAuthorityMutationReceipt receipt) {
    Objects.requireNonNull(receipt, "receipt");
    ResearchAuthorityMutationReceipt existing = authorityMutations.get(receipt.epochId());
    if (existing != null) {
      if (!existing.receiptHash().equals(receipt.receiptHash())) {
        throw new IllegalStateException("research authority mutation identity conflict");
      }
      return existing;
    }
    authorityMutations.put(receipt.epochId(), receipt);
    version++;
    return receipt;
  }

  public synchronized ResearchMergeReceipt recordMergeReceipt(ResearchMergeReceipt receipt) {
    Objects.requireNonNull(receipt, "receipt");
    ResearchAuthorityMutationReceipt mutation = authorityMutations.get(receipt.epochId());
    if (mutation != null) {
      new ResearchEpochCommitResult(mutation, receipt);
    }
    ResearchMergeReceipt existing = mergeReceipts.get(receipt.epochId());
    if (existing != null) {
      if (!existing.equals(receipt)) {
        throw new IllegalStateException("research merge receipt identity conflict");
      }
      return existing;
    }
    mergeReceipts.put(receipt.epochId(), receipt);
    version++;
    return receipt;
  }

  public synchronized Optional<ResearchAuthorityMutationReceipt> authorityMutation(
      String epochId) {
    return Optional.ofNullable(authorityMutations.get(epochId));
  }

  public synchronized Optional<ResearchMergeReceipt> mergeReceipt(String epochId) {
    return Optional.ofNullable(mergeReceipts.get(epochId));
  }

  public synchronized ResearchAuthorityMutationSnapshot snapshot() {
    return new ResearchAuthorityMutationSnapshot(
        authorityMutations.values().stream().toList(),
        mergeReceipts.values().stream().toList(),
        version,
        null);
  }

  public synchronized void restore(ResearchAuthorityMutationSnapshot snapshot) {
    ResearchAuthorityMutationSnapshot source =
        snapshot == null ? ResearchAuthorityMutationSnapshot.empty() : snapshot;
    authorityMutations.clear();
    mergeReceipts.clear();
    source.authorityMutations().forEach(value -> authorityMutations.put(value.epochId(), value));
    source.mergeReceipts().forEach(value -> mergeReceipts.put(value.epochId(), value));
    mergeReceipts.forEach(
        (epochId, merge) -> {
          ResearchAuthorityMutationReceipt mutation = authorityMutations.get(epochId);
          if (mutation != null) {
            new ResearchEpochCommitResult(mutation, merge);
          }
        });
    version = source.version();
  }
}
