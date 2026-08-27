package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ToolRequest(
    @JsonProperty(value = "arguments") @ContractNonNull ObjectNode arguments,
    @JsonProperty(value = "domains") @ContractNonNull ObjectNode domains,
    @JsonProperty(value = "kind", required = true)
        @ContractNonNull
        @ContractEnumValues(ComputationMethod.class)
        String kind,
    @JsonProperty(value = "max_cases") @ContractNonNull Integer maxCases,
    @JsonProperty(value = "purpose", required = true) @ContractNonNull String purpose,
    @JsonProperty(value = "request_id") @ContractNonNull String requestId
) implements StrictContract {

  public ToolRequest {
    if (arguments == null) {
      arguments = JsonNodeFactory.instance.objectNode();
    }
    arguments = ContractValues.objectOrEmpty(arguments);
    if (domains == null) {
      domains = JsonNodeFactory.instance.objectNode();
    }
    domains = ContractValues.objectOrEmpty(domains);
    kind = ContractStrings.trim(kind);
    kind = ContractStrings.required("kind", kind);
    ComputationMethod.fromValue(kind);
    if (maxCases == null) {
      maxCases = 100000;
    }
    ContractValues.minimum("max_cases", maxCases, 1);
    ContractValues.maximum("max_cases", maxCases, 100000000);
    purpose = ContractStrings.trim(purpose);
    purpose = ContractStrings.required("purpose", purpose);
    if (requestId == null) {
      requestId = PythonCompatibleIdGenerator.newId("toolreq");
    }
    requestId = ContractStrings.trim(requestId);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public ObjectNode arguments() {
    return arguments == null ? null : arguments.deepCopy();
  }

  public ObjectNode domains() {
    return domains == null ? null : domains.deepCopy();
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
