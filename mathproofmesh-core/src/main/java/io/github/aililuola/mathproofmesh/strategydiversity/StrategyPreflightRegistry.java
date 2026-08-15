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
          && !CanonicalJson.stableHash(existing).equals(CanonicalJson.stableHash(plan))) {
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
    String executionId = executionId(problemHash, strategyId, claimId, planHash);
    synchronized (lock) {
      StrategyPreflightExecutionRecord existing = executions.get(executionId);
      if (existing != null) {
        return existing;
      }
      StrategyPreflightExecutionRecord started =
          new StrategyPreflightExecutionRecord(
              executionId,
              problemHash,
              strategyId,
              claimId,
              planHash,
              "started",
              null,
              currentRound,
              null,
              1,
              1L);
      executions.put(executionId, started);
      version++;
      return started;
    }
  }

  public StrategyPreflightExecutionRecord completeExecution(
      String executionId,
      CriticalClaimPreflightEvidence evidence,
      int currentRound) {
    java.util.Objects.requireNonNull(evidence, "evidence");
    synchronized (lock) {
      StrategyPreflightExecutionRecord existing = executions.get(executionId);
      if (existing == null) {
        throw new IllegalStateException("preflight execution was not reserved");
      }
      if (existing.completed()) {
        if (!CanonicalJson.stableHash(existing.evidence())
            .equals(CanonicalJson.stableHash(evidence))) {
          throw new IllegalStateException("completed preflight evidence cannot change");
        }
        return existing;
      }
      StrategyPreflightExecutionRecord completed =
          new StrategyPreflightExecutionRecord(
              existing.executionId(),
              existing.problemHash(),
              existing.strategyId(),
              existing.claimId(),
              existing.planHash(),
              "completed",
              evidence,
              existing.startedRound(),
              currentRound,
              existing.executionCount(),
              existing.version() + 1L);
      executions.put(executionId, completed);
      version++;
      return completed;
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
                && !CanonicalJson.stableHash(existing).equals(CanonicalJson.stableHash(plan))) {
              throw new IllegalStateException("durable preflight plan changed during rollback");
            }
          });
      source.executions().forEach(
          (executionId, execution) -> {
            StrategyPreflightExecutionRecord existing = executions.get(executionId);
            if (existing == null || existing.version() < execution.version()) {
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
}
