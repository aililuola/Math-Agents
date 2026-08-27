package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ResearchTaskLedger {
  private final Map<String, ResearchWorkRecord> tasks = new LinkedHashMap<>();
  private long version;

  public synchronized ResearchWorkRecord plan(ResearchWorkItem item) {
    Objects.requireNonNull(item, "item");
    ResearchWorkRecord existing = tasks.get(item.workItemId());
    if (existing != null) {
      if (!existing.item().equals(item)) {
        throw new IllegalStateException("work item identity conflict: " + item.workItemId());
      }
      return existing;
    }
    ResearchWorkRecord record =
        new ResearchWorkRecord(item, ResearchWorkStatus.PLANNED, "", "", "", "", 1L);
    tasks.put(item.workItemId(), record);
    version++;
    return record;
  }

  public synchronized ResearchWorkRecord transition(
      String workItemId,
      ResearchWorkStatus status,
      String agentId,
      String providerRequestId,
      String resultRef,
      String resultHash) {
    ResearchWorkRecord prior = require(workItemId);
    ResearchWorkRecord next =
        prior.transition(status, agentId, providerRequestId, resultRef, resultHash);
    if (next != prior) {
      tasks.put(workItemId, next);
      version++;
    }
    return next;
  }

  public synchronized ResearchWorkRecord require(String workItemId) {
    ResearchWorkRecord record = tasks.get(workItemId);
    if (record == null) {
      throw new IllegalArgumentException("unknown work item: " + workItemId);
    }
    return record;
  }

  public synchronized boolean allSettled(String epochId) {
    List<ResearchWorkRecord> epochTasks =
        tasks.values().stream().filter(record -> record.item().epochId().equals(epochId)).toList();
    return !epochTasks.isEmpty()
        && epochTasks.stream().allMatch(record -> record.status().settled());
  }

  public synchronized ResearchTaskSnapshot snapshot() {
    return new ResearchTaskSnapshot(
        tasks.values().stream()
            .sorted(Comparator.comparing(record -> record.item().workItemId()))
            .toList(),
        version);
  }

  public synchronized void restore(ResearchTaskSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    tasks.clear();
    for (ResearchWorkRecord record : snapshot.tasks()) {
      ResearchWorkRecord restored = record;
      if (record.status() == ResearchWorkStatus.LEASED
          || record.status() == ResearchWorkStatus.RUNNING) {
        restored =
            record.providerRequestId().isEmpty()
                ? record.transition(
                    ResearchWorkStatus.PLANNED, null, null, null, null)
                : record.transition(
                    ResearchWorkStatus.QUARANTINED_UNCERTAIN_CALL, null, null, null, null);
      }
      tasks.put(restored.item().workItemId(), restored);
    }
    version = snapshot.version();
  }
}
