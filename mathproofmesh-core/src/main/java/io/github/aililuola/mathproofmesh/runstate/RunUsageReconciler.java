package io.github.aililuola.mathproofmesh.runstate;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RunUsageReconciler {
  public RunUsageReconciliationResult reconcile(
      List<RunUsageEvidence> evidence, RunUsageSnapshot previous) {
    List<RunUsageEvidence> sources = evidence == null ? List.of() : List.copyOf(evidence);
    RunUsageSnapshot prior = previous == null ? RunUsageSnapshot.empty() : previous;
    List<ProviderCallUsageEvidence> durableCalls =
        sources.stream().flatMap(item -> item.providerCalls().stream()).toList();
    RunUsageReconciliationResult result =
        durableCalls.isEmpty() ? reconcileAggregates(sources) : reconcileCalls(durableCalls);
    if (result.status() == RunUsageStatus.CONFLICT) {
      return new RunUsageReconciliationResult(result.status(), maximum(prior, result.usage()), result.conflicts());
    }
    RunUsageSnapshot selected = result.usage();
    if (selected.providerCalls() < prior.providerCalls()
        || selected.inputTokens() < prior.inputTokens()
        || selected.outputTokens() < prior.outputTokens()
        || selected.totalTokens() < prior.totalTokens()
        || selected.estimatedCostUsd().compareTo(prior.estimatedCostUsd()) < 0
        || selected.latencyMs() < prior.latencyMs()) {
      return new RunUsageReconciliationResult(
          RunUsageStatus.CONFLICT,
          prior,
          List.of(
              new RunUsageConflict(
                  "aggregate",
                  "USAGE_MONOTONICITY_CONFLICT",
                  sources.stream().map(RunUsageEvidence::evidenceRef).filter(ref -> !ref.isEmpty()).toList())));
    }
    return result;
  }

  private static RunUsageReconciliationResult reconcileCalls(
      List<ProviderCallUsageEvidence> calls) {
    Map<String, ProviderCallUsageEvidence> unique = new LinkedHashMap<>();
    List<RunUsageConflict> conflicts = new ArrayList<>();
    for (ProviderCallUsageEvidence call : calls) {
      ProviderCallUsageEvidence prior = unique.putIfAbsent(call.providerRequestId(), call);
      if (prior != null && !sameCounters(prior, call)) {
        conflicts.add(
            new RunUsageConflict(
                call.providerRequestId(),
                "PROVIDER_REQUEST_USAGE_CONFLICT",
                List.of(prior.sourceArtifactHash(), call.sourceArtifactHash()).stream()
                    .filter(ref -> !ref.isEmpty())
                    .toList()));
      }
    }
    long input = 0L;
    long output = 0L;
    BigDecimal cost = BigDecimal.ZERO;
    double latency = 0.0d;
    Set<String> requestIds = new LinkedHashSet<>();
    Set<String> artifacts = new LinkedHashSet<>();
    for (ProviderCallUsageEvidence call : unique.values()) {
      input = Math.addExact(input, call.inputTokens());
      output = Math.addExact(output, call.outputTokens());
      cost = cost.add(call.estimatedCostUsd());
      latency += call.latencyMs();
      if (!Double.isFinite(latency)) {
        throw new IllegalArgumentException("provider latency total is not finite");
      }
      requestIds.add(call.providerRequestId());
      if (!call.sourceArtifactHash().isEmpty()) {
        artifacts.add(call.sourceArtifactHash());
      }
    }
    RunUsageSnapshot snapshot =
        RunUsageSnapshot.of(
            unique.size(),
            input,
            output,
            cost,
            latency,
            CanonicalJson.stableHash(requestIds.stream().sorted().toList()),
            CanonicalJson.stableHash(artifacts.stream().sorted().toList()));
    return new RunUsageReconciliationResult(
        conflicts.isEmpty() ? RunUsageStatus.RECORDED : RunUsageStatus.CONFLICT,
        snapshot,
        conflicts);
  }

  private static RunUsageReconciliationResult reconcileAggregates(List<RunUsageEvidence> evidence) {
    List<RunUsageEvidence> candidates =
        evidence.stream()
            .filter(item -> item.aggregate().providerCalls() > 0L || item.aggregate().totalTokens() > 0L)
            .toList();
    if (candidates.isEmpty()) {
      return new RunUsageReconciliationResult(
          RunUsageStatus.NOT_RECORDED, RunUsageSnapshot.empty(), List.of());
    }
    List<RunUsageEvidence> extensions =
        candidates.stream()
            .filter(
                candidate ->
                    candidates.stream()
                        .allMatch(other -> dominates(candidate.aggregate(), other.aggregate())))
            .toList();
    if (extensions.isEmpty()) {
      return new RunUsageReconciliationResult(
          RunUsageStatus.CONFLICT,
          candidates.getFirst().aggregate(),
          List.of(
              new RunUsageConflict(
                  "aggregate",
                  "INCOMPARABLE_AGGREGATE_USAGE",
                  candidates.stream()
                      .map(RunUsageEvidence::evidenceRef)
                      .filter(ref -> !ref.isEmpty())
                      .sorted()
                      .toList())));
    }
    RunUsageEvidence selected =
        extensions.stream()
            .max(java.util.Comparator.comparingInt(item -> item.source().priority()))
            .orElseThrow();
    RunUsageStatus status =
        selected.source() == RunUsageEvidenceSource.RESULT_PROJECTION
            ? RunUsageStatus.PARTIAL_RECORDED
            : RunUsageStatus.RECORDED;
    return new RunUsageReconciliationResult(status, selected.aggregate(), List.of());
  }

  private static boolean dominates(RunUsageSnapshot candidate, RunUsageSnapshot other) {
    return candidate.providerCalls() >= other.providerCalls()
        && candidate.inputTokens() >= other.inputTokens()
        && candidate.outputTokens() >= other.outputTokens()
        && candidate.totalTokens() >= other.totalTokens()
        && candidate.estimatedCostUsd().compareTo(other.estimatedCostUsd()) >= 0
        && candidate.latencyMs() >= other.latencyMs();
  }

  private static boolean sameCounters(
      ProviderCallUsageEvidence left, ProviderCallUsageEvidence right) {
    return left.inputTokens() == right.inputTokens()
        && left.outputTokens() == right.outputTokens()
        && left.estimatedCostUsd().compareTo(right.estimatedCostUsd()) == 0
        && Double.compare(left.latencyMs(), right.latencyMs()) == 0;
  }

  private static RunUsageSnapshot maximum(RunUsageSnapshot left, RunUsageSnapshot right) {
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(right, "right");
    return left.totalTokens() >= right.totalTokens() ? left : right;
  }
}
