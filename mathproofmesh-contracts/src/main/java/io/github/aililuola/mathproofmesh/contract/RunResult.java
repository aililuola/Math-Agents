package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record RunResult(
    @JsonProperty(value = "agent_metrics") @ContractNonNull List<AgentMetric> agentMetrics,
    @JsonProperty(value = "attempts") @ContractNonNull List<ProofAttempt> attempts,
    @JsonProperty(value = "claims") @ContractNonNull List<ClaimCard> claims,
    @JsonProperty(value = "created_at") @ContractNonNull String createdAt,
    @JsonProperty(value = "deliverable_assessments") @ContractNonNull List<DeliverableAssessment> deliverableAssessments,
    @JsonProperty(value = "execution_status") @ContractNonNull ExecutionStatus executionStatus,
    @JsonProperty(value = "experiments") @ContractNonNull List<ExperimentResult> experiments,
    @JsonProperty(value = "final_proof") FinalProof finalProof,
    @JsonProperty(value = "final_verification") VerificationReport finalVerification,
    @JsonProperty(value = "formalization_coverage") FormalizationCoverageReport formalizationCoverage,
    @JsonProperty(value = "math_status") @ContractNonNull MathStatus mathStatus,
    @JsonProperty(value = "meta_reviews") @ContractNonNull List<MetaReview> metaReviews,
    @JsonProperty(value = "problem", required = true) @ContractNonNull ProblemContract problem,
    @JsonProperty(value = "proof_checkpoints") @ContractNonNull List<ProofCheckpoint> proofCheckpoints,
    @JsonProperty(value = "research_progress_report") ResearchProgressReport researchProgressReport,
    @JsonProperty(value = "resumed") @ContractNonNull Boolean resumed,
    @JsonProperty(value = "resumed_from_checkpoint_id") String resumedFromCheckpointId,
    @JsonProperty(value = "run_directory", required = true) @ContractNonNull String runDirectory,
    @JsonProperty(value = "run_id", required = true) @ContractNonNull String runId,
    @JsonProperty(value = "status", required = true) @ContractNonNull RunStatus status,
    @JsonProperty(value = "summary", required = true) @ContractNonNull String summary,
    @JsonProperty(value = "task_status") @ContractNonNull TaskStatus taskStatus,
    @JsonProperty(value = "termination_reason") String terminationReason,
    @JsonProperty(value = "total_calls") @ContractNonNull Integer totalCalls,
    @JsonProperty(value = "total_usage") @ContractNonNull UsageRecord totalUsage,
    @JsonProperty(value = "verification_reports") @ContractNonNull List<VerificationReport> verificationReports
) implements StrictContract {

  public RunResult {
    if (agentMetrics == null) {
      agentMetrics = List.of();
    }
    agentMetrics = ImmutableCollections.listOrEmpty(agentMetrics);
    if (attempts == null) {
      attempts = List.of();
    }
    attempts = ImmutableCollections.listOrEmpty(attempts);
    if (claims == null) {
      claims = List.of();
    }
    claims = ImmutableCollections.listOrEmpty(claims);
    if (createdAt == null) {
      createdAt = PythonIsoTimestampCodec.now();
    }
    createdAt = ContractStrings.trim(createdAt);
    if (deliverableAssessments == null) {
      deliverableAssessments = List.of();
    }
    deliverableAssessments = ImmutableCollections.listOrEmpty(deliverableAssessments);
    if (executionStatus == null) {
      executionStatus = ExecutionStatus.COMPLETED;
    }
    if (experiments == null) {
      experiments = List.of();
    }
    experiments = ImmutableCollections.listOrEmpty(experiments);
    if (mathStatus == null) {
      mathStatus = MathStatus.INCONCLUSIVE;
    }
    if (metaReviews == null) {
      metaReviews = List.of();
    }
    metaReviews = ImmutableCollections.listOrEmpty(metaReviews);
    problem = ContractValues.required("problem", problem);
    if (proofCheckpoints == null) {
      proofCheckpoints = List.of();
    }
    proofCheckpoints = ImmutableCollections.listOrEmpty(proofCheckpoints);
    if (resumed == null) {
      resumed = false;
    }
    resumedFromCheckpointId = ContractStrings.trim(resumedFromCheckpointId);
    runDirectory = ContractStrings.trim(runDirectory);
    runDirectory = ContractStrings.required("run_directory", runDirectory);
    runId = ContractStrings.trim(runId);
    runId = ContractStrings.required("run_id", runId);
    status = ContractValues.required("status", status);
    summary = ContractStrings.trim(summary);
    summary = ContractStrings.required("summary", summary);
    if (taskStatus == null) {
      taskStatus = TaskStatus.INCOMPLETE;
    }
    terminationReason = ContractStrings.trim(terminationReason);
    if (totalCalls == null) {
      totalCalls = 0;
    }
    ContractValues.minimum("total_calls", totalCalls, 0);
    if (totalUsage == null) {
      totalUsage = new UsageRecord();
    }
    if (verificationReports == null) {
      verificationReports = List.of();
    }
    verificationReports = ImmutableCollections.listOrEmpty(verificationReports);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<AgentMetric> agentMetrics() {
    return agentMetrics == null ? null : List.copyOf(agentMetrics);
  }

  public List<ProofAttempt> attempts() {
    return attempts == null ? null : List.copyOf(attempts);
  }

  public List<ClaimCard> claims() {
    return claims == null ? null : List.copyOf(claims);
  }

  public List<DeliverableAssessment> deliverableAssessments() {
    return deliverableAssessments == null ? null : List.copyOf(deliverableAssessments);
  }

  public List<ExperimentResult> experiments() {
    return experiments == null ? null : List.copyOf(experiments);
  }

  public List<MetaReview> metaReviews() {
    return metaReviews == null ? null : List.copyOf(metaReviews);
  }

  public List<ProofCheckpoint> proofCheckpoints() {
    return proofCheckpoints == null ? null : List.copyOf(proofCheckpoints);
  }

  public List<VerificationReport> verificationReports() {
    return verificationReports == null ? null : List.copyOf(verificationReports);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
