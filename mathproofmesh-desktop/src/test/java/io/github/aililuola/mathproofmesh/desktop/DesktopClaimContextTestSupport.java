package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.CriticalClaimContextBinding;
import io.github.aililuola.mathproofmesh.contract.MechanismOperationDeclaration;
import io.github.aililuola.mathproofmesh.contract.MechanismOperationKind;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimContext;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimKeyCompiler;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimSemanticKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DesktopClaimContextTestSupport {
  static final String STATEMENT = "P(x) holds.";
  static final List<String> IDS =
      List.of("under-h", "without-h", "forall-x", "exists-x", "negative-p");

  private DesktopClaimContextTestSupport() {}

  static StrategyCard strategy() {
    List<CriticalClaim> claims =
        IDS.stream()
            .map(
                id ->
                    new CriticalClaim(
                        id,
                        List.of(),
                        "Check the exact local context.",
                        "required",
                        null,
                        STATEMENT,
                        "needs_check"))
            .toList();
    return new StrategyCard(
        null,
        "Bind each load-bearing claim to its own local context.",
        List.of(),
        List.of(),
        List.of(),
        "Use five context-sensitive instances of one predicate.",
        claims,
        0.2d,
        0.8d,
        List.of(),
        "Check each local context independently.",
        "The mechanism exercises server-validated claim-local bindings.",
        null,
        null,
        List.of(),
        List.of("The ambient structure is finite."),
        "per-claim-context-strategy",
        List.of("claim-context"),
        "Per-claim context binding",
        List.of(
            new MechanismOperationDeclaration(
                "contextual-reduction",
                MechanismOperationKind.REDUCTION,
                List.of("@roots"),
                List.of("@direct_targets"))),
        List.of(
            binding("under-h", List.of("H(x)"), List.of(), List.of(), "positive"),
            binding("without-h", List.of(), List.of(), List.of(), "positive"),
            binding(
                "forall-x",
                List.of(),
                List.of(quantifier("forall", "forall-local-x")),
                List.of(variable("forall-local-x")),
                "positive"),
            binding(
                "exists-x",
                List.of(),
                List.of(quantifier("exists", "exists-local-x")),
                List.of(variable("exists-local-x")),
                "positive"),
            binding("negative-p", List.of(), List.of(), List.of(), "negative")));
  }

  static Map<String, CriticalClaimSemanticKey> keys(
      Map<String, CriticalClaimContext> contexts) {
    StrategyCard strategy = strategy();
    CriticalClaimKeyCompiler compiler = new CriticalClaimKeyCompiler();
    Map<String, CriticalClaimSemanticKey> result = new LinkedHashMap<>();
    strategy
        .criticalClaims()
        .forEach(
            claim ->
                result.put(
                    claim.claimId(),
                    compiler.compile(
                        DesktopStrategyPortfolioTestHarness.PROBLEM_HASH,
                        claim,
                        contexts.get(claim.claimId()))));
    return Map.copyOf(result);
  }

  private static CriticalClaimContextBinding binding(
      String claimId,
      List<String> assumptions,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> bindings,
      String polarity) {
    return new CriticalClaimContextBinding(
        claimId,
        "@claim",
        List.of(),
        assumptions,
        quantifiers,
        bindings,
        List.of(),
        polarity);
  }

  private static QuantifierSpec quantifier(String kind, String variableId) {
    return new QuantifierSpec("x", "claim-local domain", kind, 0, List.of(), variableId);
  }

  private static VariableBinding variable(String variableId) {
    return new VariableBinding(
        List.of("x"), "x", "claim-local domain", "critical-claim", variableId);
  }
}
