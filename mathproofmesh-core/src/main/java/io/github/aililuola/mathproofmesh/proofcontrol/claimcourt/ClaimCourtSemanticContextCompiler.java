package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Converts the server-compiled Claim-local context into an immutable Court context. */
public final class ClaimCourtSemanticContextCompiler {
  public FrozenClaimSemanticContext compile(
      ClaimCard claim, FrozenClaimSemanticContext authoritativeContext) {
    java.util.Objects.requireNonNull(claim, "claim");
    if (authoritativeContext == null) {
      throw new IllegalArgumentException(
          "MISSING_CLAIM_COURT_SEMANTIC_CONTEXT_BINDING:" + claim.claimId());
    }
    validateVariableIdentity(authoritativeContext.quantifiers(), authoritativeContext.variableBindings());
    LinkedHashSet<String> assumptions =
        new LinkedHashSet<>(authoritativeContext.assumptions());
    assumptions.addAll(claim.assumptions());
    LinkedHashSet<String> scope =
        new LinkedHashSet<>(authoritativeContext.scopeLimitations());
    scope.addAll(claim.scopeLimitations());
    return new FrozenClaimSemanticContext(
        List.copyOf(assumptions),
        authoritativeContext.quantifiers(),
        authoritativeContext.variableBindings(),
        List.copyOf(scope),
        authoritativeContext.polarity());
  }

  private static void validateVariableIdentity(
      List<QuantifierSpec> quantifiers, List<VariableBinding> bindings) {
    Set<String> bindingIds = new LinkedHashSet<>();
    for (VariableBinding binding : bindings) {
      if (!bindingIds.add(binding.variableId())) {
        throw new IllegalArgumentException(
            "DUPLICATE_CLAIM_COURT_VARIABLE_BINDING:" + binding.variableId());
      }
    }
    for (QuantifierSpec quantifier : quantifiers) {
      if (!bindingIds.contains(quantifier.variableId())) {
        throw new IllegalArgumentException(
            "UNBOUND_CLAIM_COURT_QUANTIFIER:" + quantifier.variableId());
      }
    }
  }
}
