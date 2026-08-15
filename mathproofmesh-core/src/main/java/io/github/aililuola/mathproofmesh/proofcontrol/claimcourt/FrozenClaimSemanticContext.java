package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;

public record FrozenClaimSemanticContext(
    List<QuantifierSpec> quantifiers,
    List<VariableBinding> variableBindings,
    List<String> scopeLimitations,
    String polarity) {
  public FrozenClaimSemanticContext {
    quantifiers = ClaimCourtValues.copy(quantifiers);
    variableBindings = ClaimCourtValues.copy(variableBindings);
    scopeLimitations = ClaimCourtValues.copy(scopeLimitations);
    polarity = ClaimCourtValues.required(polarity, "polarity");
  }

  public static FrozenClaimSemanticContext root(List<String> scopeLimitations) {
    return new FrozenClaimSemanticContext(
        List.of(), List.of(), scopeLimitations, "positive");
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
