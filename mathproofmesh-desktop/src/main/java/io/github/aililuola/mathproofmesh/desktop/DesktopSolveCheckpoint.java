package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewBatch;
import io.github.aililuola.mathproofmesh.contract.ComputationDecision;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import io.github.aililuola.mathproofmesh.contract.FinalProof;
import io.github.aililuola.mathproofmesh.contract.FormalizationCoverageReport;
import io.github.aililuola.mathproofmesh.contract.InspirationOutcome;
import io.github.aililuola.mathproofmesh.contract.InspirationProposal;
import io.github.aililuola.mathproofmesh.contract.InspirationAssignmentPlan;
import io.github.aililuola.mathproofmesh.contract.InspirationTask;
import io.github.aililuola.mathproofmesh.contract.InspirationTriggerType;
import io.github.aililuola.mathproofmesh.contract.MetaReview;
import io.github.aililuola.mathproofmesh.contract.ProblemContract;
import io.github.aililuola.mathproofmesh.contract.ProofAttempt;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.contract.TriageResult;
import io.github.aililuola.mathproofmesh.contract.ToolAuditReport;
import io.github.aililuola.mathproofmesh.contract.VerificationReport;
import io.github.aililuola.mathproofmesh.communication.MessageStoreSnapshot;
import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationEvidenceGate;
import io.github.aililuola.mathproofmesh.memory.LemmaMemorySnapshot;
import io.github.aililuola.mathproofmesh.memory.TypedMemorySnapshot;
import io.github.aililuola.mathproofmesh.orchestration.ContinuationFunctions.Checkpoint;
import io.github.aililuola.mathproofmesh.orchestration.ContinuationFunctions.Delta;
import io.github.aililuola.mathproofmesh.orchestration.teams.RouteTeamResult;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactSnapshot;
import io.github.aililuola.mathproofmesh.proofcontrol.ClaimLifecycleSnapshot;
import io.github.aililuola.mathproofmesh.proofcontrol.FailureControlService;
import io.github.aililuola.mathproofmesh.proofcontrol.MetaPivotController;
import io.github.aililuola.mathproofmesh.proofcontrol.NearMissLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels;
import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotSnapshot;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyBlueprintCompiler;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyArchive;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphSnapshot;
import io.github.aililuola.mathproofmesh.proofgraph.DeferredExpansionSnapshot;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphConvergenceSnapshot;
import io.github.aililuola.mathproofmesh.proofgraph.ProofTaskScope;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointSnapshot;
import io.github.aililuola.mathproofmesh.strategydiversity.PortfolioReplenishmentSnapshot;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCandidateSnapshot;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyMechanismSnapshot;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioSnapshot;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPreflightSnapshot;
import io.github.aililuola.mathproofmesh.provider.UsageTotals;
import io.github.aililuola.mathproofmesh.inspiration.InspirationSnapshot;
import io.github.aililuola.mathproofmesh.verification.EscalationPlan;
import io.github.aililuola.mathproofmesh.verification.ValidationExecution;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Durable, provider-neutral continuation state for a desktop solve. */
record DesktopSolveCheckpoint(
    int schemaVersion,
    String runId,
    String problemHash,
    String currentStage,
    int roundIndex,
    UsageTotals usageTotals,
    ProblemContract problem,
    TriageResult triage,
    StrategySet strategySet,
    List<StrategyCard> admittedStrategies,
    int nextStrategyIndex,
    List<RouteCheckpoint> routes,
    List<InspirationProposal> inspirationProposals,
    List<InspirationOutcome> inspirationOutcomes,
    List<ComputationCheckpoint> computations,
    LemmaMemorySnapshot lemmaMemory,
    TypedMemorySnapshot typedMemory,
    ProofGraphSnapshot proofGraph,
    AttemptArtifactSnapshot attemptArtifacts,
    ClaimLifecycleSnapshot claimLifecycle,
    ResearchCheckpointSnapshot researchCheckpoints,
    MessageStoreSnapshot messageStore,
    List<Double> proofDebtHistory,
    StrategyArchive.Snapshot strategyArchive,
    Map<String, StrategyBlueprintCompiler.Compilation> strategyBlueprints,
    Map<String, ProofControlModels.GoalLink> goalLinks,
    StrategyCandidateSnapshot strategyCandidates,
    StrategyMechanismSnapshot strategyMechanisms,
    StrategyPreflightSnapshot strategyPreflights,
    StrategyPortfolioSnapshot strategyPortfolios,
    PortfolioReplenishmentSnapshot portfolioReplenishments,
    List<MetaPivotController.Pivot> metaPivots,
    SemanticPivotSnapshot semanticPivots,
    InspirationRoundProgress inspirationProgress,
    MetaReview pendingMetaReview,
    List<ScheduledProofTask> pendingProofTasks,
    SchedulerStop schedulerStop,
    String workflowCursor,
    FinalProof finalProof,
    VerificationReport finalReview,
    List<VerificationReport> finalReviewReports,
    Boolean finalValidationPassed,
    ValidationExecution finalValidationExecution,
    FormalizationCoverageReport formalizationCoverage,
    List<ComputationBroker.ComputationAudit> computationAudits,
    List<String> completedStages,
    ProofGraphConvergenceSnapshot proofGraphConvergence,
    DeferredExpansionSnapshot deferredExpansions,
    boolean terminal) {

  static final int CURRENT_SCHEMA_VERSION = 15;

  DesktopSolveCheckpoint {
    if (schemaVersion >= 2) {
      usageTotals = Objects.requireNonNull(usageTotals, "usageTotals");
    }
    admittedStrategies = admittedStrategies == null ? List.of() : List.copyOf(admittedStrategies);
    routes = routes == null ? List.of() : List.copyOf(routes);
    inspirationProposals =
        inspirationProposals == null ? List.of() : List.copyOf(inspirationProposals);
    inspirationOutcomes =
        inspirationOutcomes == null ? List.of() : List.copyOf(inspirationOutcomes);
    computations = computations == null ? List.of() : List.copyOf(computations);
    attemptArtifacts =
        attemptArtifacts == null ? AttemptArtifactSnapshot.empty() : attemptArtifacts;
    claimLifecycle =
        claimLifecycle == null ? ClaimLifecycleSnapshot.empty() : claimLifecycle;
    researchCheckpoints =
        researchCheckpoints == null ? ResearchCheckpointSnapshot.empty() : researchCheckpoints;
    proofDebtHistory = proofDebtHistory == null ? List.of() : List.copyOf(proofDebtHistory);
    strategyBlueprints = strategyBlueprints == null ? Map.of() : Map.copyOf(strategyBlueprints);
    goalLinks = goalLinks == null ? Map.of() : Map.copyOf(goalLinks);
    strategyCandidates =
        strategyCandidates == null ? StrategyCandidateSnapshot.empty() : strategyCandidates;
    strategyMechanisms =
        strategyMechanisms == null ? StrategyMechanismSnapshot.empty() : strategyMechanisms;
    strategyPreflights =
        strategyPreflights == null ? StrategyPreflightSnapshot.empty() : strategyPreflights;
    strategyPortfolios =
        strategyPortfolios == null ? StrategyPortfolioSnapshot.empty() : strategyPortfolios;
    portfolioReplenishments =
        portfolioReplenishments == null
            ? PortfolioReplenishmentSnapshot.empty()
            : portfolioReplenishments;
    metaPivots = metaPivots == null ? List.of() : List.copyOf(metaPivots);
    semanticPivots =
        semanticPivots == null ? SemanticPivotSnapshot.empty() : semanticPivots;
    pendingProofTasks = pendingProofTasks == null ? List.of() : List.copyOf(pendingProofTasks);
    workflowCursor = workflowCursor == null ? "" : workflowCursor.strip();
    finalReviewReports = finalReviewReports == null ? List.of() : List.copyOf(finalReviewReports);
    finalValidationPassed = Boolean.TRUE.equals(finalValidationPassed);
    computationAudits = computationAudits == null ? List.of() : List.copyOf(computationAudits);
    completedStages = completedStages == null ? List.of() : List.copyOf(completedStages);
    proofGraphConvergence =
        proofGraphConvergence == null
            ? ProofGraphConvergenceSnapshot.empty()
            : proofGraphConvergence;
    deferredExpansions =
        deferredExpansions == null ? DeferredExpansionSnapshot.empty() : deferredExpansions;
  }

  @Override
  public List<StrategyCard> admittedStrategies() {
    return List.copyOf(admittedStrategies);
  }

  @Override
  public List<RouteCheckpoint> routes() {
    return List.copyOf(routes);
  }

  @Override
  public List<InspirationProposal> inspirationProposals() {
    return List.copyOf(inspirationProposals);
  }

  @Override
  public List<InspirationOutcome> inspirationOutcomes() {
    return List.copyOf(inspirationOutcomes);
  }

  @Override
  public List<ComputationCheckpoint> computations() {
    return List.copyOf(computations);
  }

  @Override
  public List<Double> proofDebtHistory() {
    return List.copyOf(proofDebtHistory);
  }

  @Override
  public Map<String, StrategyBlueprintCompiler.Compilation> strategyBlueprints() {
    return Map.copyOf(strategyBlueprints);
  }

  @Override
  public Map<String, ProofControlModels.GoalLink> goalLinks() {
    return Map.copyOf(goalLinks);
  }

  @Override
  public List<MetaPivotController.Pivot> metaPivots() {
    return List.copyOf(metaPivots);
  }

  @Override
  public List<ScheduledProofTask> pendingProofTasks() {
    return List.copyOf(pendingProofTasks);
  }

  @Override
  public List<VerificationReport> finalReviewReports() {
    return List.copyOf(finalReviewReports);
  }

  @Override
  public List<ComputationBroker.ComputationAudit> computationAudits() {
    return List.copyOf(computationAudits);
  }

  @Override
  public List<String> completedStages() {
    return List.copyOf(completedStages);
  }

  record RouteCheckpoint(
      String routeId,
      String authorAgentId,
      StrategyCard strategy,
      ProofAttempt attempt,
      VerificationReport skepticReview,
      ToolAuditReport toolAudit,
      VerificationReport structuralReview,
      VerificationReport detailedReview,
      VerificationReport crossProviderReview,
      RouteTeamResult teamResult,
      EscalationPlan escalation,
      ValidationExecution validationExecution,
      String status,
      String failureReason,
      Checkpoint checkpoint,
      Delta delta,
      String deltaId,
      FailureControlService.Failure failure,
      NearMissLedger.NearMiss nearMiss,
      List<String> claimIds,
      List<String> artifactIds,
      List<String> salvagedVerifiedClaimIds,
      List<String> salvagedCounterexampleIds,
      List<String> rejectedClaimIds,
      List<String> uncertainClaimIds,
      ClaimReviewBatch claimReview,
      int segmentCount,
      int noProgressSegments,
      int revisionCount,
      int cooldownUntilRound,
      boolean metaAbandoned,
      String metaControlReason,
      List<AttemptRevisionCheckpoint> revisionHistory,
      String focusObligationId,
      String focusedCanonicalTargetId,
      String focusedBottleneckFamilyId,
      String focusSource,
      String latestResearchCheckpointId,
      List<String> activeResearchFindingIds,
      String lastCheckpointedProviderCallId,
      Integer checkpointRecoveryCount,
      Boolean pendingFindingReconciliation,
      boolean reviewComplete,
      boolean checkpointProcessed,
      boolean integrated,
      String activeSemanticPivotId,
      List<String> semanticPivotIds,
      String activeStrategyEpochId,
      List<String> retiredActiveClaimIds,
      List<ClaimCard> pendingPivotProposedClaims,
      List<String> retiredStrategyFocusObligationIds,
      List<String> activeMathematicalObjectIds,
      String activeDirectionSignature) {
    RouteCheckpoint {
      claimIds = claimIds == null ? List.of() : List.copyOf(claimIds);
      artifactIds = artifactIds == null ? List.of() : List.copyOf(artifactIds);
      salvagedVerifiedClaimIds =
          salvagedVerifiedClaimIds == null ? List.of() : List.copyOf(salvagedVerifiedClaimIds);
      salvagedCounterexampleIds =
          salvagedCounterexampleIds == null ? List.of() : List.copyOf(salvagedCounterexampleIds);
      rejectedClaimIds = rejectedClaimIds == null ? List.of() : List.copyOf(rejectedClaimIds);
      uncertainClaimIds = uncertainClaimIds == null ? List.of() : List.copyOf(uncertainClaimIds);
      metaControlReason = metaControlReason == null ? "" : metaControlReason.strip();
      revisionHistory = revisionHistory == null ? List.of() : List.copyOf(revisionHistory);
      focusObligationId = focusObligationId == null ? "" : focusObligationId.strip();
      focusedCanonicalTargetId =
          focusedCanonicalTargetId == null ? "" : focusedCanonicalTargetId.strip();
      focusedBottleneckFamilyId =
          focusedBottleneckFamilyId == null ? "" : focusedBottleneckFamilyId.strip();
      focusSource = focusSource == null ? "" : focusSource.strip();
      latestResearchCheckpointId =
          latestResearchCheckpointId == null ? "" : latestResearchCheckpointId.strip();
      activeResearchFindingIds =
          activeResearchFindingIds == null ? List.of() : List.copyOf(activeResearchFindingIds);
      lastCheckpointedProviderCallId =
          lastCheckpointedProviderCallId == null ? "" : lastCheckpointedProviderCallId.strip();
      if (checkpointRecoveryCount == null) {
        checkpointRecoveryCount = Integer.valueOf(0);
      }
      if (checkpointRecoveryCount < 0) {
        throw new IllegalArgumentException("checkpointRecoveryCount must be nonnegative");
      }
      pendingFindingReconciliation = Boolean.TRUE.equals(pendingFindingReconciliation);
      activeSemanticPivotId =
          activeSemanticPivotId == null ? "" : activeSemanticPivotId.strip();
      semanticPivotIds = semanticPivotIds == null ? List.of() : List.copyOf(semanticPivotIds);
      activeStrategyEpochId =
          activeStrategyEpochId == null || activeStrategyEpochId.isBlank()
              ? strategy.strategyId()
              : activeStrategyEpochId.strip();
      retiredActiveClaimIds =
          retiredActiveClaimIds == null ? List.of() : List.copyOf(retiredActiveClaimIds);
      pendingPivotProposedClaims =
          pendingPivotProposedClaims == null
              ? List.of()
              : List.copyOf(pendingPivotProposedClaims);
      retiredStrategyFocusObligationIds =
          retiredStrategyFocusObligationIds == null
              ? List.of()
              : List.copyOf(retiredStrategyFocusObligationIds);
      activeMathematicalObjectIds =
          activeMathematicalObjectIds == null
              ? List.of()
              : List.copyOf(activeMathematicalObjectIds);
      activeDirectionSignature =
          activeDirectionSignature == null || activeDirectionSignature.isBlank()
              ? "forward"
              : activeDirectionSignature.strip();
    }

    @Override
    public List<String> claimIds() {
      return List.copyOf(claimIds);
    }

    @Override
    public List<String> artifactIds() {
      return List.copyOf(artifactIds);
    }

    @Override
    public List<String> salvagedVerifiedClaimIds() {
      return List.copyOf(salvagedVerifiedClaimIds);
    }

    @Override
    public List<String> salvagedCounterexampleIds() {
      return List.copyOf(salvagedCounterexampleIds);
    }

    @Override
    public List<String> rejectedClaimIds() {
      return List.copyOf(rejectedClaimIds);
    }

    @Override
    public List<String> uncertainClaimIds() {
      return List.copyOf(uncertainClaimIds);
    }

    @Override
    public List<String> activeResearchFindingIds() {
      return List.copyOf(activeResearchFindingIds);
    }

    @Override
    public List<AttemptRevisionCheckpoint> revisionHistory() {
      return List.copyOf(revisionHistory);
    }

    @Override
    public List<String> semanticPivotIds() {
      return List.copyOf(semanticPivotIds);
    }

    @Override
    public List<String> retiredActiveClaimIds() {
      return List.copyOf(retiredActiveClaimIds);
    }

    @Override
    public List<ClaimCard> pendingPivotProposedClaims() {
      return List.copyOf(pendingPivotProposedClaims);
    }

    @Override
    public List<String> retiredStrategyFocusObligationIds() {
      return List.copyOf(retiredStrategyFocusObligationIds);
    }

    @Override
    public List<String> activeMathematicalObjectIds() {
      return List.copyOf(activeMathematicalObjectIds);
    }
  }

  record AttemptRevisionCheckpoint(
      int revisionIndex,
      String action,
      StrategyCard strategy,
      ProofAttempt attempt,
      VerificationReport detailedReview,
      ToolAuditReport toolAudit,
      Checkpoint checkpoint,
      Delta delta,
      String deltaId,
      String status,
      String failureReason,
      List<String> claimIds,
      int segmentCount) {
    AttemptRevisionCheckpoint {
      action = action == null ? "" : action.strip();
      status = status == null ? "" : status.strip();
      failureReason = failureReason == null ? "" : failureReason.strip();
      claimIds = claimIds == null ? List.of() : List.copyOf(claimIds);
    }

    @Override
    public List<String> claimIds() {
      return List.copyOf(claimIds);
    }
  }

  record ScheduledProofTask(
      String taskId,
      String source,
      String routeId,
      String obligationId,
      String canonicalTargetId,
      String familyId,
      ProofTaskScope scope,
      String actionKey,
      String requestedAction,
      int roundCreated) {
    ScheduledProofTask {
      taskId = require(taskId, "taskId");
      source = require(source, "source");
      routeId = require(routeId, "routeId");
      obligationId = require(obligationId, "obligationId");
      canonicalTargetId = canonicalTargetId == null ? "" : canonicalTargetId.strip();
      familyId = familyId == null ? "" : familyId.strip();
      scope = scope == null ? ProofTaskScope.ROUTE_OCCURRENCE : scope;
      requestedAction = require(requestedAction, "requestedAction");
      actionKey =
          actionKey == null || actionKey.isBlank()
              ? requestedAction.toLowerCase(java.util.Locale.ROOT)
              : actionKey.strip();
      if (roundCreated < 0) {
        throw new IllegalArgumentException("roundCreated must be nonnegative");
      }
    }

    ScheduledProofTask(
        String taskId,
        String source,
        String routeId,
        String obligationId,
        String requestedAction,
        int roundCreated) {
      this(
          taskId,
          source,
          routeId,
          obligationId,
          "",
          "",
          ProofTaskScope.ROUTE_OCCURRENCE,
          requestedAction == null
              ? ""
              : requestedAction.toLowerCase(java.util.Locale.ROOT),
          requestedAction,
          roundCreated);
    }
  }

  record InspirationRoundProgress(
      int roundIndex,
      InspirationSnapshot snapshot,
      List<InspirationTask> tasks,
      Map<String, InspirationAssignmentPlan> assignmentPlans,
      Map<String, InspirationTriggerType> triggerTypes,
      List<String> completedTaskIds,
      Map<String, List<Integer>> completedProposalSlots,
      MetaPivotController.Pivot pivot,
      int proposalCountBefore,
      int verifiedFactsBefore,
      int closedObligationsBefore,
      double proofDebtBefore) {
    InspirationRoundProgress {
      if (roundIndex < 0
          || proposalCountBefore < 0
          || verifiedFactsBefore < 0
          || closedObligationsBefore < 0
          || !Double.isFinite(proofDebtBefore)
          || proofDebtBefore < 0.0d) {
        throw new IllegalArgumentException("invalid inspiration progress counters");
      }
      snapshot = Objects.requireNonNull(snapshot, "snapshot");
      tasks = tasks == null ? List.of() : List.copyOf(tasks);
      assignmentPlans = assignmentPlans == null ? Map.of() : Map.copyOf(assignmentPlans);
      triggerTypes = triggerTypes == null ? Map.of() : Map.copyOf(triggerTypes);
      completedTaskIds =
          completedTaskIds == null
              ? List.of()
              : completedTaskIds.stream().distinct().sorted().toList();
      if (completedProposalSlots == null) {
        completedProposalSlots = Map.of();
      } else {
        Map<String, List<Integer>> normalized = new java.util.LinkedHashMap<>();
        completedProposalSlots.forEach(
            (taskId, slots) ->
                normalized.put(
                    require(taskId, "completedProposalSlots taskId"),
                    slots == null
                        ? List.of()
                        : slots.stream()
                            .filter(Objects::nonNull)
                            .filter(slot -> slot >= 0)
                            .distinct()
                            .sorted()
                            .toList()));
        completedProposalSlots = Map.copyOf(normalized);
      }
    }

    InspirationRoundProgress markProposalSlot(String taskId, int proposalSlot) {
      if (proposalSlot < 0) {
        throw new IllegalArgumentException("proposalSlot must be nonnegative");
      }
      String normalizedTaskId = require(taskId, "taskId");
      Map<String, List<Integer>> updated = new java.util.LinkedHashMap<>(completedProposalSlots);
      List<Integer> slots = new java.util.ArrayList<>(updated.getOrDefault(normalizedTaskId, List.of()));
      slots.add(proposalSlot);
      updated.put(normalizedTaskId, slots);
      return new InspirationRoundProgress(
          roundIndex,
          snapshot,
          tasks,
          assignmentPlans,
          triggerTypes,
          completedTaskIds,
          updated,
          pivot,
          proposalCountBefore,
          verifiedFactsBefore,
          closedObligationsBefore,
          proofDebtBefore);
    }

    InspirationRoundProgress markTaskCompleted(String taskId) {
      List<String> completed = new java.util.ArrayList<>(completedTaskIds);
      completed.add(require(taskId, "taskId"));
      return new InspirationRoundProgress(
          roundIndex,
          snapshot,
          tasks,
          assignmentPlans,
          triggerTypes,
          completed,
          completedProposalSlots,
          pivot,
          proposalCountBefore,
          verifiedFactsBefore,
          closedObligationsBefore,
          proofDebtBefore);
    }

    boolean taskCompleted(String taskId) {
      return completedTaskIds.contains(taskId);
    }

    boolean proposalSlotCompleted(String taskId, int proposalSlot) {
      return completedProposalSlots.getOrDefault(taskId, List.of()).contains(proposalSlot);
    }

    InspirationRoundProgress withAssignmentPlan(InspirationAssignmentPlan plan) {
      Objects.requireNonNull(plan, "plan");
      Map<String, InspirationAssignmentPlan> updated = new java.util.LinkedHashMap<>(assignmentPlans);
      updated.put(plan.taskId(), plan);
      return new InspirationRoundProgress(
          roundIndex,
          snapshot,
          tasks,
          updated,
          triggerTypes,
          completedTaskIds,
          completedProposalSlots,
          pivot,
          proposalCountBefore,
          verifiedFactsBefore,
          closedObligationsBefore,
          proofDebtBefore);
    }
  }

  record SchedulerStop(
      String code,
      String detail,
      int routeCount,
      int independentRouteCount,
      int routeCap,
      int remainingCalls,
      int remainingTokens,
      double remainingCostUsd,
      int remainingRounds,
      int openObligations) {
    SchedulerStop {
      code = require(code, "code");
      detail = require(detail, "detail");
      if (routeCount < 0
          || independentRouteCount < 0
          || routeCap < 0
          || remainingCalls < 0
          || remainingTokens < 0
          || !Double.isFinite(remainingCostUsd)
          || remainingCostUsd < 0.0d
          || remainingRounds < 0
          || openObligations < 0) {
        throw new IllegalArgumentException("invalid scheduler stop diagnostics");
      }
    }
  }

  record ComputationCheckpoint(
      String routeId,
      ExperimentSpec spec,
      ComputationDecision decision,
      ExperimentProgram program,
      ExperimentResult result,
      String targetObligationId,
      ComputationEvidenceGate.EvidenceAuthority authority,
      boolean replayValid) {
    ComputationCheckpoint {
      targetObligationId = targetObligationId == null ? "" : targetObligationId.strip();
      authority =
          authority == null
              ? ComputationEvidenceGate.EvidenceAuthority.INCONCLUSIVE
              : authority;
    }
  }

  private static String require(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
