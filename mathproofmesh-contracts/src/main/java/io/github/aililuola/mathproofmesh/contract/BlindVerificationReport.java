package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record BlindVerificationReport(
    @JsonProperty(value = "checked_dependencies") @ContractNonNull List<String> checkedDependencies,
    @JsonProperty(value = "concise_feedback", required = true) @ContractNonNull String conciseFeedback,
    @JsonProperty(value = "confidence", required = true) @ContractNonNull Double confidence,
    @JsonProperty(value = "failure_level") @ContractNonNull FailureLevel failureLevel,
    @JsonProperty(value = "first_error_step") String firstErrorStep,
    @JsonProperty(value = "issues") @ContractNonNull List<VerificationIssue> issues,
    @JsonProperty(value = "problem_integrity_ok") @ContractNonNull Boolean problemIntegrityOk,
    @JsonProperty(value = "structured_issues") @ContractNonNull List<JsonNode> structuredIssues,
    @JsonProperty(value = "tool_requests") @ContractNonNull List<ToolRequest> toolRequests,
    @JsonProperty(value = "tool_results") @ContractNonNull List<ToolResult> toolResults,
    @JsonProperty(value = "verdict", required = true) @ContractNonNull VerificationVerdict verdict
) implements StrictContract {

  public BlindVerificationReport {
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
    if (structuredIssues == null) {
      structuredIssues = List.of();
    }
    structuredIssues = ImmutableCollections.jsonListOrEmpty(structuredIssues);
    if (toolRequests == null) {
      toolRequests = List.of();
    }
    toolRequests = ImmutableCollections.listOrEmpty(toolRequests);
    if (toolResults == null) {
      toolResults = List.of();
    }
    toolResults = ImmutableCollections.listOrEmpty(toolResults);
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
