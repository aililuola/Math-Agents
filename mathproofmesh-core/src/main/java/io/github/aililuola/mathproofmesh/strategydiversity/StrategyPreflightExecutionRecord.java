package io.github.aililuola.mathproofmesh.strategydiversity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;

/** Crash-recoverable exactly-once record for one typed claim preflight execution. */
public record StrategyPreflightExecutionRecord(
    String executionId,
    String problemHash,
    String strategyId,
    String claimId,
    String planHash,
    String actionKey,
    String typedInputHash,
    String resultArtifactRef,
    String replayHash,
    @JsonProperty("state") StrategyPreflightExecutionStatus status,
    CriticalClaimPreflightEvidence evidence,
    int startedRound,
    Integer resultRound,
    Integer completedRound,
    int executionCount,
    long version) {
  public StrategyPreflightExecutionRecord {
    executionId = StrategySemanticNormalizer.require(executionId, "executionId");
    problemHash = StrategySemanticNormalizer.require(problemHash, "problemHash");
    strategyId = StrategySemanticNormalizer.require(strategyId, "strategyId");
    claimId = StrategySemanticNormalizer.require(claimId, "claimId");
    planHash = StrategySemanticNormalizer.require(planHash, "planHash");
    actionKey =
        actionKey == null || actionKey.isBlank()
            ? "legacy-preflight-action:" + executionId
            : actionKey.strip();
    typedInputHash =
        typedInputHash == null || typedInputHash.isBlank() ? planHash : typedInputHash.strip();
    resultArtifactRef = resultArtifactRef == null ? "" : resultArtifactRef.strip();
    replayHash = replayHash == null ? "" : replayHash.strip();
    status = java.util.Objects.requireNonNull(status, "status");
    if (status == StrategyPreflightExecutionStatus.COMPLETED && evidence != null) {
      resultArtifactRef =
          resultArtifactRef.isBlank()
              ? evidence.evidenceRefs().stream().findFirst().orElse("legacy:" + executionId)
              : resultArtifactRef;
      replayHash = replayHash.isBlank() ? CanonicalJson.stableHash(evidence) : replayHash;
      resultRound = resultRound == null ? completedRound : resultRound;
    }
    validateCounters(
        status,
        evidence,
        startedRound,
        resultRound,
        completedRound,
        executionCount,
        version,
        resultArtifactRef,
        replayHash);
  }

  public StrategyPreflightExecutionRecord(
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
    this(
        executionId,
        problemHash,
        strategyId,
        claimId,
        planHash,
        "legacy-preflight-action:" + executionId,
        planHash,
        evidence == null
            ? ""
            : evidence.evidenceRefs().stream().findFirst().orElse("legacy:" + executionId),
        evidence == null ? "" : CanonicalJson.stableHash(evidence),
        StrategyPreflightExecutionStatus.fromValue(state),
        evidence,
        startedRound,
        evidence == null ? null : completedRound,
        completedRound,
        executionCount,
        version);
  }

  public boolean completed() {
    return status == StrategyPreflightExecutionStatus.COMPLETED;
  }

  public boolean resultDurable() {
    return status == StrategyPreflightExecutionStatus.RESULT_DURABLE || completed();
  }

  private static void validateCounters(
      StrategyPreflightExecutionStatus status,
      CriticalClaimPreflightEvidence evidence,
      int startedRound,
      Integer resultRound,
      Integer completedRound,
      int executionCount,
      long version,
      String resultArtifactRef,
      String replayHash) {
    if (startedRound < 0
        || resultRound != null && resultRound < startedRound
        || completedRound != null
            && (completedRound < startedRound
                || resultRound != null && completedRound < resultRound)
        || executionCount < 0
        || executionCount > 1
        || version < 1L) {
      throw new IllegalArgumentException("invalid preflight execution counters");
    }
    boolean hasResult = evidence != null && resultRound != null;
    boolean durable =
        status == StrategyPreflightExecutionStatus.RESULT_DURABLE
            || status == StrategyPreflightExecutionStatus.COMPLETED;
    if (durable
        != (hasResult && !resultArtifactRef.isBlank() && !replayHash.isBlank())) {
      throw new IllegalArgumentException("durable preflight status requires a replayable result");
    }
    boolean reservedCountInvalid =
        status == StrategyPreflightExecutionStatus.RESERVED && executionCount != 0;
    boolean executedCountInvalid =
        status != StrategyPreflightExecutionStatus.RESERVED
            && status != StrategyPreflightExecutionStatus.ABORTED
            && executionCount != 1;
    if (reservedCountInvalid
        || executedCountInvalid
        || (status == StrategyPreflightExecutionStatus.COMPLETED) != (completedRound != null)
        || (status != StrategyPreflightExecutionStatus.COMPLETED && completedRound != null)) {
      throw new IllegalArgumentException("preflight execution phase has inconsistent counters");
    }
    if (!durable && evidence != null) {
      throw new IllegalArgumentException("non-durable preflight phase cannot carry evidence");
    }
  }
}
