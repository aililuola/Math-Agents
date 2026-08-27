package io.github.aililuola.mathproofmesh.inspiration;

import java.util.Map;

/** Structural novelty assessment; it is never a correctness judgment. */
public record NoveltyAssessment(
    double noveltyScore,
    double maximumSimilarity,
    boolean duplicate,
    String nearestHash,
    Map<String, Double> dimensionSimilarities,
    double mechanismChainSimilarity) {
  public NoveltyAssessment {
    if (!Double.isFinite(noveltyScore)
        || !Double.isFinite(maximumSimilarity)
        || !Double.isFinite(mechanismChainSimilarity)) {
      throw new IllegalArgumentException("novelty scores must be finite");
    }
    dimensionSimilarities =
        dimensionSimilarities == null ? Map.of() : Map.copyOf(dimensionSimilarities);
  }
}
