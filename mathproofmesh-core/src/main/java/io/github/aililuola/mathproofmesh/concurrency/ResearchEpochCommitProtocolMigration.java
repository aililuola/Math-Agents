package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Classifies legacy epoch commit protocols without inferring them from the upgraded schema. */
public final class ResearchEpochCommitProtocolMigration {
  private ResearchEpochCommitProtocolMigration() {}

  public static ResearchEpochSnapshot migrate(
      int schemaVersion,
      ResearchEpochSnapshot epochSnapshot,
      ResearchAuthorityMutationSnapshot mutationSnapshot) {
    ResearchEpochSnapshot epochs = Objects.requireNonNull(epochSnapshot, "epochSnapshot");
    ResearchAuthorityMutationSnapshot mutations =
        Objects.requireNonNull(mutationSnapshot, "mutationSnapshot");
    Map<String, ResearchAuthorityMutationReceipt> mutationByEpoch =
        mutations.authorityMutations().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    ResearchAuthorityMutationReceipt::epochId, Function.identity()));
    Map<String, ResearchMergeReceipt> mergeByEpoch =
        mutations.mergeReceipts().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    ResearchMergeReceipt::epochId, Function.identity()));
    return new ResearchEpochSnapshot(
        epochs.epochs().stream()
            .map(epoch -> migrateEpoch(schemaVersion, epoch, mutationByEpoch, mergeByEpoch))
            .toList(),
        epochs.version());
  }

  private static ResearchEpochRecord migrateEpoch(
      int schemaVersion,
      ResearchEpochRecord epoch,
      Map<String, ResearchAuthorityMutationReceipt> mutationByEpoch,
      Map<String, ResearchMergeReceipt> mergeByEpoch) {
    if (epoch.authorityCommitProtocol() != null) {
      return epoch;
    }
    ResearchAuthorityMutationReceipt mutation = mutationByEpoch.get(epoch.epochId());
    ResearchMergeReceipt merge = mergeByEpoch.get(epoch.epochId());
    ResearchAuthorityCommitProtocol protocol =
        schemaVersion <= 20
                || (epoch.status() == ResearchEpochStatus.COMMITTED
                    && mutation == null
                    && merge == null)
            ? ResearchAuthorityCommitProtocol.LEGACY_NO_RECEIPT
            : ResearchAuthorityCommitProtocol.RECEIPT_V1;
    ResearchEpochRecord classified = epoch.withAuthorityCommitProtocol(protocol);
    if (protocol == ResearchAuthorityCommitProtocol.RECEIPT_V1
        && classified.status() == ResearchEpochStatus.COMMITTED
        && classified.authorityHashAfterCommit().isBlank()
        && mutation != null
        && merge != null) {
      return classified.withAuthorityHashAfterCommit(mutation.authorityHashAfter());
    }
    return classified;
  }
}
