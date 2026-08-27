package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** One bounded public checkpoint frame embedded in provider reasoning or the final envelope. */
public record ResearchCheckpointFrame(
    @JsonProperty(value = "frame_sequence", required = true) int frameSequence,
    @JsonProperty(value = "summary", required = true) @ContractNonNull String summary,
    @JsonProperty(value = "findings", required = true) @ContractNonNull List<ResearchFindingDraft> findings)
    implements StrictContract {

  public ResearchCheckpointFrame {
    ContractValues.minimum("frame_sequence", frameSequence, 0);
    summary = ContractStrings.required("summary", ContractStrings.trim(summary));
    ContractValues.minimumLength("summary", summary, 1);
    ContractValues.maximumLength("summary", summary, 2_048);
    findings = findings == null ? List.of() : ImmutableCollections.listOrEmpty(findings);
    ContractValues.maximumSize("findings", findings, 8);
  }

  @Override
  public List<ResearchFindingDraft> findings() {
    return List.copyOf(findings);
  }
}
