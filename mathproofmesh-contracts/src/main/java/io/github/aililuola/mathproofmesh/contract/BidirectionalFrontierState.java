package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record BidirectionalFrontierState(
    @JsonProperty(value = "backward_frontier", required = true) @ContractNonNull List<FrontierClaim> backwardFrontier,
    @JsonProperty(value = "bridge_candidates", required = true) @ContractNonNull List<FrontierBridge> bridgeCandidates,
    @JsonProperty(value = "forward_frontier", required = true) @ContractNonNull List<FrontierClaim> forwardFrontier,
    @JsonProperty(value = "round_index") @ContractNonNull Integer roundIndex,
    @JsonProperty(value = "target_obligation_id", required = true) @ContractNonNull String targetObligationId
) implements StrictContract {

  public BidirectionalFrontierState {
    backwardFrontier = ImmutableCollections.requiredList("backward_frontier", backwardFrontier);
    bridgeCandidates = ImmutableCollections.requiredList("bridge_candidates", bridgeCandidates);
    forwardFrontier = ImmutableCollections.requiredList("forward_frontier", forwardFrontier);
    if (roundIndex == null) {
      roundIndex = 0;
    }
    ContractValues.minimum("round_index", roundIndex, 0);
    targetObligationId = ContractStrings.trim(targetObligationId);
    targetObligationId = ContractStrings.required("target_obligation_id", targetObligationId);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<FrontierClaim> backwardFrontier() {
    return backwardFrontier == null ? null : List.copyOf(backwardFrontier);
  }

  public List<FrontierBridge> bridgeCandidates() {
    return bridgeCandidates == null ? null : List.copyOf(bridgeCandidates);
  }

  public List<FrontierClaim> forwardFrontier() {
    return forwardFrontier == null ? null : List.copyOf(forwardFrontier);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
