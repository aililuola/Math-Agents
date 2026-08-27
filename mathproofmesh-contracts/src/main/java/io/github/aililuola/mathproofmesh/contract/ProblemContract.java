package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ProblemContract(
    @JsonProperty(value = "allowed_tools") @ContractNonNull List<String> allowedTools,
    @JsonProperty(value = "canonical_statement") @ContractNonNull String canonicalStatement,
    @JsonProperty(value = "created_at") @ContractNonNull String createdAt,
    @JsonProperty(value = "definitions") @ContractNonNull List<String> definitions,
    @JsonProperty(value = "deliverables") @ContractNonNull List<String> deliverables,
    @JsonProperty(value = "exact_statement", required = true) @ContractNonNull String exactStatement,
    @JsonProperty(value = "goal_hash") @ContractNonNull String goalHash,
    @JsonProperty(value = "hard_constraints") @ContractNonNull List<String> hardConstraints,
    @JsonProperty(value = "integrity_hash") @ContractNonNull String integrityHash,
    @JsonIgnore String interpretationAgentId,
    @JsonProperty(value = "interpretation_confidence") @ContractNonNull Double interpretationConfidence,
    @JsonIgnore String interpretationRawRef,
    @JsonProperty(value = "interpretation_reasons") @ContractNonNull List<String> interpretationReasons,
    @JsonProperty(value = "interpretation_source") @ContractNonNull String interpretationSource,
    @JsonProperty(value = "normalized_statement", required = true) @ContractNonNull String normalizedStatement,
    @JsonProperty(value = "original_statement") @ContractNonNull String originalStatement,
    @JsonProperty(value = "output_language") @ContractNonNull String outputLanguage,
    @JsonProperty(value = "problem_id") @ContractNonNull String problemId,
    @JsonProperty(value = "problem_kind") @ContractNonNull ProblemKind problemKind,
    @JsonProperty(value = "semantic_view") ProblemSemanticView semanticView,
    @JsonProperty(value = "task_requirements") @ContractNonNull List<TaskRequirement> taskRequirements
) implements StrictContract {

  public ProblemContract {
    if (allowedTools == null) {
      allowedTools = List.of();
    }
    allowedTools = ImmutableCollections.listOrEmpty(allowedTools);
    if (canonicalStatement == null) {
      canonicalStatement = "";
    }
    canonicalStatement = ContractStrings.trim(canonicalStatement);
    if (createdAt == null) {
      createdAt = PythonIsoTimestampCodec.now();
    }
    createdAt = ContractStrings.trim(createdAt);
    if (definitions == null) {
      definitions = List.of();
    }
    definitions = ImmutableCollections.listOrEmpty(definitions);
    if (deliverables == null) {
      deliverables = List.of();
    }
    deliverables = ImmutableCollections.listOrEmpty(deliverables);
    exactStatement = ContractStrings.trim(exactStatement);
    exactStatement = ContractStrings.required("exact_statement", exactStatement);
    if (goalHash == null) {
      goalHash = "";
    }
    goalHash = ContractStrings.trim(goalHash);
    if (hardConstraints == null) {
      hardConstraints = List.of();
    }
    hardConstraints = ImmutableCollections.listOrEmpty(hardConstraints);
    if (integrityHash == null) {
      integrityHash = "";
    }
    integrityHash = ContractStrings.trim(integrityHash);
    interpretationAgentId = ContractStrings.trim(interpretationAgentId);
    if (interpretationConfidence == null) {
      interpretationConfidence = 1.0d;
    }
    ContractValues.minimum("interpretation_confidence", interpretationConfidence, 0.0);
    ContractValues.maximum("interpretation_confidence", interpretationConfidence, 1.0);
    interpretationRawRef = ContractStrings.trim(interpretationRawRef);
    if (interpretationReasons == null) {
      interpretationReasons = List.of();
    }
    interpretationReasons = ImmutableCollections.listOrEmpty(interpretationReasons);
    if (interpretationSource == null) {
      interpretationSource = "original";
    }
    interpretationSource = ContractStrings.trim(interpretationSource);
    ContractValues.oneOf("interpretation_source", interpretationSource, "original", "user_confirmed", "auto_assumed");
    normalizedStatement = ContractStrings.trim(normalizedStatement);
    normalizedStatement = ContractStrings.required("normalized_statement", normalizedStatement);
    if (originalStatement == null) {
      originalStatement = "";
    }
    originalStatement = ContractStrings.trim(originalStatement);
    if (outputLanguage == null) {
      outputLanguage = "zh-CN";
    }
    outputLanguage = ContractStrings.trim(outputLanguage);
    if (problemId == null) {
      problemId = PythonCompatibleIdGenerator.newId("problem");
    }
    problemId = ContractStrings.trim(problemId);
    if (problemKind == null) {
      problemKind = ProblemKind.UNKNOWN;
    }
    if (taskRequirements == null) {
      taskRequirements = List.of(TaskRequirement.PROOF);
    }
    taskRequirements = ImmutableCollections.listOrEmpty(taskRequirements);
    originalStatement = originalStatement.isEmpty() ? exactStatement : originalStatement;
    canonicalStatement = canonicalStatement.isEmpty() ? exactStatement : canonicalStatement;
    if (!exactStatement.equals(canonicalStatement)) {
      throw new ContractValidationException(
          "exact_statement must equal the frozen canonical_statement");
    }
    if ("original".equals(interpretationSource)
        && !originalStatement.equals(canonicalStatement)) {
      throw new ContractValidationException(
          "an original interpretation cannot change the submitted statement");
    }
    String expected = CanonicalJson.stableHash(canonicalStatement);
    integrityHash = ContractHashes.checked("integrity_hash", integrityHash, expected);
    goalHash = ContractHashes.checked("goal_hash", goalHash, expected);
  }

  public ProblemContract withTaskContract(
      List<TaskRequirement> requirements, List<String> requestedDeliverables) {
    return new ProblemContract(
        allowedTools,
        canonicalStatement,
        createdAt,
        definitions,
        requestedDeliverables,
        exactStatement,
        goalHash,
        hardConstraints,
        integrityHash,
        interpretationAgentId,
        interpretationConfidence,
        interpretationRawRef,
        interpretationReasons,
        interpretationSource,
        normalizedStatement,
        originalStatement,
        outputLanguage,
        problemId,
        problemKind,
        semanticView,
        requirements);
  }

  public ProblemContract withSemanticView(ProblemSemanticView auditedSemanticView) {
    if (auditedSemanticView != null
        && !goalHash.equals(auditedSemanticView.sourceStatementHash())) {
      throw new ContractValidationException(
          "semantic_view source_statement_hash must equal the frozen goal_hash");
    }
    return new ProblemContract(
        allowedTools,
        canonicalStatement,
        createdAt,
        definitions,
        deliverables,
        exactStatement,
        goalHash,
        hardConstraints,
        integrityHash,
        interpretationAgentId,
        interpretationConfidence,
        interpretationRawRef,
        interpretationReasons,
        interpretationSource,
        normalizedStatement,
        originalStatement,
        outputLanguage,
        problemId,
        problemKind,
        auditedSemanticView,
        taskRequirements);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> allowedTools() {
    return allowedTools == null ? null : List.copyOf(allowedTools);
  }

  public List<String> definitions() {
    return definitions == null ? null : List.copyOf(definitions);
  }

  public List<String> deliverables() {
    return deliverables == null ? null : List.copyOf(deliverables);
  }

  public List<String> hardConstraints() {
    return hardConstraints == null ? null : List.copyOf(hardConstraints);
  }

  public List<String> interpretationReasons() {
    return interpretationReasons == null ? null : List.copyOf(interpretationReasons);
  }

  public List<TaskRequirement> taskRequirements() {
    return taskRequirements == null ? null : List.copyOf(taskRequirements);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
