package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record FormalCertificateRef(
    @JsonProperty(value = "artifact_ref") String artifactRef,
    @JsonProperty(value = "backend", required = true) @ContractNonNull String backend,
    @JsonProperty(value = "certificate_id") @ContractNonNull String certificateId,
    @JsonProperty(value = "compiler_output_hash") String compilerOutputHash,
    @JsonProperty(value = "diagnostics") @ContractNonNull List<String> diagnostics,
    @JsonProperty(value = "packet_id", required = true) @ContractNonNull String packetId,
    @JsonProperty(value = "statement_hash", required = true) @ContractNonNull String statementHash,
    @JsonProperty(value = "status", required = true) @ContractNonNull String status
) implements StrictContract {

  public FormalCertificateRef {
    artifactRef = ContractStrings.trim(artifactRef);
    backend = ContractStrings.trim(backend);
    backend = ContractStrings.required("backend", backend);
    if (certificateId == null) {
      certificateId = PythonCompatibleIdGenerator.newId("formal_cert");
    }
    certificateId = ContractStrings.trim(certificateId);
    compilerOutputHash = ContractStrings.trim(compilerOutputHash);
    if (diagnostics == null) {
      diagnostics = List.of();
    }
    diagnostics = ImmutableCollections.listOrEmpty(diagnostics);
    packetId = ContractStrings.trim(packetId);
    packetId = ContractStrings.required("packet_id", packetId);
    statementHash = ContractStrings.trim(statementHash);
    statementHash = ContractStrings.required("statement_hash", statementHash);
    status = ContractStrings.trim(status);
    status = ContractStrings.required("status", status);
    ContractValues.oneOf("status", status, "verified", "failed", "pending", "unavailable");
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> diagnostics() {
    return diagnostics == null ? null : List.copyOf(diagnostics);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
