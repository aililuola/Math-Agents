package io.github.aililuola.mathproofmesh.strategydiversity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.topology.SparseTopologyRouter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Domain-neutral normalization used only for strategy-control identities. */
@SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification =
        "Strategy identities case-map with Locale.ROOT and are then normalized with NFKC;"
            + " they are not authentication identifiers.")
public final class StrategySemanticNormalizer {
  private static final SparseTopologyRouter MATH = new SparseTopologyRouter();

  private StrategySemanticNormalizer() {}

  public static String normalize(String value) {
    String caseMapped = (value == null ? "" : value).toLowerCase(Locale.ROOT);
    String nfkc = Normalizer.normalize(caseMapped, Normalizer.Form.NFKC);
    return MATH.mathNormalize(nfkc).strip().replaceAll("\\s+", " ");
  }

  public static List<String> normalizedSet(Collection<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .filter(java.util.Objects::nonNull)
        .map(StrategySemanticNormalizer::normalize)
        .filter(value -> !value.isBlank())
        .distinct()
        .sorted()
        .toList();
  }

  public static String hash(Object value) {
    return CanonicalJson.stableHash(value);
  }

  public static boolean hashEquals(String left, String right) {
    if (left == null || right == null) {
      return java.util.Objects.equals(left, right);
    }
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  public static String require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.strip();
  }
}
