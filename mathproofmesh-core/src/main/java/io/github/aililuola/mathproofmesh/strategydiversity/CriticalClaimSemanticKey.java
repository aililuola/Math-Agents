package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.List;

public record CriticalClaimSemanticKey(
    String problemHash,
    String normalizedStatement,
    List<String> assumptions,
    List<String> orderedQuantifiers,
    List<String> variableBindings,
    List<String> scopeLimitations,
    String polarity,
    String necessity,
    String semanticKey) {
  public CriticalClaimSemanticKey {
    problemHash = StrategySemanticNormalizer.require(problemHash, "problemHash");
    normalizedStatement =
        StrategySemanticNormalizer.require(normalizedStatement, "normalizedStatement");
    assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    orderedQuantifiers =
        orderedQuantifiers == null ? List.of() : List.copyOf(orderedQuantifiers);
    variableBindings =
        variableBindings == null ? List.of() : List.copyOf(variableBindings);
    scopeLimitations =
        scopeLimitations == null ? List.of() : List.copyOf(scopeLimitations);
    polarity = StrategySemanticNormalizer.require(polarity, "polarity");
    necessity = StrategySemanticNormalizer.require(necessity, "necessity");
    semanticKey = StrategySemanticNormalizer.require(semanticKey, "semanticKey");
  }

  @Override
  public List<String> assumptions() {
    return List.copyOf(assumptions);
  }

  @Override
  public List<String> orderedQuantifiers() {
    return List.copyOf(orderedQuantifiers);
  }

  @Override
  public List<String> variableBindings() {
    return List.copyOf(variableBindings);
  }

  @Override
  public List<String> scopeLimitations() {
    return List.copyOf(scopeLimitations);
  }
}
