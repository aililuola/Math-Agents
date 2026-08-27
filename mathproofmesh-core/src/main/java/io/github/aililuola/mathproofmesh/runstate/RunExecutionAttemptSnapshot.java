package io.github.aililuola.mathproofmesh.runstate;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.Comparator;
import java.util.List;

public record RunExecutionAttemptSnapshot(
    List<RunExecutionAttemptRecord> attempts, String stableHash) {
  public RunExecutionAttemptSnapshot {
    attempts =
        attempts == null
            ? List.of()
            : attempts.stream()
                .sorted(Comparator.comparingInt(RunExecutionAttemptRecord::ordinal))
                .toList();
    stableHash =
        RunStateHashes.generatedOrVerified(
            stableHash,
            attempts.stream()
                .map(
                    item ->
                        java.util.Map.of(
                            "attemptId", item.attemptId(),
                            "runId", item.runId(),
                            "ordinal", item.ordinal(),
                            "status", item.status(),
                            "failureCode", item.failureCode(),
                            "createdAt", item.createdAt().toString(),
                            "updatedAt", item.updatedAt().toString(),
                            "version", item.version()))
                .toList(),
            "attempt ledger");
  }

  public static RunExecutionAttemptSnapshot empty() {
    return new RunExecutionAttemptSnapshot(List.of(), null);
  }

  @Override
  public List<RunExecutionAttemptRecord> attempts() {
    return List.copyOf(attempts);
  }
}
