package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ProofGraphEdge(
    @JsonProperty(value = "edge_id") @ContractNonNull String edgeId,
    @JsonProperty(value = "edge_type", required = true) @ContractNonNull GraphEdgeType edgeType,
    @JsonProperty(value = "evidence_message_id") String evidenceMessageId,
    @JsonProperty(value = "route_id") String routeId,
    @JsonProperty(value = "source_id", required = true) @ContractNonNull String sourceId,
    @JsonProperty(value = "target_id", required = true) @ContractNonNull String targetId
) implements StrictContract {

  public ProofGraphEdge {
    if (edgeId == null) {
      edgeId = PythonCompatibleIdGenerator.newId("pge");
    }
    edgeId = ContractStrings.trim(edgeId);
    edgeType = ContractValues.required("edge_type", edgeType);
    evidenceMessageId = ContractStrings.trim(evidenceMessageId);
    routeId = ContractStrings.trim(routeId);
    sourceId = ContractStrings.trim(sourceId);
    sourceId = ContractStrings.required("source_id", sourceId);
    targetId = ContractStrings.trim(targetId);
    targetId = ContractStrings.required("target_id", targetId);
  }
}
