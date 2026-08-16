package io.github.aililuola.mathproofmesh.memory;

import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;

public final class DeterministicNegativeSeed {
  private final String seedId;
  private final NegativeKnowledgeTargetType targetType;
  private final String statement;
  private final List<String> trustedAliases;
  private final List<String> assumptions;
  private final List<QuantifierSpec> quantifiers;
  private final List<VariableBinding> variableBindings;
  private final List<String> scopeLimitations;
  private final String polarity;
  private final String reason;

  private DeterministicNegativeSeed(
      String seedId,
      NegativeKnowledgeTargetType targetType,
      String statement,
      List<String> trustedAliases,
      List<String> assumptions,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> variableBindings,
      List<String> scopeLimitations,
      String polarity,
      String reason) {
    this.seedId = require(seedId, "seedId");
    this.targetType = java.util.Objects.requireNonNull(targetType, "targetType");
    this.statement = require(statement, "statement");
    this.trustedAliases = copy(trustedAliases);
    this.assumptions = copy(assumptions);
    this.quantifiers = quantifiers == null ? List.of() : List.copyOf(quantifiers);
    this.variableBindings = variableBindings == null ? List.of() : List.copyOf(variableBindings);
    this.scopeLimitations = copy(scopeLimitations);
    this.polarity = NegativeKnowledgeSemanticKey.normalizePolarity(polarity);
    this.reason = require(reason, "reason");
  }

  public static DeterministicNegativeSeed trustedCodeSeed(
      String seedId,
      NegativeKnowledgeTargetType targetType,
      String statement,
      List<String> trustedAliases,
      List<String> assumptions,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> variableBindings,
      List<String> scopeLimitations,
      String reason) {
    return trustedCodeSeed(
        seedId,
        targetType,
        statement,
        trustedAliases,
        assumptions,
        quantifiers,
        variableBindings,
        scopeLimitations,
        NegativeKnowledgeSemanticKey.UNSPECIFIED_POLARITY,
        reason);
  }

  public static DeterministicNegativeSeed trustedCodeSeed(
      String seedId,
      NegativeKnowledgeTargetType targetType,
      String statement,
      List<String> trustedAliases,
      List<String> assumptions,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> variableBindings,
      List<String> scopeLimitations,
      String polarity,
      String reason) {
    return new DeterministicNegativeSeed(
        seedId,
        targetType,
        statement,
        trustedAliases,
        assumptions,
        quantifiers,
        variableBindings,
        scopeLimitations,
        polarity,
        reason);
  }

  public String seedId() {
    return seedId;
  }

  public NegativeKnowledgeTargetType targetType() {
    return targetType;
  }

  public String statement() {
    return statement;
  }

  public List<String> trustedAliases() {
    return List.copyOf(trustedAliases);
  }

  public List<String> assumptions() {
    return List.copyOf(assumptions);
  }

  public List<QuantifierSpec> quantifiers() {
    return List.copyOf(quantifiers);
  }

  public List<VariableBinding> variableBindings() {
    return List.copyOf(variableBindings);
  }

  public List<String> scopeLimitations() {
    return List.copyOf(scopeLimitations);
  }

  public String polarity() {
    return polarity;
  }

  public String reason() {
    return reason;
  }

  private static List<String> copy(List<String> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  private static String require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }
}
