package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Weighted, prose-independent duplicate gate. */
public final class NoveltyGate {
  private final InspirationPolicy.NoveltyRules rules;
  private final MechanismNormalizer normalizer = new MechanismNormalizer();

  public NoveltyGate(InspirationPolicy.NoveltyRules rules) {
    this.rules = java.util.Objects.requireNonNull(rules, "rules");
  }

  public NoveltyAssessment assess(
      NoveltySignature candidate, List<NoveltySignature> existing) {
    double maximum = 0.0d;
    double maximumChain = 0.0d;
    boolean duplicate = false;
    String nearest = null;
    Map<String, Double> nearestDimensions = Map.of();
    for (NoveltySignature other : existing == null ? List.<NoveltySignature>of() : existing) {
      Similarity result = similarity(candidate, other);
      double chain = chainSimilarity(candidate, other);
      boolean structurallyComparable =
          sharedStructuralDimensions(
                  normalizer.normalize(candidate), normalizer.normalize(other))
              >= 2;
      duplicate =
          duplicate
              || chain >= rules.duplicateThreshold()
              || (structurallyComparable && result.score() >= rules.duplicateThreshold());
      maximumChain = Math.max(maximumChain, chain);
      if (result.score() >= maximum) {
        maximum = result.score();
        nearest = normalizer.normalize(other).normalizedHash();
        nearestDimensions = result.dimensions();
      }
    }
    double novelty = nearest == null ? 1.0d : Math.max(0.0d, 1.0d - maximum);
    return new NoveltyAssessment(
        novelty, maximum, duplicate, nearest, nearestDimensions, maximumChain);
  }

  public Similarity similarity(NoveltySignature left, NoveltySignature right) {
    NoveltySignature a = normalizer.normalize(left);
    NoveltySignature b = normalizer.normalize(right);
    Map<String, Double> dimensions = new LinkedHashMap<>();
    dimensions.put("representation", jaccard(a.representationTags(), b.representationTags()));
    dimensions.put("mechanism", jaccard(a.mechanismTags(), b.mechanismTags()));
    dimensions.put("object", jaccard(a.coreObjects(), b.coreObjects()));
    dimensions.put("transformation", jaccard(a.keyTransformations(), b.keyTransformations()));
    dimensions.put("principle", jaccard(a.proofPrinciples(), b.proofPrinciples()));
    dimensions.put(
        "obligation", jaccard(a.targetedObligationIds(), b.targetedObligationIds()));
    dimensions.put("extension", jaccard(a.extensionTags(), b.extensionTags()));
    Map<String, Double> weights =
        Map.of(
            "representation", rules.representationWeight(),
            "mechanism", rules.mechanismWeight(),
            "object", rules.objectWeight(),
            "transformation", rules.transformationWeight(),
            "principle", rules.principleWeight(),
            "obligation", rules.obligationWeight(),
            "extension", 0.05d);
    Map<String, Boolean> active =
        Map.of(
            "representation",
            !a.representationTags().isEmpty() || !b.representationTags().isEmpty(),
            "mechanism",
            !a.mechanismTags().isEmpty() || !b.mechanismTags().isEmpty(),
            "object",
            !a.coreObjects().isEmpty() || !b.coreObjects().isEmpty(),
            "transformation",
            !a.keyTransformations().isEmpty() || !b.keyTransformations().isEmpty(),
            "principle",
            !a.proofPrinciples().isEmpty() || !b.proofPrinciples().isEmpty(),
            "obligation",
            !a.targetedObligationIds().isEmpty() || !b.targetedObligationIds().isEmpty(),
            "extension",
            !a.extensionTags().isEmpty() || !b.extensionTags().isEmpty());
    double total =
        active.entrySet().stream()
            .filter(Map.Entry::getValue)
            .mapToDouble(entry -> weights.get(entry.getKey()))
            .sum();
    double score =
        total == 0.0d
            ? 0.0d
            : active.entrySet().stream()
                    .filter(Map.Entry::getValue)
                    .mapToDouble(
                        entry ->
                            dimensions.get(entry.getKey()) * weights.get(entry.getKey()))
                    .sum()
                / total;
    boolean structural =
        active.get("representation")
            || active.get("mechanism")
            || active.get("object")
            || active.get("transformation")
            || active.get("principle");
    if (!structural) {
      score = Math.min(score, 0.5d);
    }
    return new Similarity(score, dimensions);
  }

  private static int sharedStructuralDimensions(NoveltySignature left, NoveltySignature right) {
    int result = 0;
    result += both(left.representationTags(), right.representationTags());
    result += both(left.mechanismTags(), right.mechanismTags());
    result += both(left.coreObjects(), right.coreObjects());
    result += both(left.keyTransformations(), right.keyTransformations());
    result += both(left.proofPrinciples(), right.proofPrinciples());
    return result;
  }

  private static int both(List<String> left, List<String> right) {
    return !left.isEmpty() && !right.isEmpty() ? 1 : 0;
  }

  private static double chainSimilarity(NoveltySignature left, NoveltySignature right) {
    if (left.representationTags().isEmpty()
        || right.representationTags().isEmpty()
        || left.keyTransformations().isEmpty()
        || right.keyTransformations().isEmpty()
        || left.proofPrinciples().isEmpty()
        || right.proofPrinciples().isEmpty()) {
      return 0.0d;
    }
    return (jaccard(left.representationTags(), right.representationTags())
            + jaccard(left.keyTransformations(), right.keyTransformations())
            + jaccard(left.mechanismTags(), right.mechanismTags())
            + jaccard(left.proofPrinciples(), right.proofPrinciples()))
        / 4.0d;
  }

  private static double jaccard(List<String> left, List<String> right) {
    Set<String> a =
        left.stream()
            .filter(java.util.Objects::nonNull)
            .map(value -> value.strip().toLowerCase(java.util.Locale.ROOT))
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toSet());
    Set<String> b =
        right.stream()
            .filter(java.util.Objects::nonNull)
            .map(value -> value.strip().toLowerCase(java.util.Locale.ROOT))
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toSet());
    if (a.isEmpty() || b.isEmpty()) {
      return 0.0d;
    }
    long intersection = a.stream().filter(b::contains).count();
    return intersection / (double) (a.size() + b.size() - intersection);
  }

  public record Similarity(double score, Map<String, Double> dimensions) {
    public Similarity {
      dimensions = Map.copyOf(dimensions);
    }
  }
}
