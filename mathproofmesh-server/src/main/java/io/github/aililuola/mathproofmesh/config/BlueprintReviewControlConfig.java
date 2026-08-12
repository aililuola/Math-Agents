package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record BlueprintReviewControlConfig(
    @JsonProperty(value = "max_review_calls_per_round") Integer maxReviewCallsPerRound,
    @JsonProperty(value = "max_nodes_per_batch") Integer maxNodesPerBatch,
    @JsonProperty(value = "max_repair_rounds") Integer maxRepairRounds,
    @JsonProperty(value = "max_batch_repair_items") Integer maxBatchRepairItems
) implements ConfigModel {

  @JsonCreator
  public BlueprintReviewControlConfig(Integer maxReviewCallsPerRound, Integer maxNodesPerBatch, Integer maxRepairRounds, Integer maxBatchRepairItems) {
    if (maxReviewCallsPerRound == null) {
      maxReviewCallsPerRound = 2;
    }
    ConfigValidation.minimum("max_review_calls_per_round", maxReviewCallsPerRound, 0);
    ConfigValidation.maximum("max_review_calls_per_round", maxReviewCallsPerRound, 16);
    if (maxNodesPerBatch == null) {
      maxNodesPerBatch = 12;
    }
    ConfigValidation.minimum("max_nodes_per_batch", maxNodesPerBatch, 1);
    ConfigValidation.maximum("max_nodes_per_batch", maxNodesPerBatch, 128);
    if (maxRepairRounds == null) {
      maxRepairRounds = 2;
    }
    ConfigValidation.minimum("max_repair_rounds", maxRepairRounds, 0);
    ConfigValidation.maximum("max_repair_rounds", maxRepairRounds, 8);
    if (maxBatchRepairItems == null) {
      maxBatchRepairItems = 12;
    }
    ConfigValidation.minimum("max_batch_repair_items", maxBatchRepairItems, 1);
    ConfigValidation.maximum("max_batch_repair_items", maxBatchRepairItems, 128);
    this.maxReviewCallsPerRound = maxReviewCallsPerRound;
    this.maxNodesPerBatch = maxNodesPerBatch;
    this.maxRepairRounds = maxRepairRounds;
    this.maxBatchRepairItems = maxBatchRepairItems;
  }

  public static BlueprintReviewControlConfig defaults() {
    return new BlueprintReviewControlConfig(null, null, null, null);
  }
}
