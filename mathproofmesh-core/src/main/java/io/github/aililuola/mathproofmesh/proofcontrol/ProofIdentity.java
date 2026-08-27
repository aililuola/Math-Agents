package io.github.aililuola.mathproofmesh.proofcontrol;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stable mathematical identities that exclude control and transport metadata. */
@SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification =
        "NFC normalization is applied before Locale.ROOT case folding for stable math identity")
public final class ProofIdentity {
  private static final Pattern DIRECTIVE =
      Pattern.compile(
          "^\\[(?<kind>[a-z_]+)]"
              + "\\[STATUS:[^]]+]"
              + "\\[SOURCE:[^]]+]"
              + "\\[PREMISE_ELIGIBLE:(?:true|false)]\\s*",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern OBLIGATION_PREFIX =
      Pattern.compile(
          "^(?:unresolved\\s+gap|open\\s+proof\\s+obligation|proof\\s+obligation)"
              + "\\s*[:：]\\s*",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern DOMAIN_OBJECT_TOKEN =
      Pattern.compile("[A-Za-z][A-Za-z0-9_-]{2,}|[\\u4e00-\\u9fff]{2,}");
  private static final Set<String> DOMAIN_OBJECT_STOPWORDS =
      Set.of(
          "about",
          "after",
          "argument",
          "before",
          "carefully",
          "claim",
          "complete",
          "conclusion",
          "derive",
          "every",
          "find",
          "from",
          "holds",
          "lemma",
          "prove",
          "result",
          "route",
          "show",
          "some",
          "suitable",
          "target",
          "that",
          "theorem",
          "then",
          "this",
          "using",
          "with");

  private ProofIdentity() {}

  public static String normalizeText(String value) {
    String normalized =
        Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC);
    return normalized.strip().replaceAll("\\s+", " ");
  }

  public static String canonicalObligationStatement(String value) {
    String text = normalizeText(value);
    while (true) {
      String previous = text;
      Matcher directive = DIRECTIVE.matcher(text);
      while (directive.find()) {
        text = text.substring(directive.end()).strip();
        directive = DIRECTIVE.matcher(text);
      }
      text = OBLIGATION_PREFIX.matcher(text).replaceFirst("").strip();
      if (text.equals(previous)) {
        return normalizeText(text);
      }
    }
  }

  public static String obligationIdentityText(String value) {
    return canonicalObligationStatement(value).toLowerCase(Locale.ROOT);
  }

  public static String mathematicalHash(Map<String, ?> publicMathematics) {
    return CanonicalJson.stableHash(publicMathematics);
  }

  public static String actionKey(
      String problemHash,
      ProofControlModels.ControlActionType actionType,
      List<String> sourceIds,
      List<String> routeIds,
      List<String> targetIds,
      Map<String, ?> payload) {
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("problem_hash", ProofControlModels.required(problemHash, "problemHash"));
    identity.put("action_type", actionType.name().toLowerCase(Locale.ROOT));
    identity.put("source_record_ids", sortedDistinct(sourceIds));
    identity.put("route_ids", sortedDistinct(routeIds));
    identity.put("target_obligation_ids", sortedDistinct(targetIds));
    identity.put("payload", payload == null ? Map.of() : Map.copyOf(payload));
    return CanonicalJson.stableHash(identity);
  }

  public static List<String> canonicalStrings(List<String> values) {
    List<String> canonical = new ArrayList<>();
    if (values != null) {
      for (String value : values) {
        String normalized = obligationIdentityText(value);
        if (!normalized.isEmpty() && !canonical.contains(normalized)) {
          canonical.add(normalized);
        }
      }
    }
    canonical.sort(String::compareTo);
    return List.copyOf(canonical);
  }

  /** Extracts domain-object tokens with the same ordered semantics as the Python authority. */
  public static List<String> domainObjects(List<String> values) {
    LinkedHashSet<String> objects = new LinkedHashSet<>();
    if (values != null) {
      for (String value : values) {
        Matcher matcher = DOMAIN_OBJECT_TOKEN.matcher(normalizeText(value));
        while (matcher.find()) {
          String token = matcher.group().toLowerCase(Locale.ROOT);
          if (!DOMAIN_OBJECT_STOPWORDS.contains(token) && !token.chars().allMatch(Character::isDigit)) {
            objects.add(token);
          }
        }
      }
    }
    return List.copyOf(objects);
  }

  private static List<String> sortedDistinct(List<String> values) {
    return values == null
        ? List.of()
        : values.stream().filter(java.util.Objects::nonNull).map(String::strip)
            .filter(value -> !value.isEmpty()).distinct().sorted().toList();
  }
}
