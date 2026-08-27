package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One bounded, independently authored review batch for one proof attempt. */
public record ClaimReviewBatch(
    @JsonProperty(value = "report_id") @ContractNonNull String reportId,
    @JsonProperty(value = "agent_id", required = true) @ContractNonNull String agentId,
    @JsonProperty(value = "route_id", required = true) @ContractNonNull String routeId,
    @JsonProperty(value = "attempt_id", required = true) @ContractNonNull String attemptId,
    @JsonProperty(value = "decisions") @ContractNonNull List<ClaimReviewDecision> decisions,
    @JsonProperty(value = "raw_artifact_ref") String rawArtifactRef,
    @JsonProperty(value = "usage") @ContractNonNull UsageRecord usage)
    implements StrictContract {
  public static final int MAX_DECISIONS = 64;

  public ClaimReviewBatch {
    if (reportId == null) {
      reportId = PythonCompatibleIdGenerator.newId("claim-review");
    }
    reportId = ContractStrings.required("report_id", ContractStrings.trim(reportId));
    agentId = ContractStrings.required("agent_id", ContractStrings.trim(agentId));
    routeId = ContractStrings.required("route_id", ContractStrings.trim(routeId));
    attemptId = ContractStrings.required("attempt_id", ContractStrings.trim(attemptId));
    decisions = ImmutableCollections.listOrEmpty(decisions);
    if (decisions.size() > MAX_DECISIONS) {
      throw new ContractValidationException(
          "claim review batch exceeds " + MAX_DECISIONS + " decisions");
    }
    Set<String> claimIds = new LinkedHashSet<>();
    for (ClaimReviewDecision decision : decisions) {
      if (!claimIds.add(decision.claimId())) {
        throw new ContractValidationException(
            "duplicate claim review decision: " + decision.claimId());
      }
    }
    rawArtifactRef = ContractStrings.trim(rawArtifactRef);
    usage = usage == null ? new UsageRecord() : usage;
  }

  @Override
  public List<ClaimReviewDecision> decisions() {
    return List.copyOf(decisions);
  }
}
