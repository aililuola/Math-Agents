package io.github.aililuola.mathproofmesh.strategydiversity;

/** Durable exactly-once record for one typed claim preflight execution. */
public record StrategyPreflightExecutionRecord(
    String executionId,
    String problemHash,
    String strategyId,
    String claimId,
    String planHash,
    String state,
    CriticalClaimPreflightEvidence evidence,
    int startedRound,
    Integer completedRound,
    int executionCount,
    long version) {
  public StrategyPreflightExecutionRecord {
    executionId = StrategySemanticNormalizer.require(executionId, "executionId");
    problemHash = StrategySemanticNormalizer.require(problemHash, "problemHash");
    strategyId = StrategySemanticNormalizer.require(strategyId, "strategyId");
    claimId = StrategySemanticNormalizer.require(claimId, "claimId");
    planHash = StrategySemanticNormalizer.require(planHash, "planHash");
    state = StrategySemanticNormalizer.require(state, "state");
    if (!"started".equals(state) && !"completed".equals(state)) {
      throw new IllegalArgumentException("unsupported preflight execution state");
    }
    if (startedRound < 0
        || completedRound != null && completedRound < startedRound
        || executionCount != 1
        || version < 1L) {
      throw new IllegalArgumentException("invalid preflight execution counters");
    }
    if ("completed".equals(state) != (evidence != null && completedRound != null)) {
      throw new IllegalArgumentException("completed preflight execution requires evidence and round");
    }
  }

  public boolean completed() {
    return "completed".equals(state);
  }
}
