package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record CandidateConjectureBatch(
    @JsonProperty(value = "candidate_conjectures", required = true) @ContractNonNull List<CandidateConjecture> candidateConjectures
) implements StrictContract {

  public CandidateConjectureBatch {
    candidateConjectures = ImmutableCollections.requiredList("candidate_conjectures", candidateConjectures);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<CandidateConjecture> candidateConjectures() {
    return candidateConjectures == null ? null : List.copyOf(candidateConjectures);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
