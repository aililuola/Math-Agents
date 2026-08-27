package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record FinalTopologyConfig(
    @JsonProperty(value = "freeze_graph_before_synthesis") Boolean freezeGraphBeforeSynthesis,
    @JsonProperty(value = "blind_structural_review") Boolean blindStructuralReview,
    @JsonProperty(value = "blind_detailed_review") Boolean blindDetailedReview,
    @JsonProperty(value = "remove_agent_identity") Boolean removeAgentIdentity,
    @JsonProperty(value = "remove_route_ranking") Boolean removeRouteRanking,
    @JsonProperty(value = "remove_self_confidence") Boolean removeSelfConfidence
) implements ConfigModel {

  @JsonCreator
  public FinalTopologyConfig(Boolean freezeGraphBeforeSynthesis, Boolean blindStructuralReview, Boolean blindDetailedReview, Boolean removeAgentIdentity, Boolean removeRouteRanking, Boolean removeSelfConfidence) {
    if (freezeGraphBeforeSynthesis == null) {
      freezeGraphBeforeSynthesis = true;
    }
    if (blindStructuralReview == null) {
      blindStructuralReview = true;
    }
    if (blindDetailedReview == null) {
      blindDetailedReview = true;
    }
    if (removeAgentIdentity == null) {
      removeAgentIdentity = true;
    }
    if (removeRouteRanking == null) {
      removeRouteRanking = true;
    }
    if (removeSelfConfidence == null) {
      removeSelfConfidence = true;
    }
    this.freezeGraphBeforeSynthesis = freezeGraphBeforeSynthesis;
    this.blindStructuralReview = blindStructuralReview;
    this.blindDetailedReview = blindDetailedReview;
    this.removeAgentIdentity = removeAgentIdentity;
    this.removeRouteRanking = removeRouteRanking;
    this.removeSelfConfidence = removeSelfConfidence;
  }

  public static FinalTopologyConfig defaults() {
    return new FinalTopologyConfig(null, null, null, null, null, null);
  }
}
