package io.github.aililuola.mathproofmesh.proofgraph;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

final class MathTextSimilarity {
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern PUNCTUATION =
      Pattern.compile("[\\.,;:!?\\uFF0C\\u3002\\uFF1B\\uFF1A\\uFF01\\uFF1F]");
  private static final Pattern LATIN_TOKEN = Pattern.compile("[a-z0-9_]+");

  private MathTextSimilarity() {}

  static String normalize(String value) {
    String folded = asciiLower(value == null ? "" : value.trim());
    return PUNCTUATION.matcher(WHITESPACE.matcher(folded).replaceAll(" "))
        .replaceAll("");
  }

  static double statementSimilarity(String left, String right) {
    if (normalize(left).equals(normalize(right))) {
      return 1.0;
    }
    return jaccard(tokens(left), tokens(right));
  }

  static double jaccard(Iterable<String> left, Iterable<String> right) {
    Set<String> a = new LinkedHashSet<>();
    left.forEach(a::add);
    Set<String> b = new LinkedHashSet<>();
    right.forEach(b::add);
    if (a.isEmpty() && b.isEmpty()) {
      return 1.0;
    }
    if (a.isEmpty() || b.isEmpty()) {
      return 0.0;
    }
    Set<String> intersection = new LinkedHashSet<>(a);
    intersection.retainAll(b);
    Set<String> union = new LinkedHashSet<>(a);
    union.addAll(b);
    return (double) intersection.size() / union.size();
  }

  private static Set<String> tokens(String value) {
    String normalized = normalize(value);
    Set<String> result = new LinkedHashSet<>();
    LATIN_TOKEN.matcher(normalized).results()
        .map(java.util.regex.MatchResult::group)
        .forEach(result::add);
    StringBuilder cjk = new StringBuilder();
    normalized.codePoints()
        .filter(codePoint -> codePoint >= 0x3400 && codePoint <= 0x9fff)
        .forEach(cjk::appendCodePoint);
    int[] codePoints = cjk.toString().codePoints().toArray();
    for (int index = 0; index + 1 < codePoints.length; index++) {
      result.add(new String(codePoints, index, 2));
    }
    return Set.copyOf(result);
  }

  private static String asciiLower(String value) {
    StringBuilder result = new StringBuilder(value.length());
    value.codePoints()
        .map(codePoint -> codePoint >= 'A' && codePoint <= 'Z' ? codePoint + 32 : codePoint)
        .forEach(result::appendCodePoint);
    return result.toString();
  }
}
