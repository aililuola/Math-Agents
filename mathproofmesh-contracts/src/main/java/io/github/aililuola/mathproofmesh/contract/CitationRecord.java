package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record CitationRecord(
    @JsonProperty(value = "applicability_conditions") @ContractNonNull List<String> applicabilityConditions,
    @JsonProperty(value = "authors") @ContractNonNull List<String> authors,
    @JsonProperty(value = "exact_statement") String exactStatement,
    @JsonProperty(value = "location") String location,
    @JsonProperty(value = "title", required = true) @ContractNonNull String title,
    @JsonProperty(value = "url") String url,
    @JsonProperty(value = "verified") @ContractNonNull Boolean verified
) implements StrictContract {

  public CitationRecord {
    if (applicabilityConditions == null) {
      applicabilityConditions = List.of();
    }
    applicabilityConditions = ImmutableCollections.listOrEmpty(applicabilityConditions);
    if (authors == null) {
      authors = List.of();
    }
    authors = ImmutableCollections.listOrEmpty(authors);
    exactStatement = ContractStrings.trim(exactStatement);
    location = ContractStrings.trim(location);
    title = ContractStrings.trim(title);
    title = ContractStrings.required("title", title);
    url = ContractStrings.trim(url);
    if (verified == null) {
      verified = false;
    }
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> applicabilityConditions() {
    return applicabilityConditions == null ? null : List.copyOf(applicabilityConditions);
  }

  public List<String> authors() {
    return authors == null ? null : List.copyOf(authors);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
