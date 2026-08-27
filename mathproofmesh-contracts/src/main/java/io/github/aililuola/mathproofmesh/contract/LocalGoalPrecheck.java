package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record LocalGoalPrecheck(
    @JsonProperty(value = "reasons") @ContractNonNull List<String> reasons,
    @JsonProperty(value = "rule_ids") @ContractNonNull List<String> ruleIds,
    @JsonProperty(value = "status", required = true) @ContractNonNull String status
) implements StrictContract {

  public LocalGoalPrecheck {
    if (reasons == null) {
      reasons = List.of();
    }
    reasons = ImmutableCollections.listOrEmpty(reasons);
    if (ruleIds == null) {
      ruleIds = List.of();
    }
    ruleIds = ImmutableCollections.listOrEmpty(ruleIds);
    status = ContractStrings.trim(status);
    status = ContractStrings.required("status", status);
    ContractValues.oneOf("status", status, "clear", "model_review_required");
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> reasons() {
    return reasons == null ? null : List.copyOf(reasons);
  }

  public List<String> ruleIds() {
    return ruleIds == null ? null : List.copyOf(ruleIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
