package io.github.aililuola.mathproofmesh.runstate;

import java.util.ArrayList;
import java.util.List;

public final class RunStateReconciler {
  private final RunUsageReconciler usageReconciler;
  private final RunMathematicalProgressReconciler mathematicalProgressReconciler;
  private final RunStateTransitionPolicy transitionPolicy;

  public RunStateReconciler() {
    this(
        new RunUsageReconciler(),
        new RunMathematicalProgressReconciler(),
        new RunStateTransitionPolicy());
  }

  public RunStateReconciler(
      RunUsageReconciler usageReconciler, RunStateTransitionPolicy transitionPolicy) {
    this(usageReconciler, new RunMathematicalProgressReconciler(), transitionPolicy);
  }

  public RunStateReconciler(
      RunUsageReconciler usageReconciler,
      RunMathematicalProgressReconciler mathematicalProgressReconciler,
      RunStateTransitionPolicy transitionPolicy) {
    this.usageReconciler = java.util.Objects.requireNonNull(usageReconciler, "usageReconciler");
    this.mathematicalProgressReconciler =
        java.util.Objects.requireNonNull(
            mathematicalProgressReconciler, "mathematicalProgressReconciler");
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
    RunMathematicalProgressReconciliationResult mathematicalProgress =
        mathematicalProgressReconciler.reconcile(
            evidence.mathematicalProgress(),
            previous == null ? null : previous.authority().mathematicalProgress());
    conflicts.addAll(mathematicalProgress.conflicts());
    String proofGraphHash = reconcileProofGraphHash(previous, evidence, conflicts);
    RunMathematicalStatus math = deriveMath(mathematicalProgress.progress());
    if (!conflicts.isEmpty()
        && conflicts.stream().anyMatch(conflict -> isMathematicalConflict(conflict.code()))) {
      math = RunMathematicalStatus.AUTHORITY_CONFLICT;
    }
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
            mathematicalProgress.progress(),
            evidence.semanticCheckpointRef(),
            evidence.semanticCheckpointHash(),
            proofGraphHash,
            null,
            version);
    RunProjectionSnapshot projection = alignProjection(evidence.projection(), authority.authorityHash());
    RunReconciliationStatus reconciliation =
        conflicts.isEmpty()
            ? previous == null ? RunReconciliationStatus.CONSISTENT : RunReconciliationStatus.REPAIRED
            : RunReconciliationStatus.CONFLICT_QUARANTINED;
    RunStateSnapshot next =
        RunStateSnapshot.create(authority, projection, reconciliation, conflicts, evidence.observedAt());
    transitionPolicy.validate(previous, next);
    return new RunStateReconciliationResult(next, conflicts);
  }

  private static boolean isMathematicalConflict(String code) {
    return code.startsWith("FINAL_")
        || code.startsWith("VERIFIED_CLAIM_")
        || code.startsWith("REFUTED_CLAIM_")
        || code.startsWith("PROOF_GRAPH_");
  }

  private static String reconcileProofGraphHash(
      RunStateSnapshot previous,
      RunStateEvidenceBundle evidence,
      List<RunStateConflict> conflicts) {
    String current = evidence.proofGraphHash();
    if (previous == null || previous.authority().proofGraphHash().isEmpty()) {
      return current;
    }
    String prior = previous.authority().proofGraphHash();
    if (current.isEmpty() || prior.equals(current)) {
      return prior;
    }
    if (!evidence.semanticCheckpointHash().isEmpty()
        && evidence
            .semanticCheckpointHash()
            .equals(previous.authority().latestSemanticCheckpointHash())) {
      conflicts.add(
          new RunStateConflict(
              "PROOF_GRAPH_HASH_CONFLICT", "proof_graph", List.of(prior, current)));
      return prior;
    }
    return current;
  }

  private static RunProjectionSnapshot alignProjection(
      RunProjectionSnapshot projection, String authorityHash) {
    if (projection == null) {
      return RunProjectionSnapshot.absent(authorityHash);
    }
    if (RunStateHashes.equalHash(authorityHash, projection.authorityHash())) {
      return projection;
    }
    return new RunProjectionSnapshot(
        authorityHash,
        projection.reportStatus(),
        projection.runResultRef(),
        projection.runResultHash(),
        projection.desktopMetadataRef(),
        projection.desktopMetadataHash(),
        projection.reportRef(),
        projection.reportHash(),
        projection.latestActivitySequence(),
        projection.projectionErrors(),
        projection.projectionVersion(),
        null);
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
        && !checkpointTerminal) {
      return RunCampaignStatus.RECOVERABLE;
    }
    if (execution == RunExecutionStatus.SUCCEEDED
        && math != RunMathematicalStatus.VERIFIED) {
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
