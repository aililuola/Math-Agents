package io.github.aililuola.mathproofmesh.memory;

import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public record NegativeKnowledgeRecord(
    String negativeId,
    String problemHash,
    NegativeKnowledgeTargetType targetType,
    String primarySemanticKey,
    List<String> trustedAliasKeys,
    List<String> trustedAliasStatements,
    Set<NegativeKnowledgeKind> kinds,
    String statement,
    String normalizedStatement,
    List<String> assumptions,
    List<QuantifierSpec> quantifiers,
    List<VariableBinding> variableBindings,
    List<String> scopeLimitations,
    String polarity,
    List<String> evidenceMessageIds,
    int firstSeenRound,
    Integer expiresAfterRound,
    long version) {

  public NegativeKnowledgeRecord {
    negativeId = require(negativeId, "negativeId");
    problemHash = require(problemHash, "problemHash");
    targetType = java.util.Objects.requireNonNull(targetType, "targetType");
    primarySemanticKey = require(primarySemanticKey, "primarySemanticKey");
    trustedAliasKeys = copy(trustedAliasKeys);
    trustedAliasStatements = copy(trustedAliasStatements);
    kinds =
        kinds == null || kinds.isEmpty()
            ? Set.of()
            : Collections.unmodifiableSet(EnumSet.copyOf(kinds));
    if (kinds.isEmpty()) {
      throw new IllegalArgumentException("kinds must not be empty");
    }
    statement = require(statement, "statement");
    normalizedStatement = NegativeKnowledgeSemanticKey.normalizeStatement(normalizedStatement);
    assumptions = copy(assumptions);
    quantifiers = quantifiers == null ? List.of() : List.copyOf(quantifiers);
    variableBindings = variableBindings == null ? List.of() : List.copyOf(variableBindings);
    scopeLimitations = copy(scopeLimitations);
    polarity = NegativeKnowledgeSemanticKey.normalizePolarity(polarity);
    evidenceMessageIds = copy(evidenceMessageIds);
    if (firstSeenRound < 0 || version < 1) {
      throw new IllegalArgumentException("negative knowledge round and version are invalid");
    }
    boolean permanent = kinds.stream().anyMatch(NegativeKnowledgeKind::permanent);
    if (permanent && expiresAfterRound != null) {
      throw new IllegalArgumentException("permanent negative knowledge cannot expire");
    }
    if (!permanent && (expiresAfterRound == null || expiresAfterRound < firstSeenRound)) {
      throw new IllegalArgumentException("temporary negative knowledge requires a valid expiry");
    }
  }

  public NegativeKnowledgeRecord(
      String negativeId,
      String problemHash,
      NegativeKnowledgeTargetType targetType,
      String primarySemanticKey,
      List<String> trustedAliasKeys,
      List<String> trustedAliasStatements,
      Set<NegativeKnowledgeKind> kinds,
      String statement,
      String normalizedStatement,
      List<String> assumptions,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> variableBindings,
      List<String> scopeLimitations,
      List<String> evidenceMessageIds,
      int firstSeenRound,
      Integer expiresAfterRound,
      long version) {
    this(
        negativeId,
        problemHash,
        targetType,
        primarySemanticKey,
        trustedAliasKeys,
        trustedAliasStatements,
        kinds,
        statement,
        normalizedStatement,
        assumptions,
        quantifiers,
        variableBindings,
        scopeLimitations,
        NegativeKnowledgeSemanticKey.UNSPECIFIED_POLARITY,
        evidenceMessageIds,
        firstSeenRound,
        expiresAfterRound,
        version);
  }

  public boolean permanent() {
    return kinds.stream().anyMatch(NegativeKnowledgeKind::permanent);
  }

  public boolean activeAt(int currentRound) {
    if (currentRound < 0) {
      throw new IllegalArgumentException("currentRound must be non-negative");
    }
    return permanent() || currentRound <= expiresAfterRound;
  }

  public String contextKey() {
    return NegativeKnowledgeSemanticKey.contextKey(
        problemHash,
        targetType,
        assumptions,
        quantifiers,
        variableBindings,
        scopeLimitations,
        polarity);
  }

  String contextKeyIgnoringPolarity() {
    return NegativeKnowledgeSemanticKey.contextKeyIgnoringPolarity(
        problemHash, targetType, assumptions, quantifiers, variableBindings, scopeLimitations);
  }

  @Override
  public List<String> trustedAliasKeys() {
    return List.copyOf(trustedAliasKeys);
  }

  @Override
  public Set<NegativeKnowledgeKind> kinds() {
    return Collections.unmodifiableSet(EnumSet.copyOf(kinds));
  }

  @Override
  public List<String> trustedAliasStatements() {
    return List.copyOf(trustedAliasStatements);
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

  @Override
  public List<String> evidenceMessageIds() {
    return List.copyOf(evidenceMessageIds);
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
