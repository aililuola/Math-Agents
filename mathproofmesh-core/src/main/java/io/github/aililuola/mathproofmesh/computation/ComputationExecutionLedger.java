package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ComputationVerifiedAuthority;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;

/** Durable execution frontier and the sole source for computation quota usage. */
public final class ComputationExecutionLedger {
  private final ConcurrentMap<String, ComputationExecutionRecord> records =
      new ConcurrentHashMap<>();

  public ComputationExecutionRecord reserve(
      String runId,
      String routeId,
      ExperimentSpec spec,
      ComputationCapabilityDescriptor capability,
      String claimId,
      String obligationId,
      String requestArtifactRef,
      int round) {
    String executionId = stableExecutionId(runId, routeId, spec, capability);
    return records.computeIfAbsent(
        executionId,
        ignored -> {
          ComputationExecutionAuditEvent event =
              new ComputationExecutionAuditEvent(1, ComputationExecutionStatus.ADMITTED, round, "ADMITTED");
          return new ComputationExecutionRecord(
              executionId,
              routeId,
              claimId,
              obligationId,
              spec.requestHash(),
              spec.executionHash(),
              capability.capabilityId(),
              capability.capabilityVersion(),
              capability.backendKind(),
              ComputationExecutionStatus.ADMITTED,
              requestArtifactRef,
              "",
              "",
              "",
              "",
              "",
              ComputationVerifiedAuthority.AUDIT_ONLY,
              0,
              0,
              0,
              0,
              0,
              0.0d,
              round,
              round,
              "",
              List.of(event),
              1);
        });
  }

  public ComputationExecutionRecord markRunning(String executionId, int round) {
    return transition(
        executionId,
        current -> {
          if (current.status() != ComputationExecutionStatus.ADMITTED
              && current.status() != ComputationExecutionStatus.RUNNING) {
            return current;
          }
          return copy(
              current,
              ComputationExecutionStatus.RUNNING,
              current.resultArtifactRef(),
              current.certificateArtifactRef(),
              current.verificationReceiptRef(),
              current.outcomeApplicationReceiptRef(),
              current.authorityMutationReceiptRef(),
              current.authority(),
              current.attemptCount() + 1,
              current.producerExecutions(),
              current.verifierExecutions(),
              current.authorityProjections(),
              current.cacheHits(),
              current.cpuSeconds(),
              round,
              "",
              "RUNNING");
        });
  }

  public ComputationExecutionRecord markResultDurable(
      String executionId,
      String resultArtifactRef,
      String certificateArtifactRef,
      double cpuSeconds,
      boolean producerExecuted,
      boolean cacheHit,
      int round) {
    return transition(
        executionId,
        current -> {
          if (current.status() == ComputationExecutionStatus.RESULT_DURABLE
              || current.status() == ComputationExecutionStatus.VERIFICATION_DURABLE
              || current.status() == ComputationExecutionStatus.PROJECTION_READY
              || current.status() == ComputationExecutionStatus.AUTHORITY_MUTATION_DURABLE
              || current.status() == ComputationExecutionStatus.AUTHORITY_APPLIED) {
            return current;
          }
          return copy(
              current,
              ComputationExecutionStatus.RESULT_DURABLE,
              resultArtifactRef,
              certificateArtifactRef,
              current.verificationReceiptRef(),
              current.outcomeApplicationReceiptRef(),
              current.authorityMutationReceiptRef(),
              current.authority(),
              current.attemptCount(),
              current.producerExecutions() + (producerExecuted ? 1 : 0),
              current.verifierExecutions(),
              current.authorityProjections(),
              current.cacheHits() + (cacheHit ? 1 : 0),
              current.cpuSeconds() + cpuSeconds,
              round,
              "",
              cacheHit ? "CACHE_RESULT_DURABLE" : "RESULT_DURABLE");
        });
  }

  public ComputationExecutionRecord markVerificationDurable(
      String executionId,
      String receiptRef,
      ComputationVerifiedAuthority authority,
      int round) {
    return transition(
        executionId,
        current -> {
          if (current.status() == ComputationExecutionStatus.VERIFICATION_DURABLE
              || current.status() == ComputationExecutionStatus.PROJECTION_READY
              || current.status() == ComputationExecutionStatus.AUTHORITY_MUTATION_DURABLE
              || current.status() == ComputationExecutionStatus.AUTHORITY_APPLIED) {
            return current;
          }
          if (current.status() != ComputationExecutionStatus.RESULT_DURABLE) {
            throw new IllegalStateException("verification requires a durable result");
          }
          return copy(
              current,
              ComputationExecutionStatus.VERIFICATION_DURABLE,
              current.resultArtifactRef(),
              current.certificateArtifactRef(),
              receiptRef,
              current.outcomeApplicationReceiptRef(),
              current.authorityMutationReceiptRef(),
              authority,
              current.attemptCount(),
              current.producerExecutions(),
              current.verifierExecutions() + 1,
              current.authorityProjections(),
              current.cacheHits(),
              current.cpuSeconds(),
              round,
              "",
              "VERIFICATION_DURABLE");
        });
  }

  public ComputationExecutionRecord markProjectionReady(
      String executionId, String outcomeReceiptRef, int round) {
    return transition(
        executionId,
        current -> {
          if (current.status() == ComputationExecutionStatus.PROJECTION_READY
              || current.status() == ComputationExecutionStatus.AUTHORITY_MUTATION_DURABLE
              || current.status() == ComputationExecutionStatus.AUTHORITY_APPLIED) {
            return current;
          }
          if (current.status() != ComputationExecutionStatus.VERIFICATION_DURABLE) {
            throw new IllegalStateException("projection readiness requires durable verification");
          }
          return copy(
              current,
              ComputationExecutionStatus.PROJECTION_READY,
              current.resultArtifactRef(),
              current.certificateArtifactRef(),
              current.verificationReceiptRef(),
              outcomeReceiptRef,
              current.authorityMutationReceiptRef(),
              current.authority(),
              current.attemptCount(),
              current.producerExecutions(),
              current.verifierExecutions(),
              current.authorityProjections(),
              current.cacheHits(),
              current.cpuSeconds(),
              round,
              "",
              "PROJECTION_READY");
        });
  }

  public ComputationExecutionRecord markAuthorityMutationDurable(
      String executionId, String mutationReceiptRef, int round) {
    return transition(
        executionId,
        current -> {
          if (current.status() == ComputationExecutionStatus.AUTHORITY_MUTATION_DURABLE
              || current.status() == ComputationExecutionStatus.AUTHORITY_APPLIED) {
            return current;
          }
          if (current.status() != ComputationExecutionStatus.PROJECTION_READY) {
            throw new IllegalStateException("authority mutation requires a projection-ready plan");
          }
          return copy(
              current,
              ComputationExecutionStatus.AUTHORITY_MUTATION_DURABLE,
              current.resultArtifactRef(),
              current.certificateArtifactRef(),
              current.verificationReceiptRef(),
              current.outcomeApplicationReceiptRef(),
              mutationReceiptRef,
              current.authority(),
              current.attemptCount(),
              current.producerExecutions(),
              current.verifierExecutions(),
              current.authorityProjections() + 1,
              current.cacheHits(),
              current.cpuSeconds(),
              round,
              "",
              "AUTHORITY_MUTATION_DURABLE");
        });
  }

  public ComputationExecutionRecord markAuthorityApplied(
      String executionId, int round) {
    return transition(
        executionId,
        current -> {
          if (current.status() == ComputationExecutionStatus.AUTHORITY_APPLIED) {
            return current;
          }
          if (current.status() != ComputationExecutionStatus.AUTHORITY_MUTATION_DURABLE) {
            throw new IllegalStateException("authority apply requires a durable mutation receipt");
          }
          return copy(
              current,
              ComputationExecutionStatus.AUTHORITY_APPLIED,
              current.resultArtifactRef(),
              current.certificateArtifactRef(),
              current.verificationReceiptRef(),
              current.outcomeApplicationReceiptRef(),
              current.authorityMutationReceiptRef(),
              current.authority(),
              current.attemptCount(),
              current.producerExecutions(),
              current.verifierExecutions(),
              current.authorityProjections(),
              current.cacheHits(),
              current.cpuSeconds(),
              round,
              "",
              "AUTHORITY_APPLIED");
        });
  }

  /** Adds the missing mutation receipt projection to a pre-transaction checkpoint. */
  public ComputationExecutionRecord migrateLegacyAppliedMutation(
      String executionId, String mutationReceiptRef, int round) {
    return transition(
        executionId,
        current -> {
          if (current.status() != ComputationExecutionStatus.AUTHORITY_APPLIED
              || !current.authorityMutationReceiptRef().isEmpty()) {
            return current;
          }
          return copy(
              current,
              current.status(),
              current.resultArtifactRef(),
              current.certificateArtifactRef(),
              current.verificationReceiptRef(),
              current.outcomeApplicationReceiptRef(),
              mutationReceiptRef,
              current.authority(),
              current.attemptCount(),
              current.producerExecutions(),
              current.verifierExecutions(),
              current.authorityProjections(),
              current.cacheHits(),
              current.cpuSeconds(),
              round,
              current.errorCode(),
              "LEGACY_AUTHORITY_MUTATION_MIGRATED");
        });
  }

  public ComputationExecutionRecord markCompletedReuse(String executionId, int round) {
    return transition(
        executionId,
        current -> {
          if (current.status() != ComputationExecutionStatus.AUTHORITY_APPLIED) {
            return current;
          }
          return copy(
              current,
              current.status(),
              current.resultArtifactRef(),
              current.certificateArtifactRef(),
              current.verificationReceiptRef(),
              current.outcomeApplicationReceiptRef(),
              current.authorityMutationReceiptRef(),
              current.authority(),
              current.attemptCount(),
              current.producerExecutions(),
              current.verifierExecutions(),
              current.authorityProjections(),
              current.cacheHits() + 1,
              current.cpuSeconds(),
              round,
              current.errorCode(),
              "COMPLETED_RESULT_REUSED");
        });
  }

  public ComputationExecutionRecord markTerminal(
      String executionId,
      ComputationExecutionStatus status,
      String errorCode,
      int round) {
    if (status != ComputationExecutionStatus.REJECTED
        && status != ComputationExecutionStatus.DEFERRED
        && status != ComputationExecutionStatus.QUARANTINED
        && status != ComputationExecutionStatus.FAILED) {
      throw new IllegalArgumentException("status is not terminal");
    }
    return transition(
        executionId,
        current ->
            copy(
                current,
                status,
                current.resultArtifactRef(),
                current.certificateArtifactRef(),
                current.verificationReceiptRef(),
                current.outcomeApplicationReceiptRef(),
                current.authorityMutationReceiptRef(),
                current.authority(),
                current.attemptCount(),
                current.producerExecutions(),
                current.verifierExecutions(),
                current.authorityProjections(),
                current.cacheHits(),
                current.cpuSeconds(),
                round,
                errorCode,
                status.name()));
  }

  public Optional<ComputationExecutionRecord> find(String executionId) {
    return Optional.ofNullable(records.get(executionId));
  }

  public List<ComputationExecutionRecord> records() {
    return records.values().stream()
        .sorted(java.util.Comparator.comparing(ComputationExecutionRecord::executionId))
        .toList();
  }

  public ComputationLedger.Usage usage(String routeId) {
    List<ComputationExecutionRecord> routeRecords =
        records.values().stream().filter(value -> value.routeId().equals(routeId)).toList();
    int experiments =
        (int)
            routeRecords.stream()
                .filter(value -> value.status() != ComputationExecutionStatus.REJECTED)
                .filter(value -> value.status() != ComputationExecutionStatus.DEFERRED)
                .count();
    double cpuSeconds =
        routeRecords.stream().mapToDouble(ComputationExecutionRecord::cpuSeconds).sum();
    return new ComputationLedger.Usage(experiments, cpuSeconds);
  }

  public ComputationExecutionSnapshot snapshot() {
    return new ComputationExecutionSnapshot(records(), null);
  }

  public synchronized void restore(ComputationExecutionSnapshot snapshot) {
    records.clear();
    if (snapshot != null) {
      snapshot.records().forEach(value -> records.put(value.executionId(), value));
    }
  }

  public static String stableExecutionId(
      String runId,
      String routeId,
      ExperimentSpec spec,
      ComputationCapabilityDescriptor capability) {
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("run_id", required(runId, "runId"));
    identity.put("route_id", required(routeId, "routeId"));
    identity.put("request_hash", spec.requestHash());
    identity.put("execution_hash", spec.executionHash());
    identity.put("capability_id", capability.capabilityId());
    identity.put("capability_version", capability.capabilityVersion());
    return "computation-" + CanonicalJson.stableHash(identity);
  }

  private ComputationExecutionRecord transition(
      String executionId, UnaryOperator<ComputationExecutionRecord> operation) {
    return records.compute(
        executionId,
        (ignored, current) -> {
          if (current == null) {
            throw new IllegalArgumentException("unknown computation execution: " + executionId);
          }
          return operation.apply(current);
        });
  }

  private static ComputationExecutionRecord copy(
      ComputationExecutionRecord current,
      ComputationExecutionStatus status,
      String resultArtifactRef,
      String certificateArtifactRef,
      String verificationReceiptRef,
      String outcomeApplicationReceiptRef,
      String authorityMutationReceiptRef,
      ComputationVerifiedAuthority authority,
      int attemptCount,
      int producerExecutions,
      int verifierExecutions,
      int authorityProjections,
      int cacheHits,
      double cpuSeconds,
      int round,
      String errorCode,
      String eventCode) {
    if (status == current.status()
        && resultArtifactRef.equals(current.resultArtifactRef())
        && certificateArtifactRef.equals(current.certificateArtifactRef())
        && verificationReceiptRef.equals(current.verificationReceiptRef())
        && outcomeApplicationReceiptRef.equals(current.outcomeApplicationReceiptRef())
        && authorityMutationReceiptRef.equals(current.authorityMutationReceiptRef())
        && authority == current.authority()
        && attemptCount == current.attemptCount()
        && producerExecutions == current.producerExecutions()
        && verifierExecutions == current.verifierExecutions()
        && authorityProjections == current.authorityProjections()
        && cacheHits == current.cacheHits()
        && Double.compare(cpuSeconds, current.cpuSeconds()) == 0
        && errorCode.equals(current.errorCode())) {
      return current;
    }
    int version = current.version() + 1;
    List<ComputationExecutionAuditEvent> history = new ArrayList<>(current.history());
    history.add(new ComputationExecutionAuditEvent(version, status, round, eventCode));
    return new ComputationExecutionRecord(
        current.executionId(),
        current.routeId(),
        current.claimId(),
        current.obligationId(),
        current.requestHash(),
        current.executionHash(),
        current.capabilityId(),
        current.capabilityVersion(),
        current.backend(),
        status,
        current.requestArtifactRef(),
        resultArtifactRef,
        certificateArtifactRef,
        verificationReceiptRef,
        outcomeApplicationReceiptRef,
        authorityMutationReceiptRef,
        authority,
        attemptCount,
        producerExecutions,
        verifierExecutions,
        authorityProjections,
        cacheHits,
        cpuSeconds,
        current.createdRound(),
        Math.max(round, current.lastUpdatedRound()),
        errorCode,
        history,
        version);
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
