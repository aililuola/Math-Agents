package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record BrokerConfig(
    @JsonProperty(value = "contradiction_detection") Boolean contradictionDetection,
    @JsonProperty(value = "bridge_detection") Boolean bridgeDetection,
    @JsonProperty(value = "duplicate_route_detection") Boolean duplicateRouteDetection,
    @JsonProperty(value = "contradiction_similarity_threshold") Double contradictionSimilarityThreshold,
    @JsonProperty(value = "bridge_similarity_threshold") Double bridgeSimilarityThreshold,
    @JsonProperty(value = "duplicate_strategy_threshold") Double duplicateStrategyThreshold,
    @JsonProperty(value = "min_routes_for_bridge") Integer minRoutesForBridge,
    @JsonProperty(value = "max_bridge_tasks_per_round") Integer maxBridgeTasksPerRound,
    @JsonProperty(value = "max_conflict_tasks_per_round") Integer maxConflictTasksPerRound,
    @JsonProperty(value = "deterministic_matching_first") Boolean deterministicMatchingFirst,
    @JsonProperty(value = "use_llm_matching_on_ambiguous") Boolean useLlmMatchingOnAmbiguous,
    @JsonProperty(value = "ambiguous_match_low") Double ambiguousMatchLow,
    @JsonProperty(value = "ambiguous_match_high") Double ambiguousMatchHigh
) implements ConfigModel {

  @JsonCreator
  public BrokerConfig(Boolean contradictionDetection, Boolean bridgeDetection, Boolean duplicateRouteDetection, Double contradictionSimilarityThreshold, Double bridgeSimilarityThreshold, Double duplicateStrategyThreshold, Integer minRoutesForBridge, Integer maxBridgeTasksPerRound, Integer maxConflictTasksPerRound, Boolean deterministicMatchingFirst, Boolean useLlmMatchingOnAmbiguous, Double ambiguousMatchLow, Double ambiguousMatchHigh) {
    if (contradictionDetection == null) {
      contradictionDetection = true;
    }
    if (bridgeDetection == null) {
      bridgeDetection = true;
    }
    if (duplicateRouteDetection == null) {
      duplicateRouteDetection = true;
    }
    if (contradictionSimilarityThreshold == null) {
      contradictionSimilarityThreshold = 0.82d;
    }
    ConfigValidation.minimum("contradiction_similarity_threshold", contradictionSimilarityThreshold, 0.0d);
    ConfigValidation.maximum("contradiction_similarity_threshold", contradictionSimilarityThreshold, 1.0d);
    if (bridgeSimilarityThreshold == null) {
      bridgeSimilarityThreshold = 0.78d;
    }
    ConfigValidation.minimum("bridge_similarity_threshold", bridgeSimilarityThreshold, 0.0d);
    ConfigValidation.maximum("bridge_similarity_threshold", bridgeSimilarityThreshold, 1.0d);
    if (duplicateStrategyThreshold == null) {
      duplicateStrategyThreshold = 0.84d;
    }
    ConfigValidation.minimum("duplicate_strategy_threshold", duplicateStrategyThreshold, 0.0d);
    ConfigValidation.maximum("duplicate_strategy_threshold", duplicateStrategyThreshold, 1.0d);
    if (minRoutesForBridge == null) {
      minRoutesForBridge = 2;
    }
    ConfigValidation.minimum("min_routes_for_bridge", minRoutesForBridge, 2);
    ConfigValidation.maximum("min_routes_for_bridge", minRoutesForBridge, 32);
    if (maxBridgeTasksPerRound == null) {
      maxBridgeTasksPerRound = 2;
    }
    ConfigValidation.minimum("max_bridge_tasks_per_round", maxBridgeTasksPerRound, 0);
    ConfigValidation.maximum("max_bridge_tasks_per_round", maxBridgeTasksPerRound, 32);
    if (maxConflictTasksPerRound == null) {
      maxConflictTasksPerRound = 2;
    }
    ConfigValidation.minimum("max_conflict_tasks_per_round", maxConflictTasksPerRound, 0);
    ConfigValidation.maximum("max_conflict_tasks_per_round", maxConflictTasksPerRound, 32);
    if (deterministicMatchingFirst == null) {
      deterministicMatchingFirst = true;
    }
    if (useLlmMatchingOnAmbiguous == null) {
      useLlmMatchingOnAmbiguous = true;
    }
    if (ambiguousMatchLow == null) {
      ambiguousMatchLow = 0.55d;
    }
    ConfigValidation.minimum("ambiguous_match_low", ambiguousMatchLow, 0.0d);
    ConfigValidation.maximum("ambiguous_match_low", ambiguousMatchLow, 1.0d);
    if (ambiguousMatchHigh == null) {
      ambiguousMatchHigh = 0.82d;
    }
    ConfigValidation.minimum("ambiguous_match_high", ambiguousMatchHigh, 0.0d);
    ConfigValidation.maximum("ambiguous_match_high", ambiguousMatchHigh, 1.0d);
    this.contradictionDetection = contradictionDetection;
    this.bridgeDetection = bridgeDetection;
    this.duplicateRouteDetection = duplicateRouteDetection;
    this.contradictionSimilarityThreshold = contradictionSimilarityThreshold;
    this.bridgeSimilarityThreshold = bridgeSimilarityThreshold;
    this.duplicateStrategyThreshold = duplicateStrategyThreshold;
    this.minRoutesForBridge = minRoutesForBridge;
    this.maxBridgeTasksPerRound = maxBridgeTasksPerRound;
    this.maxConflictTasksPerRound = maxConflictTasksPerRound;
    this.deterministicMatchingFirst = deterministicMatchingFirst;
    this.useLlmMatchingOnAmbiguous = useLlmMatchingOnAmbiguous;
    this.ambiguousMatchLow = ambiguousMatchLow;
    this.ambiguousMatchHigh = ambiguousMatchHigh;
    ConfigInvariants.validate(this);
  }

  public static BrokerConfig defaults() {
    return new BrokerConfig(null, null, null, null, null, null, null, null, null, null, null, null, null);
  }
}
