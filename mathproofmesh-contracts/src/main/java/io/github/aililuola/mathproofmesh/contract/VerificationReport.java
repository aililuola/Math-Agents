package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record VerificationReport(
    @JsonProperty(value = "agent_id", required = true) @ContractNonNull String agentId,
    @JsonProperty(value = "checked_dependencies") @ContractNonNull List<String> checkedDependencies,
    @JsonProperty(value = "concise_feedback", required = true) @ContractNonNull String conciseFeedback,
    @JsonProperty(value = "confidence", required = true) @ContractNonNull Double confidence,
    @JsonProperty(value = "failure_level") @ContractNonNull FailureLevel failureLevel,
    @JsonProperty(value = "first_error_step") String firstErrorStep,
    @JsonProperty(value = "issues") @ContractNonNull List<VerificationIssue> issues,
    @JsonProperty(value = "problem_integrity_ok") @ContractNonNull Boolean problemIntegrityOk,
    @JsonProperty(value = "raw_artifact_ref") String rawArtifactRef,
    @JsonProperty(value = "report_id") @ContractNonNull String reportId,
    @JsonProperty(value = "stage", required = true) @ContractNonNull VerificationStage stage,
    @JsonProperty(value = "structured_issues") @ContractNonNull List<JsonNode> structuredIssues,
    @JsonProperty(value = "target_id", required = true) @ContractNonNull String targetId,
    @JsonProperty(value = "target_type", required = true)
        @ContractNonNull
        @ContractAllowedValues({"attempt", "claim", "proof_delta", "checkpoint", "final_proof"})
        String targetType,
    @JsonProperty(value = "tool_requests") @ContractNonNull List<ToolRequest> toolRequests,
    @JsonProperty(value = "tool_results") @ContractNonNull List<ToolResult> toolResults,
    @JsonProperty(value = "usage") @ContractNonNull UsageRecord usage,
    @JsonProperty(value = "verdict", required = true) @ContractNonNull VerificationVerdict verdict
) implements StrictContract {

  public VerificationReport {
    agentId = ContractStrings.trim(agentId);
    agentId = ContractStrings.required("agent_id", agentId);
    if (checkedDependencies == null) {
      checkedDependencies = List.of();
    }
    checkedDependencies = ImmutableCollections.listOrEmpty(checkedDependencies);
    conciseFeedback = ContractStrings.trim(conciseFeedback);
    conciseFeedback = ContractStrings.required("concise_feedback", conciseFeedback);
    confidence = ContractValues.required("confidence", confidence);
    ContractValues.minimum("confidence", confidence, 0.0);
    ContractValues.maximum("confidence", confidence, 1.0);
    if (failureLevel == null) {
      failureLevel = FailureLevel.NONE;
    }
    firstErrorStep = ContractStrings.trim(firstErrorStep);
    if (issues == null) {
      issues = List.of();
    }
    issues = ImmutableCollections.listOrEmpty(issues);
    if (problemIntegrityOk == null) {
      problemIntegrityOk = true;
    }
    rawArtifactRef = ContractStrings.trim(rawArtifactRef);
    if (reportId == null) {
      reportId = PythonCompatibleIdGenerator.newId("verify");
    }
    reportId = ContractStrings.trim(reportId);
    stage = ContractValues.required("stage", stage);
    if (structuredIssues == null) {
      structuredIssues = List.of();
    }
    structuredIssues = ImmutableCollections.jsonListOrEmpty(structuredIssues);
    targetId = ContractStrings.trim(targetId);
    targetId = ContractStrings.required("target_id", targetId);
    targetType = ContractStrings.trim(targetType);
    targetType = ContractStrings.required("target_type", targetType);
    ContractValues.oneOf("target_type", targetType, "attempt", "claim", "proof_delta", "checkpoint", "final_proof");
    if (toolRequests == null) {
      toolRequests = List.of();
    }
    toolRequests = ImmutableCollections.listOrEmpty(toolRequests);
    if (toolResults == null) {
      toolResults = List.of();
    }
    toolResults = ImmutableCollections.listOrEmpty(toolResults);
    if (usage == null) {
      usage = new UsageRecord();
    }
    verdict = ContractValues.required("verdict", verdict);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> checkedDependencies() {
    return checkedDependencies == null ? null : List.copyOf(checkedDependencies);
  }

  public List<VerificationIssue> issues() {
    return issues == null ? null : List.copyOf(issues);
  }

  public List<JsonNode> structuredIssues() {
    return structuredIssues == null ? null : ImmutableCollections.copyJsonList(structuredIssues);
  }

  public List<ToolRequest> toolRequests() {
    return toolRequests == null ? null : List.copyOf(toolRequests);
  }

  public List<ToolResult> toolResults() {
    return toolResults == null ? null : List.copyOf(toolResults);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
