package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;

/** Server-owned semantic context bound to a strategy's load-bearing claim. */
public record CriticalClaimContext(
    List<String> assumptions,
    List<QuantifierSpec> quantifiers,
    List<String> scopeLimitations,
    List<VariableBinding> variableBindings,
    String polarity) {
  public CriticalClaimContext {
    assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    quantifiers = quantifiers == null ? List.of() : List.copyOf(quantifiers);
    scopeLimitations = scopeLimitations == null ? List.of() : List.copyOf(scopeLimitations);
    variableBindings = variableBindings == null ? List.of() : List.copyOf(variableBindings);
    polarity =
        polarity == null || polarity.isBlank()
            ? "positive"
            : StrategySemanticNormalizer.normalize(polarity);
  }

  public static CriticalClaimContext empty() {
    return new CriticalClaimContext(List.of(), List.of(), List.of(), List.of(), "positive");
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
  public List<String> scopeLimitations() {
    return List.copyOf(scopeLimitations);
  }

  @Override
  public List<VariableBinding> variableBindings() {
    return List.copyOf(variableBindings);
  }
}
