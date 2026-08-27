package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ToolResult(
    @JsonProperty(value = "error") String error,
    @JsonProperty(value = "evidence_ref") EvidenceRef evidenceRef,
    @JsonProperty(value = "kind", required = true) @ContractNonNull String kind,
    @JsonProperty(value = "ok", required = true) @ContractNonNull Boolean ok,
    @JsonProperty(value = "request_id", required = true) @ContractNonNull String requestId,
    @JsonProperty(value = "result") JsonNode result
) implements StrictContract {

  public ToolResult {
    error = ContractStrings.trim(error);
    kind = ContractStrings.trim(kind);
    kind = ContractStrings.required("kind", kind);
    ok = ContractValues.required("ok", ok);
    requestId = ContractStrings.trim(requestId);
    requestId = ContractStrings.required("request_id", requestId);
    result = ContractValues.copyJson(result);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public JsonNode result() {
    return result == null ? null : result.deepCopy();
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
