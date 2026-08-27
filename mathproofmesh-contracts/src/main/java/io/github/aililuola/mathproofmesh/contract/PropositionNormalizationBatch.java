package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record PropositionNormalizationBatch(
    @JsonProperty(value = "items") @ContractNonNull List<PropositionNormalizationItem> items
) implements StrictContract {

  public PropositionNormalizationBatch {
    if (items == null) {
      items = List.of();
    }
    items = ImmutableCollections.listOrEmpty(items);
  }

  public PropositionNormalizationBatch() {
    this(null);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<PropositionNormalizationItem> items() {
    return items == null ? null : List.copyOf(items);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
