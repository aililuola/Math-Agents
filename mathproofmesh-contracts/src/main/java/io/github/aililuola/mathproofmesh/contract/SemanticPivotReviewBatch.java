package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One bounded independent review batch for semantic pivot proposals. */
public record SemanticPivotReviewBatch(
    @JsonProperty(value = "report_id") @ContractNonNull String reportId,
    @JsonProperty(value = "reviewer_agent_id", required = true) @ContractNonNull
        String reviewerAgentId,
    @JsonProperty(value = "proposer_agent_id", required = true) @ContractNonNull
        String proposerAgentId,
    @JsonProperty(value = "decisions") @ContractNonNull
        List<SemanticPivotReviewDecision> decisions,
    @JsonProperty(value = "raw_artifact_ref") String rawArtifactRef,
    @JsonProperty(value = "usage") @ContractNonNull UsageRecord usage)
    implements StrictContract {
  public static final int MAX_DECISIONS = 16;

  public SemanticPivotReviewBatch {
    if (reportId == null) {
      reportId = PythonCompatibleIdGenerator.newId("semantic-pivot-review");
    }
    reportId = ContractStrings.required("report_id", ContractStrings.trim(reportId));
    reviewerAgentId =
        ContractStrings.required("reviewer_agent_id", ContractStrings.trim(reviewerAgentId));
    proposerAgentId =
        ContractStrings.required("proposer_agent_id", ContractStrings.trim(proposerAgentId));
    decisions = ImmutableCollections.listOrEmpty(decisions);
    if (decisions.size() > MAX_DECISIONS) {
      throw new ContractValidationException(
          "semantic pivot review exceeds " + MAX_DECISIONS + " decisions");
    }
    Set<String> pivotIds = new LinkedHashSet<>();
    for (SemanticPivotReviewDecision decision : decisions) {
      if (!pivotIds.add(decision.pivotId())) {
        throw new ContractValidationException(
            "duplicate semantic pivot review decision: " + decision.pivotId());
      }
    }
    rawArtifactRef = ContractStrings.trim(rawArtifactRef);
    usage = usage == null ? new UsageRecord() : usage;
  }

  @Override
  public List<SemanticPivotReviewDecision> decisions() {
    return List.copyOf(decisions);
  }
}
