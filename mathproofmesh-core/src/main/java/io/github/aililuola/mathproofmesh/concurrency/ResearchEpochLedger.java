package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ResearchEpochLedger {
  private final Map<String, ResearchEpochRecord> epochs = new LinkedHashMap<>();
  private long version;

  public synchronized ResearchEpochRecord plan(
      FrozenResearchSnapshot snapshot, List<String> workItemIds) {
    Objects.requireNonNull(snapshot, "snapshot");
    ResearchEpochRecord existing = epochs.get(snapshot.epochId());
    if (existing != null) {
      if (!existing.snapshotHash().equals(snapshot.snapshotHash())) {
        throw new IllegalStateException("epoch snapshot identity conflict");
      }
      return existing;
    }
    ResearchEpochRecord record =
        new ResearchEpochRecord(
            snapshot.epochId(),
            snapshot.snapshotHash(),
            ResearchEpochStatus.PLANNED,
            workItemIds,
            List.of(),
            "",
            snapshot.authority(),
            ResearchAuthorityCommitProtocol.RECEIPT_V1,
            "",
            1L);
    epochs.put(record.epochId(), record);
    version++;
    return record;
  }

  public synchronized ResearchEpochRecord transition(
      String epochId,
      ResearchEpochStatus status,
      List<String> resultIds,
      String mergePlanHash) {
    ResearchEpochRecord prior = require(epochId);
    ResearchEpochRecord next = prior.transition(status, resultIds, mergePlanHash);
    epochs.put(epochId, next);
    version++;
    return next;
  }

  public synchronized ResearchEpochRecord commit(String epochId, String authorityHashAfterCommit) {
    ResearchEpochRecord prior = require(epochId);
    ResearchEpochRecord next =
        prior
            .transition(ResearchEpochStatus.COMMITTED, null, null)
            .withAuthorityHashAfterCommit(authorityHashAfterCommit);
    epochs.put(epochId, next);
    version++;
    return next;
  }

  public synchronized ResearchEpochRecord require(String epochId) {
    ResearchEpochRecord record = epochs.get(epochId);
    if (record == null) {
      throw new IllegalArgumentException("unknown epoch: " + epochId);
    }
    return record;
  }

  public synchronized ResearchEpochSnapshot snapshot() {
    return new ResearchEpochSnapshot(
        epochs.values().stream().sorted(Comparator.comparing(ResearchEpochRecord::epochId)).toList(),
        version);
  }

  public synchronized void restore(ResearchEpochSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    epochs.clear();
    snapshot.epochs().forEach(record -> epochs.put(record.epochId(), record));
    version = snapshot.version();
  }
}
