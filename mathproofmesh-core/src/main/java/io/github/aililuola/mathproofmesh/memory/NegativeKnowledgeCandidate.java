package io.github.aililuola.mathproofmesh.memory;

import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;

public record NegativeKnowledgeCandidate(
    String problemHash,
    NegativeKnowledgeTargetType targetType,
    String statement,
    String normalizedStatement,
    List<String> assumptions,
    List<QuantifierSpec> quantifiers,
    List<VariableBinding> variableBindings,
    List<String> scopeLimitations,
    NegativeKnowledgeSurface surface,
    NegativeCandidateIntent intent) {

  public NegativeKnowledgeCandidate {
    problemHash = require(problemHash, "problemHash");
    targetType = java.util.Objects.requireNonNull(targetType, "targetType");
    statement = require(statement, "statement");
    normalizedStatement =
        normalizedStatement == null || normalizedStatement.isBlank()
            ? NegativeKnowledgeSemanticKey.normalizeStatement(statement)
            : NegativeKnowledgeSemanticKey.normalizeStatement(normalizedStatement);
    assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    quantifiers = quantifiers == null ? List.of() : List.copyOf(quantifiers);
    variableBindings = variableBindings == null ? List.of() : List.copyOf(variableBindings);
    scopeLimitations = scopeLimitations == null ? List.of() : List.copyOf(scopeLimitations);
    surface = java.util.Objects.requireNonNull(surface, "surface");
    intent = java.util.Objects.requireNonNull(intent, "intent");
  }

  public String semanticKey() {
    return NegativeKnowledgeSemanticKey.semanticKey(
        problemHash,
        targetType,
        normalizedStatement,
        assumptions,
        quantifiers,
        variableBindings,
        scopeLimitations);
  }

  public String contextKey() {
    return NegativeKnowledgeSemanticKey.contextKey(
        problemHash, targetType, assumptions, quantifiers, variableBindings, scopeLimitations);
  }

  @Override
  public List<String> assumptions() {
    return List.copyOf(assumptions);
  }

  @Override
  public List<QuantifierSpec> quantifiers() {
    return List.copyOf(quantifiers);
  }

  @Override
  public List<VariableBinding> variableBindings() {
    return List.copyOf(variableBindings);
  }

  @Override
  public List<String> scopeLimitations() {
    return List.copyOf(scopeLimitations);
  }

  private static String require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }
}
