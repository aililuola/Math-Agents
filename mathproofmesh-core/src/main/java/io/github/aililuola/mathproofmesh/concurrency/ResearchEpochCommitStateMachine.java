package io.github.aililuola.mathproofmesh.concurrency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Optional;

/** Deterministic restore decision for an epoch authority commit frontier. */
public final class ResearchEpochCommitStateMachine {
  public enum RecoveryAction {
    REPLAY_PREPARED,
    NO_OP_COMMITTED,
    ROLL_FORWARD_RECEIPTED,
    LEGACY_NO_RECEIPT,
    IGNORE_NON_COMMIT_FRONTIER,
    QUARANTINE_PARTIAL_AUTHORITY_COMMIT
  }

  public record RecoveryDecision(RecoveryAction action, String code) {
    public RecoveryDecision {
      Objects.requireNonNull(action, "action");
      code = Objects.requireNonNull(code, "code").strip();
      if (code.isEmpty()) {
        throw new IllegalArgumentException("code must not be blank");
      }
    }

    public boolean quarantined() {
      return action == RecoveryAction.QUARANTINE_PARTIAL_AUTHORITY_COMMIT;
    }
  }

  public RecoveryDecision reconcile(
      ResearchEpochRecord epoch,
      String currentAuthorityHash,
      Optional<ResearchAuthorityMutationReceipt> mutationReceipt,
      boolean mergeReceiptPresent,
      boolean receiptsRequired) {
    Objects.requireNonNull(epoch, "epoch");
    String current = required(currentAuthorityHash, "currentAuthorityHash");
    Optional<ResearchAuthorityMutationReceipt> receipt =
        Objects.requireNonNull(mutationReceipt, "mutationReceipt");
    if (epoch.status() == ResearchEpochStatus.MERGE_PREPARED) {
      if (receipt.isEmpty()) {
        if (mergeReceiptPresent && receiptsRequired) {
          return quarantine();
        }
        if (epoch.authority() != null && sameHash(epoch.authority().stableHash(), current)) {
          return new RecoveryDecision(RecoveryAction.REPLAY_PREPARED, "REPLAY_FROZEN_PREPARED");
        }
        return quarantine();
      }
      ResearchAuthorityMutationReceipt durable = receipt.orElseThrow();
      if (!receiptBindsEpoch(epoch, durable)) {
        return quarantine();
      }
      if (sameHash(durable.authorityHashAfter(), current)) {
        return new RecoveryDecision(
            RecoveryAction.ROLL_FORWARD_RECEIPTED,
            mergeReceiptPresent
                ? "ROLL_FORWARD_RECEIPTED_COMMIT"
                : "ROLL_FORWARD_RECEIPTED_AUTHORITY_AND_REBUILD_MERGE");
      }
      return quarantine();
    }
    if (epoch.status() == ResearchEpochStatus.COMMITTED) {
      if (receipt.isPresent()
          && mergeReceiptPresent
          && receiptBindsEpoch(epoch, receipt.orElseThrow())
          && sameHash(receipt.orElseThrow().authorityHashAfter(), current)) {
        return new RecoveryDecision(RecoveryAction.NO_OP_COMMITTED, "COMMITTED_RECEIPT_VERIFIED");
      }
      if (!receiptsRequired && receipt.isEmpty()) {
        return new RecoveryDecision(RecoveryAction.LEGACY_NO_RECEIPT, "LEGACY_COMMITTED_EPOCH");
      }
      return quarantine();
    }
    return new RecoveryDecision(
        RecoveryAction.IGNORE_NON_COMMIT_FRONTIER, "NON_COMMIT_FRONTIER_UNCHANGED");
  }

  private static RecoveryDecision quarantine() {
    return new RecoveryDecision(
        RecoveryAction.QUARANTINE_PARTIAL_AUTHORITY_COMMIT,
        "QUARANTINED_PARTIAL_AUTHORITY_COMMIT");
  }

  private static boolean receiptBindsEpoch(
      ResearchEpochRecord epoch, ResearchAuthorityMutationReceipt receipt) {
    return epoch.authority() != null
        && receipt.epochId().equals(epoch.epochId())
        && receipt.mergePlanHash().equals(epoch.mergePlanHash())
        && sameHash(receipt.authorityHashBefore(), epoch.authority().stableHash())
        && epoch.durableResultIds().containsAll(receipt.acceptedResultHashes());
  }

  private static String required(String value, String label) {
    String normalized = Objects.requireNonNull(value, label).strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }

  private static boolean sameHash(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }
}
