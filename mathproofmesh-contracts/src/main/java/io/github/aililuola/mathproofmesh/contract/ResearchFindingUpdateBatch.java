package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record ResearchFindingUpdateBatch(
    @JsonProperty(value = "dispositions") @ContractNonNull List<ResearchFindingDisposition> dispositions)
    implements StrictContract {

  public ResearchFindingUpdateBatch {
    dispositions =
        dispositions == null ? List.of() : ImmutableCollections.listOrEmpty(dispositions);
    Set<String> ids = new HashSet<>();
    for (ResearchFindingDisposition disposition : dispositions) {
      if (!ids.add(disposition.findingId())) {
        throw new ContractValidationException(
            "finding_updates contains duplicate finding_id: " + disposition.findingId());
      }
    }
  }

  public static ResearchFindingUpdateBatch empty() {
    return new ResearchFindingUpdateBatch(List.of());
  }

  @Override
  public List<ResearchFindingDisposition> dispositions() {
    return List.copyOf(dispositions);
  }
}
