package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record EvidenceRef(
    @JsonProperty(value = "artifact_ref", required = true) @ContractNonNull String artifactRef,
    @JsonProperty(value = "content_hash") String contentHash,
    @JsonProperty(value = "section") String section,
    @JsonProperty(value = "summary") @ContractNonNull String summary
) implements StrictContract {

  public EvidenceRef {
    artifactRef = ContractStrings.trim(artifactRef);
    artifactRef = ContractStrings.required("artifact_ref", artifactRef);
    contentHash = ContractStrings.trim(contentHash);
    section = ContractStrings.trim(section);
    if (summary == null) {
      summary = "";
    }
    summary = ContractStrings.trim(summary);
  }
}
