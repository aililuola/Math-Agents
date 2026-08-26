package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.agent.StructuredAgentRunner;
import io.github.aililuola.mathproofmesh.contract.ActionKind;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.orchestration.BudgetActionCandidate;
import io.github.aililuola.mathproofmesh.orchestration.BudgetBucket;
import io.github.aililuola.mathproofmesh.orchestration.BudgetEnvelope;
import io.github.aililuola.mathproofmesh.orchestration.BudgetResourceVector;
import io.github.aililuola.mathproofmesh.orchestration.BudgetStateSnapshot;
import io.github.aililuola.mathproofmesh.orchestration.EvidenceAwareBudgetDecision;
import io.github.aililuola.mathproofmesh.orchestration.TargetMechanismKey;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Applies scheduler choices only after their physical resource envelope is durable. */
final class DesktopBudgetScheduler {
  private final String runId;
  private final DesktopBudgetRuntime runtime;
  private final StructuredAgentRunner runner;
  private final Host host;

  DesktopBudgetScheduler(
      String runId, DesktopBudgetRuntime runtime, StructuredAgentRunner runner, Host host) {
    this.runId = require(runId, "runId");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.runner = Objects.requireNonNull(runner, "runner");
    this.host = Objects.requireNonNull(host, "host");
  }

  boolean apply(EvidenceAwareBudgetDecision decision) {
    BudgetActionCandidate action = decision.selectedActions().stream().findFirst().orElse(null);
    if (action == null
        || action.action() == ActionKind.STOP
        || action.action() == ActionKind.SYNTHESIZE) {
      return false;
    }
    BudgetEnvelope envelope =
        reserve(
            "scheduler-epoch-" + host.currentRound(),
            action.targetId().isBlank()
                ? "scheduler-" + action.action().name().toLowerCase(Locale.ROOT)
                : action.targetId(),
            decision.identity().decisionHash(),
            action.bucket(),
            action.resourceEstimate(),
            host.budgetTarget(action),
            action.action().name());
    if (envelope == null) {
      return false;
    }
    boolean applied = host.execute(action);
    if (!applied) {
      finish();
    }
    host.event(action.action().name(), applied, decision.rationale());
    return applied;
  }

  boolean reserveInitial(int pendingRoutes, BudgetStateSnapshot state) {
    if (runtime.hasActiveEnvelope() || pendingRoutes == 0) {
      return true;
    }
    BudgetResourceVector estimate = runtime.estimateInitialExploration(pendingRoutes);
    String decisionId =
        CanonicalJson.stableHash(
            Map.of(
                "budget_state_hash", state.snapshotHash(),
                "action", "INITIAL_ROUTE_EXPLORATION",
                "pending_routes", pendingRoutes,
                "resource_estimate", estimate));
    return reserve(
            state.researchEpochId(),
            "initial-route-exploration",
            decisionId,
            BudgetBucket.BREADTH,
            estimate,
            new TargetMechanismKey(
                "initial-route-exploration",
                "initial-portfolio",
                ActionKind.WIDEN,
                "initial-portfolio"),
            "INITIAL_ROUTE_EXPLORATION")
        != null;
  }

  boolean reserveExhaustedPortfolioRecovery(BudgetStateSnapshot state) {
    Objects.requireNonNull(state, "state");
    BudgetEnvelope active = runtime.activeEnvelope().orElse(null);
    if (active != null) {
      return active.epochId().equals(state.researchEpochId())
          && "exhausted-portfolio-recovery".equals(active.workItemId());
    }
    BudgetResourceVector estimate = runtime.estimate(ActionKind.WIDEN);
    String decisionId =
        CanonicalJson.stableHash(
            Map.of(
                "budget_state_hash", state.snapshotHash(),
                "action", "EXHAUSTED_PORTFOLIO_RECOVERY",
                "resource_estimate", estimate));
    return reserve(
            state.researchEpochId(),
            "exhausted-portfolio-recovery",
            decisionId,
            BudgetBucket.BREADTH,
            estimate,
            new TargetMechanismKey(
                "exhausted-portfolio-recovery",
                "scheduler-portfolio-gap",
                ActionKind.WIDEN,
                "scheduler-portfolio-gap"),
            "WIDEN")
        != null;
  }

  boolean reserveProofTaskBatch(
      List<ProofTaskBudgetInput> tasks, String authorityHash) {
    if (tasks.isEmpty()) {
      return false;
    }
    BudgetResourceVector resources = BudgetResourceVector.zero();
    boolean revision = false;
    for (ProofTaskBudgetInput task : tasks) {
      ActionKind action =
          "REVISE".equals(task.requestedAction()) ? ActionKind.REVISE : ActionKind.DEEPEN;
      resources = resources.plus(runtime.estimate(action));
      revision |= action == ActionKind.REVISE;
    }
    List<String> taskIds = tasks.stream().map(ProofTaskBudgetInput::taskId).toList();
    String decisionId =
        CanonicalJson.stableHash(
            Map.of(
                "run_id", runId,
                "round", host.currentRound(),
                "task_ids", taskIds,
                "resource_estimate", resources,
                "authority_hash", authorityHash));
    String batchSignature = CanonicalJson.stableHash(taskIds);
    return reserve(
            "scheduler-epoch-" + host.currentRound(),
            "proof-task-batch-" + decisionId.substring(0, 20),
            decisionId,
            revision ? BudgetBucket.REVISION : BudgetBucket.DEPTH,
            resources,
            new TargetMechanismKey(
                "proof-task-batch",
                batchSignature,
                revision ? ActionKind.REVISE : ActionKind.DEEPEN,
                batchSignature),
            "PROOF_TASK_BATCH")
        != null;
  }

  boolean reserveClaimCourtBatch(List<String> claimIds, String authorityHash) {
    if (claimIds.isEmpty()) {
      return false;
    }
    List<String> stableClaimIds = claimIds.stream().distinct().sorted().toList();
    String stableAuthorityHash = require(authorityHash, "authorityHash");
    String batchSignature =
        CanonicalJson.stableHash(
            Map.of("claim_ids", stableClaimIds, "authority_hash", stableAuthorityHash));
    String epochId = "claim-court-epoch-" + host.currentRound();
    String workItemId = "claim-court-batch-" + batchSignature.substring(0, 20);
    BudgetEnvelope active = runtime.activeEnvelope().orElse(null);
    if (active != null
        && active.epochId().equals(epochId)
        && active.workItemId().equals(workItemId)) {
      return true;
    }
    if (active != null) {
      finish();
    }
    BudgetResourceVector resources = runtime.availableExplorationCapacity();
    if (resources.calls() < 1L) {
      host.event("CLAIM_COURT", false, "ACTION_BUDGET_ENVELOPE_EXHAUSTED");
      return false;
    }
    String decisionId =
        CanonicalJson.stableHash(
            Map.of(
                "run_id", runId,
                "round", host.currentRound(),
                "claim_ids", stableClaimIds,
                "resource_capacity", resources,
                "authority_hash", stableAuthorityHash,
                "budget_envelope_frontier_hash",
                    CanonicalJson.stableHash(runtime.envelopeSnapshot())));
    return reserve(
            epochId,
            workItemId,
            decisionId,
            BudgetBucket.DEPTH,
            resources,
            new TargetMechanismKey(
                "claim-court", batchSignature, ActionKind.DEEPEN, batchSignature),
            "CLAIM_COURT")
        != null;
  }

  void finish() {
    runtime
        .finishActiveEnvelope(host.gainBaseline(), host.noGainExhaustionThreshold())
        .ifPresent(runner::clearRunBudgetEnvelope);
  }

  void restore(DesktopSolveCheckpoint checkpoint) {
    BudgetEnvelope envelope = runtime.activeEnvelope().orElse(null);
    if (envelope == null) {
      return;
    }
    TargetMechanismKey target = host.restoredBudgetTarget(checkpoint, envelope);
    runtime.restoreActiveEnvelope(target, host.gainBaseline());
    runner.activateRunBudgetEnvelope(envelope.envelopeId());
  }

  private BudgetEnvelope reserve(
      String epochId,
      String workItemId,
      String decisionId,
      BudgetBucket bucket,
      BudgetResourceVector resources,
      TargetMechanismKey target,
      String actionName) {
    BudgetEnvelope envelope;
    try {
      envelope =
          runtime.reserveAndActivate(
              epochId,
              workItemId,
              decisionId,
              bucket,
              resources,
              target,
              host.gainBaseline());
    } catch (IllegalStateException failure) {
      if (budgetAdmissionRefused(failure)) {
        host.event(actionName, false, failure.getMessage());
        return null;
      }
      throw failure;
    }
    host.persistReservation();
    runner.activateRunBudgetEnvelope(envelope.envelopeId());
    return envelope;
  }

  private static boolean budgetAdmissionRefused(IllegalStateException failure) {
    return List.of(
            "ACTION_BUDGET_ENVELOPE_EXHAUSTED", "ACTUAL_USAGE_OVERRUN", "UNPRICED_PROVIDER")
        .contains(Objects.toString(failure.getMessage(), ""));
  }

  private static String require(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }

  record ProofTaskBudgetInput(String taskId, String requestedAction) {
    ProofTaskBudgetInput {
      taskId = require(taskId, "taskId");
      requestedAction = require(requestedAction, "requestedAction");
    }
  }

  interface Host {
    int currentRound();

    TargetMechanismKey budgetTarget(BudgetActionCandidate action);

    TargetMechanismKey restoredBudgetTarget(
        DesktopSolveCheckpoint checkpoint, BudgetEnvelope envelope);

    DesktopBudgetRuntime.GainBaseline gainBaseline();

    int noGainExhaustionThreshold();

    boolean execute(BudgetActionCandidate action);

    void persistReservation();

    void event(String action, boolean applied, String detail);
  }
}
