package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Compiles context-bound claim identities and exact alpha-equivalent evidence keys. */
public final class CriticalClaimKeyCompiler {
  public CriticalClaimSemanticKey compile(String problemHash, CriticalClaim claim) {
    return compile(problemHash, claim, CriticalClaimContext.empty());
  }

  public CriticalClaimSemanticKey compile(
      String problemHash, CriticalClaim claim, CriticalClaimContext context) {
    java.util.Objects.requireNonNull(context, "context");
    return compile(
        problemHash,
        claim,
        context.assumptions(),
        context.quantifiers(),
        context.scopeLimitations(),
        context.variableBindings(),
        context.polarity());
  }

  public CriticalClaimSemanticKey compile(
      String problemHash,
      CriticalClaim claim,
      List<String> assumptions,
      List<QuantifierSpec> quantifiers,
      List<String> scopeLimitations,
      String polarity) {
    return compile(
        problemHash,
        claim,
        assumptions,
        quantifiers,
        scopeLimitations,
        List.of(),
        polarity);
  }

  public CriticalClaimSemanticKey compile(
      String problemHash,
      CriticalClaim claim,
      List<String> assumptions,
      List<QuantifierSpec> quantifiers,
      List<String> scopeLimitations,
      List<VariableBinding> variableBindings,
      String polarity) {
    java.util.Objects.requireNonNull(claim, "claim");
    Map<String, String> alphaNames = alphaNames(quantifiers, variableBindings);
    String normalized = normalizeWithVariables(claim.statement(), alphaNames);
    List<String> normalizedAssumptions =
        normalizedSetWithVariables(assumptions, alphaNames);
    List<String> orderedQuantifiers = normalizedQuantifiers(quantifiers, alphaNames);
    List<String> normalizedBindings = normalizedBindings(variableBindings, alphaNames);
    List<String> normalizedScope = normalizedSetWithVariables(scopeLimitations, alphaNames);
    String normalizedPolarity = StrategySemanticNormalizer.normalize(polarity);
    String necessity = StrategySemanticNormalizer.normalize(claim.necessity());
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("problem_hash", StrategySemanticNormalizer.require(problemHash, "problemHash"));
    identity.put("statement", commonModeStatement(normalized));
    identity.put("assumptions", normalizedAssumptions);
    identity.put("ordered_quantifiers", orderedQuantifiers);
    identity.put("variable_bindings", normalizedBindings);
    identity.put("scope_limitations", normalizedScope);
    identity.put("polarity", normalizedPolarity);
    identity.put("necessity", necessity);
    return new CriticalClaimSemanticKey(
        problemHash,
        normalized,
        normalizedAssumptions,
        orderedQuantifiers,
        normalizedBindings,
        normalizedScope,
        normalizedPolarity,
        necessity,
        StrategySemanticNormalizer.hash(identity));
  }

  public boolean exactEvidenceMatch(
      CriticalClaimSemanticKey key,
      String statement,
      CriticalClaimContext evidenceContext) {
    java.util.Objects.requireNonNull(key, "key");
    java.util.Objects.requireNonNull(evidenceContext, "evidenceContext");
    Map<String, String> alphaNames =
        alphaNames(evidenceContext.quantifiers(), evidenceContext.variableBindings());
    return key.normalizedStatement().equals(normalizeWithVariables(statement, alphaNames))
        && key.assumptions()
            .equals(normalizedSetWithVariables(evidenceContext.assumptions(), alphaNames))
        && key.orderedQuantifiers()
            .equals(normalizedQuantifiers(evidenceContext.quantifiers(), alphaNames))
        && key.variableBindings()
            .equals(normalizedBindings(evidenceContext.variableBindings(), alphaNames))
        && key.scopeLimitations()
            .equals(normalizedSetWithVariables(evidenceContext.scopeLimitations(), alphaNames))
        && key.polarity().equals(StrategySemanticNormalizer.normalize(evidenceContext.polarity()));
  }

  private static Map<String, String> alphaNames(
      List<QuantifierSpec> quantifiers, List<VariableBinding> bindings) {
    Map<String, String> result = new LinkedHashMap<>();
    List<QuantifierSpec> ordered =
        quantifiers == null
            ? List.of()
            : quantifiers.stream().sorted(Comparator.comparingInt(QuantifierSpec::order)).toList();
    for (int index = 0; index < ordered.size(); index++) {
      QuantifierSpec quantifier = ordered.get(index);
      String canonical = "q" + index;
      result.put(quantifier.variableId(), canonical);
      result.put(quantifier.displayName(), canonical);
    }
    if (bindings != null) {
      int offset = result.values().stream().distinct().toList().size();
      for (VariableBinding binding : bindings) {
        String canonical = result.get(binding.variableId());
        if (canonical == null) {
          canonical = "v" + offset++;
          result.put(binding.variableId(), canonical);
        }
        result.put(binding.displayName(), canonical);
        for (String alias : binding.aliases()) {
          result.put(alias, canonical);
        }
      }
    }
    return Map.copyOf(result);
  }

  private static List<String> normalizedQuantifiers(
      List<QuantifierSpec> quantifiers, Map<String, String> alphaNames) {
    if (quantifiers == null) {
      return List.of();
    }
    List<QuantifierSpec> ordered =
        quantifiers.stream().sorted(Comparator.comparingInt(QuantifierSpec::order)).toList();
    List<String> result = new ArrayList<>(ordered.size());
    for (int index = 0; index < ordered.size(); index++) {
      QuantifierSpec value = ordered.get(index);
      result.add(
          index
              + ":"
              + StrategySemanticNormalizer.normalize(value.kind())
              + ":"
              + normalizeWithVariables(value.domain(), alphaNames)
              + ":q"
              + index
              + ":"
              + String.join(",", normalizedSetWithVariables(value.restrictions(), alphaNames)));
    }
    return List.copyOf(result);
  }

  private static List<String> normalizedBindings(
      List<VariableBinding> bindings, Map<String, String> alphaNames) {
    if (bindings == null) {
      return List.of();
    }
    return bindings.stream()
        .map(
            binding ->
                alphaNames.getOrDefault(
                        binding.variableId(),
                        StrategySemanticNormalizer.normalize(binding.variableId()))
                    + ":"
                    + normalizeWithVariables(binding.domain(), alphaNames)
                    + ":"
                    + normalizeWithVariables(binding.ownerScope(), alphaNames))
        .sorted()
        .toList();
  }

  private static List<String> normalizedSetWithVariables(
      List<String> values, Map<String, String> alphaNames) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .map(value -> normalizeWithVariables(value, alphaNames))
        .filter(value -> !value.isBlank())
        .distinct()
        .sorted()
        .toList();
  }

  private static String normalizeWithVariables(
      String value, Map<String, String> alphaNames) {
    String normalized = StrategySemanticNormalizer.normalize(value);
    List<Map.Entry<String, String>> replacements =
        alphaNames.entrySet().stream()
            .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
            .sorted(
                Map.Entry.<String, String>comparingByKey(
                    Comparator.comparingInt(String::length).reversed()))
            .toList();
    for (Map.Entry<String, String> replacement : replacements) {
      normalized =
          normalized.replaceAll(
              "(?<![a-z0-9_])"
                  + Pattern.quote(replacement.getKey().toLowerCase(Locale.ROOT))
                  + "(?![a-z0-9_])",
              replacement.getValue());
    }
    return normalized;
  }

  private static String commonModeStatement(String normalized) {
    String result = normalized;
    result = result.replace("pendant vertex", "leaf");
    result = result.replace("terminal vertex", "leaf");
    result = result.replace("graph order", "vertex count");
    result = result.replace("number of vertices", "vertex count");
    result = result.replace("pruning", "removing");
    result = result.replace("deleting", "removing");
    result = result.replace("delete", "remove");
    result = result.replace("prune", "remove");
    result = result.replace("strip", "remove");
    return result;
  }
}
