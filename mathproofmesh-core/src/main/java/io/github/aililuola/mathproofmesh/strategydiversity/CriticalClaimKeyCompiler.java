package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CriticalClaimKeyCompiler {
  public CriticalClaimSemanticKey compile(String problemHash, CriticalClaim claim) {
    return compile(problemHash, claim, List.of(), List.of(), List.of(), "positive");
  }

  public CriticalClaimSemanticKey compile(
      String problemHash,
      CriticalClaim claim,
      List<String> assumptions,
      List<QuantifierSpec> quantifiers,
      List<String> scopeLimitations,
      String polarity) {
    java.util.Objects.requireNonNull(claim, "claim");
    String normalized = StrategySemanticNormalizer.normalize(claim.statement());
    List<String> normalizedAssumptions = StrategySemanticNormalizer.normalizedSet(assumptions);
    List<String> orderedQuantifiers =
        quantifiers == null
            ? List.of()
            : quantifiers.stream()
                .sorted(Comparator.comparingInt(QuantifierSpec::order))
                .map(
                    value ->
                        value.order()
                            + ":"
                            + StrategySemanticNormalizer.normalize(value.kind())
                            + ":"
                            + StrategySemanticNormalizer.normalize(value.domain())
                            + ":"
                            + StrategySemanticNormalizer.normalize(value.variableId())
                            + ":"
                            + String.join(",", StrategySemanticNormalizer.normalizedSet(value.restrictions())))
                .toList();
    List<String> normalizedScope = StrategySemanticNormalizer.normalizedSet(scopeLimitations);
    String normalizedPolarity = StrategySemanticNormalizer.normalize(polarity);
    String necessity = StrategySemanticNormalizer.normalize(claim.necessity());
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("problem_hash", StrategySemanticNormalizer.require(problemHash, "problemHash"));
    identity.put("statement", normalized);
    identity.put("assumptions", normalizedAssumptions);
    identity.put("ordered_quantifiers", orderedQuantifiers);
    identity.put("scope_limitations", normalizedScope);
    identity.put("polarity", normalizedPolarity);
    identity.put("necessity", necessity);
    return new CriticalClaimSemanticKey(
        problemHash,
        normalized,
        normalizedAssumptions,
        orderedQuantifiers,
        normalizedScope,
        normalizedPolarity,
        necessity,
        StrategySemanticNormalizer.hash(identity));
  }
}
