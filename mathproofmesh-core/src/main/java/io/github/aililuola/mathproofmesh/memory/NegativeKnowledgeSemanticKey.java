package io.github.aililuola.mathproofmesh.memory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import io.github.aililuola.mathproofmesh.topology.SparseTopologyRouter;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification =
        "Semantic keys apply NFKC first and then use Locale.ROOT for deterministic case folding.")
final class NegativeKnowledgeSemanticKey {
  private static final SparseTopologyRouter NORMALIZER = new SparseTopologyRouter();
  private static final Pattern CANONICAL_VARIABLE = Pattern.compile("\\bv\\d+\\b");

  private NegativeKnowledgeSemanticKey() {}

  static String normalizeStatement(String value) {
    String normalized = Normalizer.normalize(require(value, "statement"), Normalizer.Form.NFKC);
    List<String> variables = new ArrayList<>();
    Matcher matcher = CANONICAL_VARIABLE.matcher(normalized);
    StringBuilder protectedValue = new StringBuilder(normalized.length());
    while (matcher.find()) {
      String placeholder = "canonicalvariableplaceholder" + variables.size() + "token";
      variables.add(matcher.group());
      matcher.appendReplacement(protectedValue, placeholder);
    }
    matcher.appendTail(protectedValue);
    String result = NORMALIZER.mathNormalize(protectedValue.toString());
    for (int index = 0; index < variables.size(); index++) {
      result =
          result.replace(
              "canonicalvariableplaceholder" + index + "token", variables.get(index));
    }
    return compact(result.toLowerCase(Locale.ROOT));
  }

  static String semanticKey(
      String problemHash,
      NegativeKnowledgeTargetType targetType,
      String statement,
      List<String> assumptions,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> variableBindings,
      List<String> scopeLimitations) {
    Map<String, Object> payload = contextPayload(
        problemHash, targetType, assumptions, quantifiers, variableBindings, scopeLimitations);
    payload.put("normalized_statement", normalizeStatement(statement));
    return CanonicalJson.stableHash(payload);
  }

  static String contextKey(
      String problemHash,
      NegativeKnowledgeTargetType targetType,
      List<String> assumptions,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> variableBindings,
      List<String> scopeLimitations) {
    return CanonicalJson.stableHash(
        contextPayload(
            problemHash, targetType, assumptions, quantifiers, variableBindings, scopeLimitations));
  }

  static double similarity(String left, String right) {
    return NORMALIZER.mathSimilarity(normalizeStatement(left), normalizeStatement(right));
  }

  private static Map<String, Object> contextPayload(
      String problemHash,
      NegativeKnowledgeTargetType targetType,
      List<String> assumptions,
      List<QuantifierSpec> quantifiers,
      List<VariableBinding> variableBindings,
      List<String> scopeLimitations) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("problem_hash", require(problemHash, "problemHash"));
    payload.put("target_type", java.util.Objects.requireNonNull(targetType, "targetType").name());
    payload.put("assumptions", normalizedStrings(assumptions));
    payload.put("quantifiers", normalizedQuantifiers(quantifiers));
    payload.put("variable_bindings", normalizedBindings(variableBindings));
    payload.put("scope_limitations", normalizedStrings(scopeLimitations));
    return payload;
  }

  private static List<String> normalizedStrings(List<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .map(NegativeKnowledgeSemanticKey::normalizeText)
        .filter(value -> !value.isBlank())
        .distinct()
        .sorted()
        .toList();
  }

  private static List<Map<String, Object>> normalizedQuantifiers(List<QuantifierSpec> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .sorted(Comparator.comparingInt(QuantifierSpec::order))
        .map(
            value -> {
              Map<String, Object> item = new LinkedHashMap<>();
              item.put("display_name", normalizeText(value.displayName()));
              item.put("domain", normalizeText(value.domain()));
              item.put("kind", normalizeText(value.kind()));
              item.put("order", value.order());
              item.put("restrictions", normalizedStrings(value.restrictions()));
              item.put("variable_id", normalizeText(value.variableId()));
              return Map.copyOf(item);
            })
        .toList();
  }

  private static List<Map<String, Object>> normalizedBindings(List<VariableBinding> values) {
    if (values == null) {
      return List.of();
    }
    List<VariableBinding> sorted = new ArrayList<>(values);
    sorted.sort(Comparator.comparing(VariableBinding::variableId));
    return sorted.stream()
        .map(
            value -> {
              Map<String, Object> item = new LinkedHashMap<>();
              item.put("aliases", normalizedStrings(value.aliases()));
              item.put("display_name", normalizeText(value.displayName()));
              item.put("domain", normalizeText(value.domain()));
              item.put("owner_scope", normalizeText(value.ownerScope()));
              item.put("variable_id", normalizeText(value.variableId()));
              return Map.copyOf(item);
            })
        .toList();
  }

  private static String normalizeText(String value) {
    if (value == null) {
      return "";
    }
    return compact(Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
  }

  private static String compact(String value) {
    return value.trim().replaceAll("\\s+", " ");
  }

  private static String require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }
}
