package io.github.aililuola.mathproofmesh.runstate;

import java.util.ArrayList;
import java.util.List;

public final class RunStateReconciler {
  private final RunUsageReconciler usageReconciler;
  private final RunStateTransitionPolicy transitionPolicy;

  public RunStateReconciler() {
    this(new RunUsageReconciler(), new RunStateTransitionPolicy());
  }

  public RunStateReconciler(
      RunUsageReconciler usageReconciler, RunStateTransitionPolicy transitionPolicy) {
    this.usageReconciler = java.util.Objects.requireNonNull(usageReconciler, "usageReconciler");
    this.transitionPolicy =
        java.util.Objects.requireNonNull(transitionPolicy, "transitionPolicy");
  }

  public RunStateReconciliationResult reconcile(RunStateEvidenceBundle evidence) {
    RunStateSnapshot previous = evidence.previousState();
    RunUsageSnapshot priorUsage =
        previous == null ? RunUsageSnapshot.empty() : previous.authority().usage();
    RunUsageReconciliationResult usage =
        usageReconciler.reconcile(evidence.usageEvidence(), priorUsage);
    List<RunStateConflict> conflicts = new ArrayList<>();
    for (RunUsageConflict conflict : usage.conflicts()) {
      conflicts.add(
          new RunStateConflict(conflict.code(), conflict.providerRequestId(), conflict.evidenceRefs()));
    }
    RunMathematicalStatus math = deriveMath(evidence.mathematicalProgress());
    if (previous != null
        && RunStateTransitionPolicy.mathRank(math)
            < RunStateTransitionPolicy.mathRank(previous.authority().mathStatus())) {
      math = previous.authority().mathStatus();
    }
    RunCampaignStatus campaign =
        deriveCampaign(
            evidence.executionStatus(), math, evidence.semanticCheckpointPresent(),
            evidence.semanticCheckpointTerminal());
    RunTerminalReason terminalReason =
        deriveTerminalReason(evidence.terminalReason(), evidence.executionStatus(), math, campaign);
    long sequence = previous == null ? 0L : previous.authority().authoritySequence() + 1L;
    long version = previous == null ? 0L : previous.authority().version() + 1L;
    RunAuthoritySnapshot authority =
        new RunAuthoritySnapshot(
            evidence.runId(),
            evidence.problemHash(),
            evidence.executionAttemptId(),
            sequence,
            evidence.executionStatus(),
            math,
            usage.status(),
            campaign,
            terminalReason,
            evidence.currentStage(),
            campaign == RunCampaignStatus.RECOVERABLE,
            usage.usage(),
            evidence.mathematicalProgress(),
            evidence.semanticCheckpointRef(),
            evidence.semanticCheckpointHash(),
            evidence.proofGraphHash(),
            null,
            version);
    RunProjectionSnapshot projection =
        evidence.projection() == null
            ? RunProjectionSnapshot.absent(authority.authorityHash())
            : evidence.projection();
    RunReconciliationStatus reconciliation =
        conflicts.isEmpty()
            ? previous == null ? RunReconciliationStatus.CONSISTENT : RunReconciliationStatus.REPAIRED
            : RunReconciliationStatus.CONFLICT_QUARANTINED;
    RunStateSnapshot next =
        RunStateSnapshot.create(authority, projection, reconciliation, conflicts, evidence.observedAt());
    transitionPolicy.validate(previous, next);
    return new RunStateReconciliationResult(next, conflicts);
  }

  public static RunMathematicalStatus deriveMath(
      RunMathematicalProgressSnapshot progress) {
    if (progress.finalValidationPassed()
        && (!progress.finalProofPresent()
            || !progress.finalReviewPassed()
            || !progress.problemIntegrityOk())) {
      return RunMathematicalStatus.AUTHORITY_CONFLICT;
    }
    if (progress.finalProofPresent()
        && progress.finalValidationPassed()
        && progress.finalReviewPassed()
        && progress.problemIntegrityOk()) {
      return RunMathematicalStatus.VERIFIED;
    }
    if (progress.finalProofPresent()) {
      return RunMathematicalStatus.CANDIDATE_UNVERIFIED;
    }
    return progress.anyProgress()
        ? RunMathematicalStatus.PARTIAL_UNVERIFIED
        : RunMathematicalStatus.NOT_STARTED;
  }

  public static RunCampaignStatus deriveCampaign(
      RunExecutionStatus execution,
      RunMathematicalStatus math,
      boolean checkpointPresent,
      boolean checkpointTerminal) {
    if (math == RunMathematicalStatus.VERIFIED || (checkpointPresent && checkpointTerminal)) {
      return RunCampaignStatus.TERMINAL;
    }
    if (execution == RunExecutionStatus.RUNNING) {
      return RunCampaignStatus.ACTIVE;
    }
    if ((execution == RunExecutionStatus.FAILED
            || execution == RunExecutionStatus.INTERRUPTED
            || execution == RunExecutionStatus.CANCELLED)
        && checkpointPresent
        && !checkpointTerminal) {
      return RunCampaignStatus.RECOVERABLE;
    }
    return execution == RunExecutionStatus.QUEUED
        ? RunCampaignStatus.QUEUED
        : RunCampaignStatus.TERMINAL;
  }

  private static RunTerminalReason deriveTerminalReason(
      RunTerminalReason supplied,
      RunExecutionStatus execution,
      RunMathematicalStatus math,
      RunCampaignStatus campaign) {
    if (supplied != RunTerminalReason.NONE) {
      return supplied;
    }
    if (math == RunMathematicalStatus.AUTHORITY_CONFLICT) {
      return RunTerminalReason.AUTHORITY_CONFLICT;
    }
    if (math == RunMathematicalStatus.VERIFIED) {
      return RunTerminalReason.VERIFIED;
    }
    if (campaign != RunCampaignStatus.TERMINAL) {
      return RunTerminalReason.NONE;
    }
    return switch (execution) {
      case FAILED -> RunTerminalReason.EXECUTION_FAILED;
      case INTERRUPTED -> RunTerminalReason.EXECUTION_INTERRUPTED;
      case CANCELLED -> RunTerminalReason.USER_CANCELLED;
      default -> RunTerminalReason.UNVERIFIED_TERMINAL;
    };
  }
}
