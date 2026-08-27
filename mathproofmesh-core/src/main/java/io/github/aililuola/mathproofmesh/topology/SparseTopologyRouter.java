package io.github.aililuola.mathproofmesh.topology;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SparseTopologyRouter {
  public double jaccardSimilarity(String left, String right) {
    Set<String> leftFeatures = features(left);
    Set<String> rightFeatures = features(right);
    if (leftFeatures.isEmpty() && rightFeatures.isEmpty()) {
      return 1.0;
    }
    if (leftFeatures.isEmpty() || rightFeatures.isEmpty()) {
      return 0.0;
    }
    Set<String> intersection = new LinkedHashSet<>(leftFeatures);
    intersection.retainAll(rightFeatures);
    Set<String> union = new LinkedHashSet<>(leftFeatures);
    union.addAll(rightFeatures);
    return (double) intersection.size() / union.size();
  }

  public Map<String, List<String>> selectSparseRouteNeighbors(
      Map<String, String> routeMechanisms, int maxNeighbors) {
    if (maxNeighbors < 0) {
      throw new IllegalArgumentException("maxNeighbors cannot be negative");
    }
    Map<String, List<String>> result = new LinkedHashMap<>();
    routeMechanisms.forEach(
        (routeId, mechanism) -> {
          List<String> selected =
              routeMechanisms.entrySet().stream()
                  .filter(candidate -> !candidate.getKey().equals(routeId))
                  .sorted(
                      Comparator.<Map.Entry<String, String>>comparingDouble(
                              candidate ->
                                  -jaccardSimilarity(mechanism, candidate.getValue()))
                          .thenComparing(Map.Entry::getKey))
                  .limit(maxNeighbors)
                  .map(Map.Entry::getKey)
                  .toList();
          result.put(routeId, selected);
        });
    return Map.copyOf(result);
  }

  public List<String> selectDiverseStrategies(Map<String, String> strategies, int count) {
    if (count < 0) {
      throw new IllegalArgumentException("count cannot be negative");
    }
    if (count == 0) {
      return List.of();
    }
    if (strategies.size() <= count) {
      return List.copyOf(strategies.keySet());
    }
    List<String> remaining = new ArrayList<>(strategies.keySet());
    remaining.sort(String::compareTo);
    List<String> selected = new ArrayList<>();
    selected.add(remaining.removeFirst());
    while (!remaining.isEmpty() && selected.size() < count) {
      String next =
          remaining.stream()
              .max(
                  Comparator.<String>comparingDouble(
                          candidate ->
                              selected.stream()
                                  .mapToDouble(
                                      existing ->
                                          1.0
                                              - jaccardSimilarity(
                                                  strategies.get(candidate),
                                                  strategies.get(existing)))
                                  .min()
                                  .orElse(0.0))
                      .thenComparing(Comparator.reverseOrder()))
              .orElseThrow();
      selected.add(next);
      remaining.remove(next);
    }
    return List.copyOf(selected);
  }

  public List<StrategyCard> selectDiverseStrategies(
      List<StrategyCard> strategies, int count) {
    if (count < 0) {
      throw new IllegalArgumentException("count cannot be negative");
    }
    if (count == 0) {
      return List.of();
    }
    if (strategies.size() <= count) {
      return List.copyOf(strategies);
    }
    List<StrategyCard> remaining = new ArrayList<>(strategies);
    StrategyCard first =
        remaining.stream()
            .max(
                Comparator.comparingDouble(
                    strategy -> strategy.estimatedSuccess() - 0.25 * strategy.estimatedCost()))
            .orElseThrow();
    remaining.remove(first);
    List<StrategyCard> selected = new ArrayList<>();
    selected.add(first);
    while (!remaining.isEmpty() && selected.size() < count) {
      StrategyCard next =
          remaining.stream()
              .max(
                  Comparator.<StrategyCard>comparingDouble(
                          candidate ->
                              selected.stream()
                                  .mapToDouble(
                                      existing ->
                                          1.0
                                              - mathSimilarity(
                                                  strategyComparisonText(candidate),
                                                  strategyComparisonText(existing)))
                                  .min()
                                  .orElse(0.0))
                      .thenComparing(StrategyCard::strategyId, Comparator.reverseOrder()))
              .orElseThrow();
      selected.add(next);
      remaining.remove(next);
    }
    return List.copyOf(selected);
  }

  public List<ClaimCard> relevantClaims(
      List<ClaimCard> claims,
      StrategyCard strategy,
      int neighborLimit,
      int claimLimit) {
    if (neighborLimit < 0 || claimLimit < 0) {
      throw new IllegalArgumentException("claim limits cannot be negative");
    }
    String target = strategyText(strategy);
    Map<String, List<ClaimCard>> bySource = new LinkedHashMap<>();
    claims.stream()
        .filter(claim -> claim.status() == ClaimStatus.VERIFIED)
        .sorted(
            Comparator.<ClaimCard>comparingDouble(
                    claim ->
                        -mathSimilarity(
                            target,
                            String.join(
                                " ",
                                claim.statement(),
                                claim.conclusion(),
                                String.join(" ", claim.tags()))))
                .thenComparing(ClaimCard::claimId))
        .forEach(
            claim ->
                bySource
                    .computeIfAbsent(
                        claim.sourceAttemptId() == null ? "" : claim.sourceAttemptId(),
                        ignored -> new ArrayList<>())
                    .add(claim));
    return bySource.values().stream()
        .limit(neighborLimit)
        .flatMap(List::stream)
        .limit(claimLimit)
        .toList();
  }

  public String mathNormalize(String text) {
    String commandsReplaced = replaceLatexCommands(text);
    StringBuilder markupStripped = new StringBuilder(commandsReplaced.length());
    for (int index = 0; index < commandsReplaced.length(); index++) {
      char current = commandsReplaced.charAt(index);
      if (current == '$') {
        continue;
      }
      if (current == '_'
          && index + 1 < commandsReplaced.length()
          && commandsReplaced.charAt(index + 1) == '{') {
        int closing = commandsReplaced.indexOf('}', index + 2);
        if (closing >= 0) {
          markupStripped.append('_');
          markupStripped.append(commandsReplaced, index + 2, closing);
          index = closing;
          continue;
        }
      }
      if (current != '{' && current != '}') {
        markupStripped.append(current);
      }
    }

    Map<String, String> renamed = new LinkedHashMap<>();
    String value = markupStripped.toString();
    StringBuilder result = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); ) {
      char current = value.charAt(index);
      if (!isAsciiLetter(current)) {
        result.append(current);
        index++;
        continue;
      }
      int end = index + 1;
      while (end < value.length() && isAsciiLetter(value.charAt(end))) {
        end++;
      }
      if (end == index + 1
          && end + 1 < value.length()
          && value.charAt(end) == '_'
          && isAsciiDigit(value.charAt(end + 1))) {
        end += 2;
        while (end < value.length() && isAsciiDigit(value.charAt(end))) {
          end++;
        }
      }
      String token = value.substring(index, end);
      int separator = token.indexOf('_');
      String base = token.substring(0, separator < 0 ? token.length() : separator);
      if (base.length() == 1) {
        result.append(
            renamed.computeIfAbsent(token, ignored -> "v" + (renamed.size() + 1)));
      } else {
        result.append(token);
      }
      index = end;
    }
    return result.toString();
  }

  public double[] mathEmbedding(String text) {
    return mathEmbedding(text, 128);
  }

  public double[] mathEmbedding(String text, int dimensions) {
    if (dimensions <= 0) {
      throw new IllegalArgumentException("dimensions must be positive");
    }
    String normalized = asciiLower(mathNormalize(text));
    List<String> tokens = new ArrayList<>(features(normalized));
    tokens.sort(String::compareTo);
    String compact = compactWhitespace(normalized);
    for (int index = 0; index + 2 < compact.length(); index++) {
      tokens.add("tri:" + compact.substring(index, index + 3));
    }
    double[] vector = new double[dimensions];
    for (String token : tokens) {
      byte[] digest = digest(token);
      long prefix =
          ((long) Byte.toUnsignedInt(digest[0]) << 24)
              | ((long) Byte.toUnsignedInt(digest[1]) << 16)
              | ((long) Byte.toUnsignedInt(digest[2]) << 8)
              | Byte.toUnsignedInt(digest[3]);
      int index = (int) (prefix % dimensions);
      vector[index] += (digest[4] & 1) == 1 ? 1.0 : -1.0;
    }
    double norm = Math.sqrt(java.util.Arrays.stream(vector).map(value -> value * value).sum());
    if (norm != 0.0) {
      for (int index = 0; index < vector.length; index++) {
        vector[index] /= norm;
      }
    }
    return vector;
  }

  public double cosineSimilarity(double[] left, double[] right) {
    if (left.length != right.length) {
      throw new IllegalArgumentException("embedding dimensions differ");
    }
    boolean leftZero = java.util.Arrays.stream(left).allMatch(value -> value == 0.0);
    boolean rightZero = java.util.Arrays.stream(right).allMatch(value -> value == 0.0);
    if (leftZero && rightZero) {
      return 1.0;
    }
    if (leftZero || rightZero) {
      return 0.0;
    }
    double dot = 0.0;
    for (int index = 0; index < left.length; index++) {
      dot += left[index] * right[index];
    }
    return Math.max(0.0, Math.min(1.0, dot));
  }

  public double mathSimilarity(String left, String right) {
    double structural = jaccardSimilarity(mathNormalize(left), mathNormalize(right));
    double embedded = cosineSimilarity(mathEmbedding(left), mathEmbedding(right));
    return 0.8 * structural + 0.2 * embedded;
  }

  public String strategyText(StrategyCard strategy) {
    return String.join(
        " ",
        strategy.title(),
        strategy.coreIdea(),
        strategy.independenceBasis(),
        strategy.bottleneck(),
        strategy.keyOriginalStep() == null ? "" : strategy.keyOriginalStep(),
        String.join(" ", strategy.expectedLemmas()),
        String.join(" ", strategy.prerequisites()),
        strategy.criticalClaims().stream()
            .map(claim -> claim.statement())
            .collect(java.util.stream.Collectors.joining(" ")),
        String.join(" ", strategy.tags()));
  }

  /** Canonical unresolved dependency text used to detect common-mode routes. */
  public String dependencyText(StrategyCard strategy) {
    return String.join(
        " ",
        strategy.criticalClaims().stream()
            .filter(claim -> "required".equals(claim.necessity()))
            .filter(claim -> !"verified".equals(claim.status()))
            .map(claim -> claim.statement())
            .collect(java.util.stream.Collectors.joining(" ")),
        String.join(" ", strategy.prerequisites()),
        String.join(" ", strategy.expectedLemmas()));
  }

  public String dependencySignature(StrategyCard strategy) {
    return String.join(" ", features(mathNormalize(dependencyText(strategy))).stream().sorted().toList());
  }

  public boolean sharesUnverifiedDependency(
      StrategyCard left, StrategyCard right, double threshold) {
    if (!Double.isFinite(threshold) || threshold < 0.0d || threshold > 1.0d) {
      throw new IllegalArgumentException("dependency threshold must be between zero and one");
    }
    String leftText = dependencyText(left);
    String rightText = dependencyText(right);
    return !leftText.isBlank()
        && !rightText.isBlank()
        && mathSimilarity(leftText, rightText) >= threshold;
  }

  private String strategyComparisonText(StrategyCard strategy) {
    return strategyText(strategy) + " dependency_chain " + dependencyText(strategy);
  }

  private static Set<String> features(String value) {
    Set<String> result = new LinkedHashSet<>();
    StringBuilder word = new StringBuilder();
    StringBuilder cjk = new StringBuilder();
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (codePoint >= 'A' && codePoint <= 'Z') {
        codePoint += 'a' - 'A';
      }
      if ((codePoint >= 'a' && codePoint <= 'z')
          || (codePoint >= '0' && codePoint <= '9')
          || codePoint == '_') {
        word.appendCodePoint(codePoint);
      } else {
        flushWord(result, word);
      }
      if (codePoint >= 0x3400 && codePoint <= 0x9fff) {
        cjk.appendCodePoint(codePoint);
      }
      if (isMathSymbol(codePoint)) {
        result.add(new String(Character.toChars(codePoint)));
      }
    }
    flushWord(result, word);
    int[] cjkPoints = cjk.codePoints().toArray();
    for (int index = 0; index + 1 < cjkPoints.length; index++) {
      result.add(new String(cjkPoints, index, 2));
    }
    return result;
  }

  private static void flushWord(Set<String> result, StringBuilder word) {
    if (!word.isEmpty()) {
      result.add(word.toString());
      word.setLength(0);
    }
  }

  private static String replaceLatexCommands(String value) {
    StringBuilder result = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); ) {
      char current = value.charAt(index);
      if (current != '\\') {
        result.append(current);
        index++;
        continue;
      }
      int end = index + 1;
      while (end < value.length() && isAsciiLetter(value.charAt(end))) {
        end++;
      }
      if (end == index + 1) {
        result.append(' ');
        index++;
        continue;
      }
      String command = asciiLower(value.substring(index + 1, end));
      String replacement =
          switch (command) {
            case "le" -> "<=";
            case "ge" -> ">=";
            case "ne" -> "!=";
            case "in" -> "\u2208";
            case "subseteq" -> "\u2286";
            case "mid" -> "|";
            case "cdot", "times" -> "*";
            case "forall" -> "\u5bf9\u4efb\u610f";
            case "exists" -> "\u5b58\u5728";
            case "sin", "cos", "tan", "log", "exp", "gcd", "lcm", "mod",
                "max", "min", "sum", "prod", "deg", "ord" -> command;
            default -> "";
          };
      result.append(' ').append(replacement).append(' ');
      index = end;
    }
    return result.toString();
  }

  private static String compactWhitespace(String value) {
    StringBuilder result = new StringBuilder(value.length());
    boolean pendingSpace = false;
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (Character.isWhitespace(codePoint)) {
        pendingSpace = !result.isEmpty();
      } else {
        if (pendingSpace) {
          result.append(' ');
          pendingSpace = false;
        }
        result.appendCodePoint(codePoint);
      }
    }
    return result.toString();
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static boolean isAsciiLetter(char value) {
    return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
  }

  private static boolean isAsciiDigit(char value) {
    return value >= '0' && value <= '9';
  }

  private static String asciiLower(String value) {
    StringBuilder result = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      result.append(
          current >= 'A' && current <= 'Z'
              ? (char) (current + ('a' - 'A'))
              : current);
    }
    return result.toString();
  }

  private static boolean isMathSymbol(int codePoint) {
    return codePoint == 0x2200
        || codePoint == 0x2203
        || codePoint == 0x2211
        || codePoint == 0x220f
        || codePoint == 0x2264
        || codePoint == 0x2265
        || codePoint == 0x2260
        || codePoint == 0x2248
        || codePoint == 0x221e;
  }
}
