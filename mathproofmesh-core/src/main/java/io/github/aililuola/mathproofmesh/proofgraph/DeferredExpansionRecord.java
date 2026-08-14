package io.github.aililuola.mathproofmesh.proofgraph;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Audit-preserving record for a proposal deferred by capacity or focused recovery. */
public record DeferredExpansionRecord(
    String deferredId,
    String problemHash,
    int round,
    String routeId,
    String obligationId,
    String canonicalTargetId,
    FocusedRecoveryActionType actionType,
    ObligationOccurrenceSchedulingState schedulingState,
    String reason,
    DeferredExpansionStatus status,
    int lastEvaluatedRound,
    int reactivatedRound,
    String reactivationReason,
    String reactivatedTaskId,
    int retiredRound,
    String retirementReason,
    long version) {

  public DeferredExpansionRecord {
    deferredId = require(deferredId, "deferredId");
    problemHash = require(problemHash, "problemHash");
    routeId = normalize(routeId);
    obligationId = normalize(obligationId);
    canonicalTargetId = normalize(canonicalTargetId);
    actionType = java.util.Objects.requireNonNull(actionType, "actionType");
    schedulingState = java.util.Objects.requireNonNull(schedulingState, "schedulingState");
    reason = require(reason, "reason");
    boolean legacy = status == null;
    status = legacy ? DeferredExpansionStatus.DEFERRED : status;
    lastEvaluatedRound = legacy ? round : lastEvaluatedRound;
    reactivatedRound = legacy ? -1 : reactivatedRound;
    reactivationReason = normalize(reactivationReason);
    reactivatedTaskId = normalize(reactivatedTaskId);
    retiredRound = legacy ? -1 : retiredRound;
    retirementReason = normalize(retirementReason);
    if (round < 0 || version < 0 || schedulingState == ObligationOccurrenceSchedulingState.ACTIVE) {
      throw new IllegalArgumentException("deferred expansion must have deferred state and counters");
    }
    if (lastEvaluatedRound < round) {
      throw new IllegalArgumentException("lastEvaluatedRound cannot precede the deferral");
    }
    switch (status) {
      case DEFERRED -> {
        if (reactivatedRound != -1 || retiredRound != -1) {
          throw new IllegalArgumentException("deferred expansion cannot contain terminal rounds");
        }
      }
      case REACTIVATED -> {
        if (reactivatedRound < round
            || reactivationReason.isEmpty()
            || reactivatedTaskId.isEmpty()
            || retiredRound != -1) {
          throw new IllegalArgumentException("reactivated expansion requires its task metadata");
        }
      }
      case SATISFIED_BY_ACTIVE_TARGET -> {
        if (reactivatedRound < round || reactivationReason.isEmpty() || retiredRound != -1) {
          throw new IllegalArgumentException("satisfied expansion requires activation metadata");
        }
      }
      case RETIRED -> {
        if (retiredRound < round || retirementReason.isEmpty() || reactivatedRound != -1) {
          throw new IllegalArgumentException("retired expansion requires retirement metadata");
        }
      }
    }
  }

  public DeferredExpansionRecord(
      String deferredId,
      String problemHash,
      int round,
      String routeId,
      String obligationId,
      String canonicalTargetId,
      FocusedRecoveryActionType actionType,
      ObligationOccurrenceSchedulingState schedulingState,
      String reason,
      long version) {
    this(
        deferredId,
        problemHash,
        round,
        routeId,
        obligationId,
        canonicalTargetId,
        actionType,
        schedulingState,
        reason,
        DeferredExpansionStatus.DEFERRED,
        round,
        -1,
        "",
        "",
        -1,
        "",
        version);
  }

  @JsonCreator
  public static DeferredExpansionRecord fromJson(
      @JsonProperty("deferredId") String deferredId,
      @JsonProperty("problemHash") String problemHash,
      @JsonProperty("round") int round,
      @JsonProperty("routeId") String routeId,
      @JsonProperty("obligationId") String obligationId,
      @JsonProperty("canonicalTargetId") String canonicalTargetId,
      @JsonProperty("actionType") FocusedRecoveryActionType actionType,
      @JsonProperty("schedulingState") ObligationOccurrenceSchedulingState schedulingState,
      @JsonProperty("reason") String reason,
      @JsonProperty("status") DeferredExpansionStatus status,
      @JsonProperty("lastEvaluatedRound") Integer lastEvaluatedRound,
      @JsonProperty("reactivatedRound") Integer reactivatedRound,
      @JsonProperty("reactivationReason") String reactivationReason,
      @JsonProperty("reactivatedTaskId") String reactivatedTaskId,
      @JsonProperty("retiredRound") Integer retiredRound,
      @JsonProperty("retirementReason") String retirementReason,
      @JsonProperty("version") long version) {
    boolean legacy = status == null;
    return new DeferredExpansionRecord(
        deferredId,
        problemHash,
        round,
        routeId,
        obligationId,
        canonicalTargetId,
        actionType,
        schedulingState,
        reason,
        status,
        legacy || lastEvaluatedRound == null ? round : lastEvaluatedRound,
        legacy || reactivatedRound == null ? -1 : reactivatedRound,
        reactivationReason,
        reactivatedTaskId,
        legacy || retiredRound == null ? -1 : retiredRound,
        retirementReason,
        version);
  }

  DeferredExpansionRecord evaluated(int currentRound) {
    if (status != DeferredExpansionStatus.DEFERRED || currentRound < lastEvaluatedRound) {
      throw new IllegalStateException("deferred evaluation must be monotonic");
    }
    if (currentRound == lastEvaluatedRound) {
      return this;
    }
    return copy(
        status, currentRound, reactivatedRound, reactivationReason, reactivatedTaskId,
        retiredRound, retirementReason);
  }

  DeferredExpansionRecord reactivated(int currentRound, String transitionReason, String taskId) {
    return copy(
        DeferredExpansionStatus.REACTIVATED,
        currentRound,
        currentRound,
        require(transitionReason, "reactivationReason"),
        require(taskId, "reactivatedTaskId"),
        -1,
        "");
  }

  DeferredExpansionRecord satisfied(int currentRound, String transitionReason) {
    return copy(
        DeferredExpansionStatus.SATISFIED_BY_ACTIVE_TARGET,
        currentRound,
        currentRound,
        require(transitionReason, "reactivationReason"),
        "",
        -1,
        "");
  }

  DeferredExpansionRecord retired(int currentRound, String transitionReason) {
    return copy(
        DeferredExpansionStatus.RETIRED,
        currentRound,
        -1,
        "",
        "",
        currentRound,
        require(transitionReason, "retirementReason"));
  }

  private DeferredExpansionRecord copy(
      DeferredExpansionStatus nextStatus,
      int evaluationRound,
      int activationRound,
      String activationReason,
      String taskId,
      int terminalRound,
      String terminalReason) {
    return new DeferredExpansionRecord(
        deferredId,
        problemHash,
        round,
        routeId,
        obligationId,
        canonicalTargetId,
        actionType,
        schedulingState,
        reason,
        nextStatus,
        evaluationRound,
        activationRound,
        activationReason,
        taskId,
        terminalRound,
        terminalReason,
        version + 1);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip();
  }

  private static String require(String value, String field) {
    String normalized = normalize(value);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
