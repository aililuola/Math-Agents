package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.provider.UsageTotals;
import io.github.aililuola.mathproofmesh.runstate.ProviderCallUsageEvidence;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reconciles aggregate usage with immutable, request-keyed provider response artifacts. */
final class DurableProviderUsageCollector {
  enum Status {
    NO_DURABLE_EVIDENCE,
    DURABLE_EXTENSION,
    AGGREGATE_PRESERVED,
    AGGREGATE_CONFLICT,
    REQUEST_CONFLICT;

    boolean conflict() {
      return this == AGGREGATE_CONFLICT || this == REQUEST_CONFLICT;
    }
  }

  record Result(UsageTotals totals, List<ProviderCallUsageEvidence> evidence, Status status) {
    Result {
      totals = Objects.requireNonNull(totals, "totals");
      evidence = evidence == null ? List.of() : List.copyOf(evidence);
      status = Objects.requireNonNull(status, "status");
    }

    @Override
    public List<ProviderCallUsageEvidence> evidence() {
      return List.copyOf(evidence);
    }
  }

  private DurableProviderUsageCollector() {}

  static Result collect(
      Path runDirectory,
      SystemConfig config,
      UsageTotals aggregate,
      List<ProviderCallUsageEvidence> repositoryEvidence)
      throws IOException {
    List<ProviderCallUsageEvidence> artifactEvidence =
        ProviderUsageRecovery.recoverEvidence(runDirectory, config);
    try {
      return reconcile(
          aggregate,
          ProviderUsageRecovery.mergeEvidence(repositoryEvidence, artifactEvidence));
    } catch (IllegalStateException conflict) {
      List<ProviderCallUsageEvidence> unresolved = new ArrayList<>(repositoryEvidence);
      unresolved.addAll(artifactEvidence);
      return new Result(aggregate, unresolved, Status.REQUEST_CONFLICT);
    }
  }

  static Result collect(Path runDirectory, UsageTotals aggregate) throws IOException {
    List<ProviderCallUsageEvidence> artifactEvidence =
        ProviderUsageRecovery.recoverEmbeddedCostEvidence(runDirectory);
    try {
      return reconcile(aggregate, ProviderUsageRecovery.mergeEvidence(artifactEvidence));
    } catch (IllegalStateException conflict) {
      return new Result(aggregate, artifactEvidence, Status.REQUEST_CONFLICT);
    }
  }

  private static Result reconcile(
      UsageTotals aggregate, List<ProviderCallUsageEvidence> evidence) {
    Objects.requireNonNull(aggregate, "aggregate");
    if (evidence.isEmpty()) {
      return new Result(aggregate, List.of(), Status.NO_DURABLE_EVIDENCE);
    }
    UsageTotals durable = ProviderUsageRecovery.totals(evidence);
    boolean durableDominates = dominates(durable, aggregate);
    boolean aggregateDominates = dominates(aggregate, durable);
    if (durableDominates) {
      return new Result(durable, evidence, Status.DURABLE_EXTENSION);
    }
    if (aggregateDominates) {
      return new Result(aggregate, List.of(), Status.AGGREGATE_PRESERVED);
    }
    return new Result(aggregate, evidence, Status.AGGREGATE_CONFLICT);
  }

  private static boolean dominates(UsageTotals candidate, UsageTotals other) {
    return candidate.calls() >= other.calls()
        && candidate.inputTokens() >= other.inputTokens()
        && candidate.outputTokens() >= other.outputTokens()
        && candidate.costUsd().compareTo(other.costUsd()) >= 0
        && candidate.latencyMs() >= other.latencyMs();
  }
}
