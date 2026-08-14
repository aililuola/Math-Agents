package io.github.aililuola.mathproofmesh.proofgraph;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "IMPROPER_UNICODE"},
    justification =
        "Collections are immutable, and NFKC plus Locale.ROOT is the deterministic semantic-key boundary.")
public record ObligationSemanticSignature(
    String problemHash,
    ObligationKind kind,
    String normalizedStatement,
    List<String> normalizedAssumptions,
    List<String> orderedQuantifiers,
    String polarity,
    List<String> scopeMarkers,
    String signatureHash) {

  public ObligationSemanticSignature {
    problemHash = require(problemHash, "problemHash");
    kind = java.util.Objects.requireNonNull(kind, "kind");
    normalizedStatement = require(normalizedStatement, "normalizedStatement");
    normalizedAssumptions = immutable(normalizedAssumptions);
    orderedQuantifiers = immutable(orderedQuantifiers);
    polarity = require(polarity, "polarity");
    scopeMarkers = immutable(scopeMarkers);
    String computed =
        CanonicalJson.stableHash(
            Map.of(
                "problem_hash", problemHash,
                "kind", kind.name(),
                "normalized_statement", normalizedStatement,
                "normalized_assumptions", normalizedAssumptions,
                "ordered_quantifiers", orderedQuantifiers,
                "polarity", polarity,
                "scope_markers", scopeMarkers));
    if (signatureHash == null || signatureHash.isBlank()) {
      signatureHash = computed;
    } else if (!computed.equals(signatureHash)) {
      throw new IllegalArgumentException("signatureHash does not match semantic signature");
    }
  }

  public static ObligationSemanticSignature from(
      ProofObligation obligation, ObligationCreationContext context) {
    java.util.Objects.requireNonNull(obligation, "obligation");
    java.util.Objects.requireNonNull(context, "context");
    if (!obligation.problemHash().equals(context.problemHash())) {
      throw new IllegalArgumentException("obligation creation context problemHash mismatch");
    }
    List<QuantifierSpec> quantifiers =
        obligation.quantifiers().stream()
            .sorted(Comparator.comparingInt(QuantifierSpec::order))
            .toList();
    Map<String, String> roles = new LinkedHashMap<>(context.trustedSymbolRoles());
    for (int index = 0; index < quantifiers.size(); index++) {
      QuantifierSpec quantifier = quantifiers.get(index);
      roles.put(quantifier.variableId(), "bound" + index);
      roles.put(quantifier.displayName(), "bound" + index);
    }
    String statement = normalizeMath(obligation.normalizedStatement(), roles);
    List<String> assumptions =
        obligation.assumptions().stream()
            .map(value -> normalizeMath(value, roles))
            .filter(value -> !value.isBlank())
            .distinct()
            .sorted()
            .toList();
    List<String> ordered =
        quantifiers.stream()
            .map(
                value ->
                    value.order()
                        + ":"
                        + normalizeText(value.kind())
                        + ":"
                        + normalizeMath(value.domain(), context.trustedSymbolRoles())
                        + ":"
                        + value.restrictions().stream()
                            .map(item -> normalizeMath(item, roles))
                            .sorted()
                            .toList())
            .toList();
    String polarity =
        context.polarity().isBlank()
            ? inferPolarity(statement)
            : normalizeText(context.polarity());
    List<String> scope =
        context.scopeMarkers().isEmpty()
            ? inferScope(statement, quantifiers)
            : context.scopeMarkers().stream()
                .map(ObligationSemanticSignature::normalizeText)
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    return new ObligationSemanticSignature(
        obligation.problemHash(),
        obligation.kind(),
        statement,
        assumptions,
        ordered,
        polarity,
        scope,
        "");
  }

  public static ObligationIdentityStrength identityStrength(
      ProofObligation left,
      ObligationCreationContext leftContext,
      ProofObligation right,
      ObligationCreationContext rightContext) {
    ObligationSemanticSignature a = from(left, leftContext);
    ObligationSemanticSignature b = from(right, rightContext);
    if (sameExceptPolarity(a, b) && !a.polarity().equals(b.polarity())) {
      return ObligationIdentityStrength.CONFLICT;
    }
    if (a.signatureHash().equals(b.signatureHash())) {
      String exactLeft = normalizeMath(left.normalizedStatement(), leftContext.trustedSymbolRoles());
      String exactRight =
          normalizeMath(right.normalizedStatement(), rightContext.trustedSymbolRoles());
      return exactLeft.equals(exactRight)
          ? ObligationIdentityStrength.EXACT
          : ObligationIdentityStrength.TRUSTED_ALPHA_EQUIVALENT;
    }
    if (sameContext(a, b)
        && MathTextSimilarity.statementSimilarity(
                a.normalizedStatement(), b.normalizedStatement())
            >= 0.72d) {
      return ObligationIdentityStrength.POSSIBLE_EQUIVALENT;
    }
    return ObligationIdentityStrength.DISTINCT;
  }

  static boolean sameContext(
      ObligationSemanticSignature left, ObligationSemanticSignature right) {
    return left.problemHash().equals(right.problemHash())
        && left.kind() == right.kind()
        && left.normalizedAssumptions().equals(right.normalizedAssumptions())
        && left.orderedQuantifiers().equals(right.orderedQuantifiers())
        && left.polarity().equals(right.polarity())
        && left.scopeMarkers().equals(right.scopeMarkers());
  }

  private static boolean sameExceptPolarity(
      ObligationSemanticSignature left, ObligationSemanticSignature right) {
    return left.problemHash().equals(right.problemHash())
        && left.kind() == right.kind()
        && left.normalizedStatement().equals(right.normalizedStatement())
        && left.normalizedAssumptions().equals(right.normalizedAssumptions())
        && left.orderedQuantifiers().equals(right.orderedQuantifiers())
        && left.scopeMarkers().equals(right.scopeMarkers());
  }

  static String normalizeMath(String value, Map<String, String> symbolRoles) {
    String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
    normalized =
        normalized
            .replace("\\leq", "<=")
            .replace("\\le", "<=")
            .replace("\\geq", ">=")
            .replace("\\ge", ">=")
            .replace("\\neq", "!=")
            .replace("\\ne", "!=")
            .replace("\\forall", "forall")
            .replace("\\exists", "exists")
            .replace("\\in", " in ")
            .replace("\u2264", "<=")
            .replace("\u2265", ">=")
            .replace("\u2260", "!=")
            .replace("\u2200", "forall")
            .replace("\u2203", "exists")
            .replace("\u2208", " in ")
            .replace("$", "");
    normalized = normalized.replaceAll("_\\{([^{}]+)}", "_$1").replace("{", "").replace("}", "");
    List<Map.Entry<String, String>> entries = new ArrayList<>(symbolRoles.entrySet());
    entries.sort(
        Comparator.<Map.Entry<String, String>>comparingInt(entry -> entry.getKey().length())
            .reversed()
            .thenComparing(Map.Entry::getKey));
    for (Map.Entry<String, String> entry : entries) {
      String symbol = normalizeText(entry.getKey());
      String role = normalizeText(entry.getValue());
      if (symbol.isBlank() || role.isBlank()) {
        continue;
      }
      normalized =
          Pattern.compile(
                  "(?<![\\p{L}\\p{N}])" + Pattern.quote(symbol) + "(?![\\p{L}\\p{N}])",
                  Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
              .matcher(normalized)
              .replaceAll(role);
    }
    normalized = normalizeText(normalized);
    normalized = normalized.replaceAll("\\s*(<=|>=|!=|=|<|>)\\s*", "$1");
    normalized = normalized.replaceAll("\\s*([(),;:+\\-*/])\\s*", "$1");
    return normalized;
  }

  private static List<String> inferScope(
      String statement, List<QuantifierSpec> quantifiers) {
    if (statement.contains("eventually")
        || statement.contains("sufficiently large")
        || statement.contains("for all large")) {
      return List.of("eventual");
    }
    if (statement.contains("for every")
        || statement.contains("for all")
        || quantifiers.stream().anyMatch(value -> "forall".equals(value.kind()))) {
      return List.of("all");
    }
    return List.of("unspecified");
  }

  private static String inferPolarity(String statement) {
    return statement.startsWith("not ")
            || statement.contains("!=")
            || statement.startsWith("no ")
        ? "negative"
        : "positive";
  }

  private static String normalizeText(String value) {
    return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .strip()
        .replaceAll("\\s+", " ");
  }

  private static List<String> immutable(List<String> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  private static String require(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
