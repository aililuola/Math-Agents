package io.github.aililuola.mathproofmesh.runstate;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RunExecutionAttemptLedger {
  private final Map<String, RunExecutionAttemptRecord> attempts = new LinkedHashMap<>();

  public synchronized RunExecutionAttemptRecord create(String runId, Instant now) {
    Objects.requireNonNull(now, "now");
    int ordinal = attempts.size();
    String attemptId =
        "execution-attempt-"
            + CanonicalJson.stableHash(List.of(runId, ordinal, now.toString())).substring(0, 24);
    RunExecutionAttemptRecord record =
        new RunExecutionAttemptRecord(
            attemptId,
            runId,
            ordinal,
            RunExecutionAttemptStatus.QUEUED,
            "",
            now,
            now,
            0L);
    attempts.put(attemptId, record);
    return record;
  }

  public synchronized RunExecutionAttemptRecord transition(
      String attemptId, RunExecutionAttemptStatus next, String failureCode, Instant now) {
    RunExecutionAttemptRecord prior = require(attemptId);
    if (!allowed(prior.status(), next)) {
      throw new IllegalStateException(
          "illegal execution attempt transition " + prior.status() + " -> " + next);
    }
    RunExecutionAttemptRecord updated =
        new RunExecutionAttemptRecord(
            prior.attemptId(),
            prior.runId(),
            prior.ordinal(),
            next,
            failureCode,
            prior.createdAt(),
            Objects.requireNonNull(now, "now"),
            prior.version() + 1L);
    attempts.put(attemptId, updated);
    return updated;
  }

  public synchronized RunExecutionAttemptRecord require(String attemptId) {
    RunExecutionAttemptRecord record = attempts.get(attemptId);
    if (record == null) {
      throw new IllegalArgumentException("unknown execution attempt");
    }
    return record;
  }

  public synchronized RunExecutionAttemptRecord latest() {
    if (attempts.isEmpty()) {
      throw new IllegalStateException("execution attempt ledger is empty");
    }
    return new ArrayList<>(attempts.values()).getLast();
  }

  public synchronized RunExecutionAttemptSnapshot snapshot() {
    return new RunExecutionAttemptSnapshot(List.copyOf(attempts.values()), null);
  }

  public synchronized void restore(RunExecutionAttemptSnapshot snapshot) {
    attempts.clear();
    for (RunExecutionAttemptRecord record : Objects.requireNonNull(snapshot, "snapshot").attempts()) {
      if (attempts.put(record.attemptId(), record) != null) {
        throw new IllegalArgumentException("duplicate execution attempt");
      }
    }
  }

  private static boolean allowed(
      RunExecutionAttemptStatus prior, RunExecutionAttemptStatus next) {
    if (prior == next) {
      return true;
    }
    if (prior.terminal()) {
      return false;
    }
    return prior == RunExecutionAttemptStatus.QUEUED
        ? next == RunExecutionAttemptStatus.RUNNING
            || next == RunExecutionAttemptStatus.CANCELLED
        : next.terminal();
  }
}
