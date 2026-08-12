package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record MessageEnvelope(
    @JsonProperty(value = "artifact_refs") @ContractNonNull List<String> artifactRefs,
    @JsonProperty(value = "assumptions") @ContractNonNull List<String> assumptions,
    @JsonProperty(value = "conclusion", required = true) @ContractNonNull String conclusion,
    @JsonProperty(value = "content_hash") @ContractNonNull String contentHash,
    @JsonProperty(value = "created_at") @ContractNonNull String createdAt,
    @JsonProperty(value = "dependencies") @ContractNonNull List<String> dependencies,
    @JsonProperty(value = "dependency_refs") @ContractNonNull List<JsonNode> dependencyRefs,
    @JsonProperty(value = "evidence_type", required = true) @ContractNonNull EvidenceType evidenceType,
    @JsonProperty(value = "memory_tier", required = true) @ContractNonNull MemoryTier memoryTier,
    @JsonProperty(value = "message_id") @ContractNonNull String messageId,
    @JsonProperty(value = "message_type", required = true) @ContractNonNull MessageType messageType,
    @JsonProperty(value = "normalization_confidence") @ContractNonNull Double normalizationConfidence,
    @JsonProperty(value = "normalized_statement", required = true) @ContractNonNull String normalizedStatement,
    @JsonProperty(value = "problem_hash", required = true) @ContractNonNull String problemHash,
    @JsonProperty(value = "quantifiers") @ContractNonNull List<QuantifierSpec> quantifiers,
    @JsonProperty(value = "raw_source_ref") String rawSourceRef,
    @JsonProperty(value = "round_created", required = true) @ContractNonNull Integer roundCreated,
    @JsonProperty(value = "schema_version") @ContractNonNull String schemaVersion,
    @JsonProperty(value = "scope_limitations") @ContractNonNull List<String> scopeLimitations,
    @JsonProperty(value = "source_agent_id", required = true) @ContractNonNull String sourceAgentId,
    @JsonProperty(value = "source_role", required = true) @ContractNonNull RouteRole sourceRole,
    @JsonProperty(value = "source_route_id", required = true) @ContractNonNull String sourceRouteId,
    @JsonProperty(value = "statement", required = true) @ContractNonNull String statement,
    @JsonProperty(value = "target_route_ids") @ContractNonNull List<String> targetRouteIds,
    @JsonProperty(value = "ttl_rounds") @ContractNonNull Integer ttlRounds,
    @JsonProperty(value = "variable_bindings") @ContractNonNull List<VariableBinding> variableBindings,
    @JsonProperty(value = "verification_confidence") @ContractNonNull Double verificationConfidence,
    @JsonProperty(value = "verification_status", required = true) @ContractNonNull ClaimStatus verificationStatus
) implements StrictContract {

  public MessageEnvelope {
    if (artifactRefs == null) {
      artifactRefs = List.of();
    }
    artifactRefs = ImmutableCollections.listOrEmpty(artifactRefs);
    if (assumptions == null) {
      assumptions = List.of();
    }
    assumptions = ImmutableCollections.listOrEmpty(assumptions);
    conclusion = ContractStrings.trim(conclusion);
    conclusion = ContractStrings.required("conclusion", conclusion);
    ContractValues.minimumLength("conclusion", conclusion, 1);
    if (contentHash == null) {
      contentHash = "";
    }
    contentHash = ContractStrings.trim(contentHash);
    if (createdAt == null) {
      createdAt = PythonIsoTimestampCodec.now();
    }
    createdAt = ContractStrings.trim(createdAt);
    if (dependencies == null) {
      dependencies = List.of();
    }
    dependencies = ImmutableCollections.listOrEmpty(dependencies);
    if (dependencyRefs == null) {
      dependencyRefs = List.of();
    }
    dependencyRefs = ImmutableCollections.jsonListOrEmpty(dependencyRefs);
    evidenceType = ContractValues.required("evidence_type", evidenceType);
    memoryTier = ContractValues.required("memory_tier", memoryTier);
    if (messageId == null) {
      messageId = PythonCompatibleIdGenerator.newId("msg");
    }
    messageId = ContractStrings.trim(messageId);
    messageType = ContractValues.required("message_type", messageType);
    if (normalizationConfidence == null) {
      normalizationConfidence = 0.0d;
    }
    ContractValues.minimum("normalization_confidence", normalizationConfidence, 0.0);
    ContractValues.maximum("normalization_confidence", normalizationConfidence, 1.0);
    normalizedStatement = ContractStrings.trim(normalizedStatement);
    normalizedStatement = ContractStrings.required("normalized_statement", normalizedStatement);
    ContractValues.minimumLength("normalized_statement", normalizedStatement, 1);
    problemHash = ContractStrings.trim(problemHash);
    problemHash = ContractStrings.required("problem_hash", problemHash);
    ContractValues.minimumLength("problem_hash", problemHash, 1);
    if (quantifiers == null) {
      quantifiers = List.of();
    }
    quantifiers = ImmutableCollections.listOrEmpty(quantifiers);
    rawSourceRef = ContractStrings.trim(rawSourceRef);
    roundCreated = ContractValues.required("round_created", roundCreated);
    ContractValues.minimum("round_created", roundCreated, 0);
    if (schemaVersion == null) {
      schemaVersion = "1";
    }
    schemaVersion = ContractStrings.trim(schemaVersion);
    if (scopeLimitations == null) {
      scopeLimitations = List.of();
    }
    scopeLimitations = ImmutableCollections.listOrEmpty(scopeLimitations);
    sourceAgentId = ContractStrings.trim(sourceAgentId);
    sourceAgentId = ContractStrings.required("source_agent_id", sourceAgentId);
    ContractValues.minimumLength("source_agent_id", sourceAgentId, 1);
    sourceRole = ContractValues.required("source_role", sourceRole);
    sourceRouteId = ContractStrings.trim(sourceRouteId);
    sourceRouteId = ContractStrings.required("source_route_id", sourceRouteId);
    ContractValues.minimumLength("source_route_id", sourceRouteId, 1);
    statement = ContractStrings.trim(statement);
    statement = ContractStrings.required("statement", statement);
    ContractValues.minimumLength("statement", statement, 1);
    if (targetRouteIds == null) {
      targetRouteIds = List.of();
    }
    targetRouteIds = ImmutableCollections.listOrEmpty(targetRouteIds);
    if (ttlRounds == null) {
      ttlRounds = 2;
    }
    ContractValues.minimum("ttl_rounds", ttlRounds, 1);
    if (variableBindings == null) {
      variableBindings = List.of();
    }
    variableBindings = ImmutableCollections.listOrEmpty(variableBindings);
    if (verificationConfidence == null) {
      verificationConfidence = 0.0d;
    }
    ContractValues.minimum("verification_confidence", verificationConfidence, 0.0);
    ContractValues.maximum("verification_confidence", verificationConfidence, 1.0);
    verificationStatus = ContractValues.required("verification_status", verificationStatus);
    contentHash =
        ContractHashes.checked(
            "message content_hash",
            contentHash,
            ContractHashes.messageContentHash(
                problemHash,
                sourceRouteId,
                messageType,
                normalizedStatement,
                assumptions,
                conclusion,
                quantifiers,
                dependencies,
                evidenceType,
                memoryTier));
  }

  public ObjectNode immutablePayload() {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("problem_hash", problemHash);
    payload.put("source_route_id", sourceRouteId);
    payload.put("message_type", messageType.value());
    payload.put("normalized_statement", normalizedStatement);
    payload.set("assumptions", ContractObjectMapper.toTree(assumptions));
    payload.put("conclusion", conclusion);
    payload.set("quantifiers", ContractObjectMapper.toTree(quantifiers));
    payload.set("dependencies", ContractObjectMapper.toTree(dependencies));
    payload.put("evidence_type", evidenceType.value());
    payload.put("memory_tier", memoryTier.value());
    return payload;
  }

  public String expectedContentHash() {
    return ContractHashes.messageContentHash(
        problemHash,
        sourceRouteId,
        messageType,
        normalizedStatement,
        assumptions,
        conclusion,
        quantifiers,
        dependencies,
        evidenceType,
        memoryTier);
  }

  public String expectedSemanticHash() {
    return ContractHashes.messageSemanticHash(
        assumptions, conclusion, quantifiers, variableBindings);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> artifactRefs() {
    return artifactRefs == null ? null : List.copyOf(artifactRefs);
  }

  public List<String> assumptions() {
    return assumptions == null ? null : List.copyOf(assumptions);
  }

  public List<String> dependencies() {
    return dependencies == null ? null : List.copyOf(dependencies);
  }

  public List<JsonNode> dependencyRefs() {
    return dependencyRefs == null ? null : ImmutableCollections.copyJsonList(dependencyRefs);
  }

  public List<QuantifierSpec> quantifiers() {
    return quantifiers == null ? null : List.copyOf(quantifiers);
  }

  public List<String> scopeLimitations() {
    return scopeLimitations == null ? null : List.copyOf(scopeLimitations);
  }

  public List<String> targetRouteIds() {
    return targetRouteIds == null ? null : List.copyOf(targetRouteIds);
  }

  public List<VariableBinding> variableBindings() {
    return variableBindings == null ? null : List.copyOf(variableBindings);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
