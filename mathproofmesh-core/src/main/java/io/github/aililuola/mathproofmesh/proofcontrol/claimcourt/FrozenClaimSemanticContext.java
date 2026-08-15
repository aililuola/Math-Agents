package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;

public record FrozenClaimSemanticContext(
    List<String> assumptions,
    List<QuantifierSpec> quantifiers,
    List<VariableBinding> variableBindings,
    List<String> scopeLimitations,
    String polarity) {
  public FrozenClaimSemanticContext {
    assumptions = ClaimCourtValues.copy(assumptions);
    quantifiers = ClaimCourtValues.copy(quantifiers);
    variableBindings = ClaimCourtValues.copy(variableBindings);
    scopeLimitations = ClaimCourtValues.copy(scopeLimitations);
    polarity = ClaimCourtValues.required(polarity, "polarity");
  }

  public static FrozenClaimSemanticContext root(List<String> scopeLimitations) {
    return new FrozenClaimSemanticContext(
        List.of(), List.of(), List.of(), scopeLimitations, "positive");
  }

  public static FrozenClaimSemanticContext legacyIncomplete(
      List<String> scopeLimitations) {
    java.util.LinkedHashSet<String> scope =
        new java.util.LinkedHashSet<>(ClaimCourtValues.copy(scopeLimitations));
    scope.add("LEGACY_INCOMPLETE_SEMANTIC_CONTEXT");
    return new FrozenClaimSemanticContext(
        List.of(), List.of(), List.of(), List.copyOf(scope), "positive");
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
}
