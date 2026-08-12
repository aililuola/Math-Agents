package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record DeferredExperimentRequest(
    @JsonProperty(value = "defer_count") @ContractNonNull Integer deferCount,
    @JsonProperty(value = "first_deferred_at") @ContractNonNull String firstDeferredAt,
    @JsonProperty(value = "last_evaluated_at") @ContractNonNull String lastEvaluatedAt,
    @JsonProperty(value = "latest_decision", required = true) @ContractNonNull ComputationDecision latestDecision,
    @JsonProperty(value = "path_id", required = true) @ContractNonNull String pathId,
    @JsonProperty(value = "spec", required = true) @ContractNonNull ExperimentSpec spec
) implements StrictContract {

  public DeferredExperimentRequest {
    if (deferCount == null) {
      deferCount = 1;
    }
    ContractValues.minimum("defer_count", deferCount, 1);
    if (firstDeferredAt == null) {
      firstDeferredAt = PythonIsoTimestampCodec.now();
    }
    firstDeferredAt = ContractStrings.trim(firstDeferredAt);
    if (lastEvaluatedAt == null) {
      lastEvaluatedAt = PythonIsoTimestampCodec.now();
    }
    lastEvaluatedAt = ContractStrings.trim(lastEvaluatedAt);
    latestDecision = ContractValues.required("latest_decision", latestDecision);
    pathId = ContractStrings.trim(pathId);
    pathId = ContractStrings.required("path_id", pathId);
    spec = ContractValues.required("spec", spec);
  }
}
