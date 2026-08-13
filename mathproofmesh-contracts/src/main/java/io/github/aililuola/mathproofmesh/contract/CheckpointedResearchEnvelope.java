package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Public checkpoint sidecar plus the stage's unchanged strict result payload. */
public record CheckpointedResearchEnvelope(
    @JsonProperty(value = "public_checkpoint") ResearchCheckpointFrame publicCheckpoint,
    @JsonProperty(value = "finding_updates") ResearchFindingUpdateBatch findingUpdates,
    @JsonProperty(value = "result", required = true) @ContractNonNull JsonNode result)
    implements StrictContract {

  public CheckpointedResearchEnvelope {
    findingUpdates =
        findingUpdates == null ? ResearchFindingUpdateBatch.empty() : findingUpdates;
    result = ContractValues.copyJson(ContractValues.required("result", result));
  }

  @Override
  public JsonNode result() {
    return result.deepCopy();
  }
}
