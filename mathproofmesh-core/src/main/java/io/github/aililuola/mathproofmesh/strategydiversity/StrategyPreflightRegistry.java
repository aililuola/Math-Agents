package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.StrategyPreflightPlan;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class StrategyPreflightRegistry {
  private final Object lock = new Object();
  private final Map<String, StrategyPreflightReport> reports = new LinkedHashMap<>();
  private final Map<String, StrategyPreflightPlan> plans = new LinkedHashMap<>();
  private final Map<String, StrategyPreflightExecutionRecord> executions =
      new LinkedHashMap<>();
  private long version;

  public StrategyPreflightReport record(StrategyPreflightReport report) {
    java.util.Objects.requireNonNull(report, "report");
    synchronized (lock) {
      StrategyPreflightReport existing = reports.get(report.strategyId());
      if (existing != null
          && !StrategySemanticNormalizer.hashEquals(existing.reportHash(), report.reportHash())) {
        throw new IllegalStateException("preflight result cannot change for a captured candidate");
      }
      if (existing == null) {
        reports.put(report.strategyId(), report);
        version++;
        return report;
      }
      return existing;
    }
  }

  public Optional<StrategyPreflightReport> find(String strategyId) {
    synchronized (lock) {
      return Optional.ofNullable(reports.get(strategyId));
    }
  }

  public StrategyPreflightPlan recordPlan(StrategyPreflightPlan plan) {
    java.util.Objects.requireNonNull(plan, "plan");
    synchronized (lock) {
      StrategyPreflightPlan existing = plans.get(plan.strategyId());
      if (existing != null
          && !StrategySemanticNormalizer.hashEquals(
              CanonicalJson.stableHash(existing), CanonicalJson.stableHash(plan))) {
        throw new IllegalStateException("preflight plan cannot change for a captured candidate");
      }
      if (existing == null) {
        plans.put(plan.strategyId(), plan);
        version++;
        return plan;
      }
      return existing;
    }
  }

  public Optional<StrategyPreflightPlan> plan(String strategyId) {
    synchronized (lock) {
      return Optional.ofNullable(plans.get(strategyId));
    }
  }

  public StrategyPreflightExecutionRecord beginExecution(
      String problemHash,
      String strategyId,
      String claimId,
      String planHash,
      int currentRound) {
    StrategyPreflightExecutionRecord reserved =
        reserveExecution(
            problemHash,
            strategyId,
            claimId,
            planHash,
            "legacy-preflight-action:"
                + executionId(problemHash, strategyId, claimId, planHash),
            planHash,
            currentRound);
    return startExecution(reserved.executionId());
  }

  public StrategyPreflightExecutionRecord reserveExecution(
      String problemHash,
      String strategyId,
      String claimId,
      String planHash,
      String actionKey,
      String typedInputHash,
      int currentRound) {
    String executionId = executionId(problemHash, strategyId, claimId, planHash);
    synchronized (lock) {
      StrategyPreflightExecutionRecord existing = executions.get(executionId);
      if (existing != null) {
        if (!existing.actionKey().equals(actionKey)
            || !StrategySemanticNormalizer.hashEquals(
                existing.typedInputHash(), typedInputHash)) {
          throw new IllegalStateException("preflight execution binding cannot change");
        }
        return existing;
      }
      StrategyPreflightExecutionRecord reserved =
          new StrategyPreflightExecutionRecord(
              executionId,
              problemHash,
              strategyId,
              claimId,
              planHash,
              actionKey,
              typedInputHash,
              "",
              "",
              StrategyPreflightExecutionStatus.RESERVED,
              null,
              currentRound,
              null,
              null,
              0,
              1L);
      executions.put(executionId, reserved);
      version++;
      return reserved;
    }
  }

  public StrategyPreflightExecutionRecord startExecution(String executionId) {
    synchronized (lock) {
      StrategyPreflightExecutionRecord existing = requireExecution(executionId);
      if (existing.status() == StrategyPreflightExecutionStatus.RUNNING) {
        return existing;
      }
      if (existing.status() != StrategyPreflightExecutionStatus.RESERVED) {
        throw new IllegalStateException("only a reserved preflight execution can start");
      }
      StrategyPreflightExecutionRecord running =
          copy(
              existing,
              StrategyPreflightExecutionStatus.RUNNING,
              null,
              "",
              "",
              null,
              null,
              1);
      executions.put(executionId, running);
      version++;
      return running;
    }
  }

  public StrategyPreflightExecutionRecord recordDurableResult(
      String executionId,
      CriticalClaimPreflightEvidence evidence,
      String resultArtifactRef,
      String replayHash,
      int currentRound) {
    java.util.Objects.requireNonNull(evidence, "evidence");
    synchronized (lock) {
      StrategyPreflightExecutionRecord existing = requireExecution(executionId);
      if (existing.resultDurable()) {
        requireSameEvidence(existing, evidence, resultArtifactRef, replayHash);
        return existing;
      }
      if (existing.status() != StrategyPreflightExecutionStatus.RUNNING) {
        throw new IllegalStateException("preflight result requires a running execution");
      }
      StrategyPreflightExecutionRecord durable =
          copy(
              existing,
              StrategyPreflightExecutionStatus.RESULT_DURABLE,
              evidence,
              resultArtifactRef,
              replayHash,
              currentRound,
              null,
              1);
      executions.put(executionId, durable);
      version++;
      return durable;
    }
  }

  public StrategyPreflightExecutionRecord completeExecution(
      String executionId,
      CriticalClaimPreflightEvidence evidence,
      int currentRound) {
    java.util.Objects.requireNonNull(evidence, "evidence");
    synchronized (lock) {
      StrategyPreflightExecutionRecord existing = requireExecution(executionId);
      if (existing.completed()) {
        requireSameEvidence(
            existing, evidence, existing.resultArtifactRef(), existing.replayHash());
        return existing;
      }
      if (existing.status() == StrategyPreflightExecutionStatus.RUNNING) {
        existing =
            recordDurableResult(
                executionId,
                evidence,
                evidence.evidenceRefs().stream()
                    .findFirst()
                    .orElse("preflight-result:" + executionId),
                CanonicalJson.stableHash(evidence),
                currentRound);
      }
      if (existing.status() != StrategyPreflightExecutionStatus.RESULT_DURABLE) {
        throw new IllegalStateException("preflight completion requires a durable result");
      }
      requireSameEvidence(
          existing, evidence, existing.resultArtifactRef(), existing.replayHash());
      StrategyPreflightExecutionRecord completed =
          copy(
              existing,
              StrategyPreflightExecutionStatus.COMPLETED,
              evidence,
              existing.resultArtifactRef(),
              existing.replayHash(),
              existing.resultRound(),
              currentRound,
              existing.executionCount());
      executions.put(executionId, completed);
      version++;
      return completed;
    }
  }

  public StrategyPreflightExecutionRecord abortExecution(String executionId) {
    synchronized (lock) {
      StrategyPreflightExecutionRecord existing = requireExecution(executionId);
      if (existing.status() == StrategyPreflightExecutionStatus.ABORTED) {
        return existing;
      }
      if (existing.resultDurable()) {
        throw new IllegalStateException("durable preflight result cannot be aborted");
      }
      StrategyPreflightExecutionRecord aborted =
          copy(
              existing,
              StrategyPreflightExecutionStatus.ABORTED,
              null,
              "",
              "",
              null,
              null,
              existing.executionCount());
      executions.put(executionId, aborted);
      version++;
      return aborted;
    }
  }

  public Optional<StrategyPreflightExecutionRecord> execution(String executionId) {
    synchronized (lock) {
      return Optional.ofNullable(executions.get(executionId));
    }
  }

  public int executionCount() {
    synchronized (lock) {
      return executions.values().stream()
          .mapToInt(StrategyPreflightExecutionRecord::executionCount)
          .sum();
    }
  }

  public void mergeDurable(StrategyPreflightSnapshot snapshot) {
    StrategyPreflightSnapshot source =
        snapshot == null ? StrategyPreflightSnapshot.empty() : snapshot;
    synchronized (lock) {
      source.plans().forEach(
          (strategyId, plan) -> {
            StrategyPreflightPlan existing = plans.putIfAbsent(strategyId, plan);
            if (existing != null
                && !StrategySemanticNormalizer.hashEquals(
                    CanonicalJson.stableHash(existing), CanonicalJson.stableHash(plan))) {
              throw new IllegalStateException("durable preflight plan changed during rollback");
            }
          });
      source.executions().forEach(
          (executionId, execution) -> {
            StrategyPreflightExecutionRecord existing = executions.get(executionId);
            if (existing != null) {
              requireSameExecutionBinding(existing, execution);
            }
            if (existing == null || canAdvance(existing, execution)) {
              executions.put(executionId, execution);
            }
          });
      version = Math.max(version, source.version());
    }
  }

  public StrategyPreflightSnapshot snapshot() {
    synchronized (lock) {
      return snapshotUnsafe();
    }
  }

  public String registryHash() {
    synchronized (lock) {
      return CanonicalJson.stableHash(snapshotUnsafe());
    }
  }

  public static StrategyPreflightRegistry restore(StrategyPreflightSnapshot snapshot) {
    StrategyPreflightRegistry registry = new StrategyPreflightRegistry();
    StrategyPreflightSnapshot source =
        snapshot == null ? StrategyPreflightSnapshot.empty() : snapshot;
    synchronized (registry.lock) {
      registry.reports.putAll(source.reports());
      registry.plans.putAll(source.plans());
      registry.executions.putAll(source.executions());
      registry.version = source.version();
    }
    return registry;
  }

  private StrategyPreflightSnapshot snapshotUnsafe() {
    return new StrategyPreflightSnapshot(
        StrategyPreflightSnapshot.CURRENT_SCHEMA_VERSION,
        reports,
        plans,
        executions,
        version);
  }

  public static String executionId(
      String problemHash, String strategyId, String claimId, String planHash) {
    return "strategy-preflight-"
        + StrategySemanticNormalizer.hash(
                java.util.List.of(problemHash, strategyId, claimId, planHash))
            .substring(0, 24);
  }

  private StrategyPreflightExecutionRecord requireExecution(String executionId) {
    StrategyPreflightExecutionRecord existing = executions.get(executionId);
    if (existing == null) {
      throw new IllegalStateException("preflight execution was not reserved");
    }
    return existing;
  }

  private static void requireSameEvidence(
      StrategyPreflightExecutionRecord existing,
      CriticalClaimPreflightEvidence evidence,
      String resultArtifactRef,
      String replayHash) {
    if (!StrategySemanticNormalizer.hashEquals(
            CanonicalJson.stableHash(existing.evidence()), CanonicalJson.stableHash(evidence))
        || !existing.resultArtifactRef().equals(resultArtifactRef)
        || !StrategySemanticNormalizer.hashEquals(existing.replayHash(), replayHash)) {
      throw new IllegalStateException("durable preflight evidence cannot change");
    }
  }

  private static StrategyPreflightExecutionRecord copy(
      StrategyPreflightExecutionRecord source,
      StrategyPreflightExecutionStatus status,
      CriticalClaimPreflightEvidence evidence,
      String resultArtifactRef,
      String replayHash,
      Integer resultRound,
      Integer completedRound,
      int executionCount) {
    return new StrategyPreflightExecutionRecord(
        source.executionId(),
        source.problemHash(),
        source.strategyId(),
        source.claimId(),
        source.planHash(),
        source.actionKey(),
        source.typedInputHash(),
        resultArtifactRef,
        replayHash,
        status,
        evidence,
        source.startedRound(),
        resultRound,
        completedRound,
        executionCount,
        source.version() + 1L);
  }

  private static int statusRank(StrategyPreflightExecutionStatus status) {
    return switch (status) {
      case RESERVED -> 0;
      case RUNNING, ABORTED -> 1;
      case RESULT_DURABLE -> 2;
      case COMPLETED -> 3;
    };
  }

  private static boolean canAdvance(
      StrategyPreflightExecutionRecord existing,
      StrategyPreflightExecutionRecord incoming) {
    int existingRank = statusRank(existing.status());
    int incomingRank = statusRank(incoming.status());
    return incomingRank >= existingRank
        && (incoming.version() > existing.version()
            || incoming.version() == existing.version() && incomingRank > existingRank);
  }

  private static void requireSameExecutionBinding(
      StrategyPreflightExecutionRecord existing,
      StrategyPreflightExecutionRecord incoming) {
    if (!StrategySemanticNormalizer.hashEquals(
            existing.problemHash(), incoming.problemHash())
        || !existing.strategyId().equals(incoming.strategyId())
        || !existing.claimId().equals(incoming.claimId())
        || !StrategySemanticNormalizer.hashEquals(existing.planHash(), incoming.planHash())
        || !existing.actionKey().equals(incoming.actionKey())
        || !StrategySemanticNormalizer.hashEquals(
            existing.typedInputHash(), incoming.typedInputHash())) {
      throw new IllegalStateException("preflight execution binding changed during durable merge");
    }
  }
}
