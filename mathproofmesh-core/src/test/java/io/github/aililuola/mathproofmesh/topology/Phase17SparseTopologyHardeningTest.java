package io.github.aililuola.mathproofmesh.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Phase17SparseTopologyHardeningTest {
  private final SparseTopologyRouter router = new SparseTopologyRouter();

  @Test
  void selectionBoundariesPreserveZeroAndRejectNegativeLimits() {
    assertThat(router.jaccardSimilarity("", "nonempty")).isZero();
    assertThat(router.jaccardSimilarity("nonempty", "")).isZero();
    assertThatThrownBy(() -> router.selectSparseRouteNeighbors(Map.of(), -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> router.selectDiverseStrategies(Map.of(), -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> router.selectDiverseStrategies(List.of(), -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> router.relevantClaims(List.of(), strategy("s"), -1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> router.relevantClaims(List.of(), strategy("s"), 1, -1))
        .isInstanceOf(IllegalArgumentException.class);

    Map<String, String> strategies = new LinkedHashMap<>();
    strategies.put("b", "same");
    strategies.put("a", "same");
    assertThat(router.selectDiverseStrategies(strategies, 0)).isEmpty();
    assertThat(router.selectDiverseStrategies(strategies, 5)).containsExactly("b", "a");
    assertThat(router.selectSparseRouteNeighbors(strategies, 0).values())
        .allSatisfy(neighbors -> assertThat(neighbors).isEmpty());

    List<StrategyCard> cards = List.of(strategy("a"), strategy("b"), strategy("c"));
    assertThat(router.selectDiverseStrategies(cards, 0)).isEmpty();
    assertThat(router.selectDiverseStrategies(cards, 5)).containsExactlyElementsOf(cards);
    assertThat(router.selectDiverseStrategies(cards, 2)).hasSize(2);
  }

  @Test
  void relevantClaimsFilterStatusGroupSourcesAndHonorBothLimits() {
    StrategyCard strategy = strategy("target");
    List<ClaimCard> claims =
        List.of(
            claim("a", "attempt-a", "induction invariant", "verified"),
            claim("b", "attempt-a", "induction recurrence", "verified"),
            claim("c", null, "finite differences", "verified"),
            claim("d", "attempt-b", "unrelated", "proposed"));
    assertThat(router.relevantClaims(claims, strategy, 0, 10)).isEmpty();
    assertThat(router.relevantClaims(claims, strategy, 1, 1))
        .extracting(ClaimCard::claimId)
        .hasSize(1);
    assertThat(router.relevantClaims(claims, strategy, 3, 10))
        .extracting(ClaimCard::claimId)
        .doesNotContain("d")
        .containsExactlyInAnyOrder("a", "b", "c");
  }

  @Test
  void normalizationEmbeddingAndCosineCoverEveryBoundaryBranch() {
    String commands =
        router.mathNormalize(
            "\\le \\ge \\ne \\in \\subseteq \\mid \\cdot \\times \\forall \\exists "
                + "\\sin \\cos \\tan \\log \\exp \\gcd \\lcm \\mod \\max \\min "
                + "\\sum \\prod \\deg \\ord \\unknown \\\\ $x_{12} y_{unterminated");
    assertThat(commands)
        .contains("<=", ">=", "!=", "\u2208", "\u2286", "|", "*", "\u5bf9\u4efb\u610f")
        .doesNotContain("\\unknown");
    assertThat(router.mathNormalize("A+B+C")).isEqualTo("v1+v2+v3");
    assertThat(router.mathNormalize("word + X_10")).contains("word", "v1");
    assertThat(router.mathNormalize("x_{2} + {y}")).isEqualTo("v1 + v2");

    assertThatThrownBy(() -> router.mathEmbedding("x", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(router.mathEmbedding("", 8)).containsOnly(0.0d);
    double[] normalized = router.mathEmbedding("x + y + z", 8);
    assertThat(java.util.Arrays.stream(normalized).map(value -> value * value).sum())
        .isCloseTo(1.0d, org.assertj.core.data.Offset.offset(1.0e-12));

    assertThatThrownBy(() -> router.cosineSimilarity(new double[1], new double[2]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(router.cosineSimilarity(new double[2], new double[2])).isEqualTo(1.0d);
    assertThat(router.cosineSimilarity(new double[2], new double[] {1, 0})).isZero();
    assertThat(router.cosineSimilarity(new double[] {2, 0}, new double[] {2, 0}))
        .isEqualTo(1.0d);
    assertThat(router.cosineSimilarity(new double[] {1, 0}, new double[] {-1, 0}))
        .isZero();

    assertThat(
            router.jaccardSimilarity(
                "\u6574\u6570\u5e8f\u5217 \u2200 \u2203 \u2211 \u220f \u2264 \u2265 \u2260 \u2248 \u221e",
                "\u6574\u6570\u5e8f\u5217 \u2200 \u2203 \u2211 \u220f \u2264 \u2265 \u2260 \u2248 \u221e"))
        .isEqualTo(1.0d);
  }

  @Test
  void routeDiversityUsesUnverifiedDependencyChainsRatherThanLabels() {
    StrategyCard first =
        strategyWithDependency(
            "a", "Residue route", "Every route eventually has finite state");
    StrategyCard renamed =
        strategyWithDependency(
            "b", "Graph route", "Every route eventually has finite state");
    StrategyCard independent =
        strategyWithDependency(
            "c", "Enumeration route", "The greedy feasible set eventually stabilizes");

    assertThat(router.sharesUnverifiedDependency(first, renamed, 0.82d)).isTrue();
    assertThat(router.sharesUnverifiedDependency(first, independent, 0.82d)).isFalse();
  }

  private static StrategyCard strategy(String id) {
    return ContractObjectMapper.read(
        """
        {
          "strategy_id":"%s",
          "title":"Induction route",
          "core_idea":"Use an induction invariant and recurrence.",
          "independence_basis":"A structurally independent route.",
          "bottleneck":"Prove invariant preservation.",
          "falsification_test":"Check the smallest failed index.",
          "estimated_success":0.8,
          "estimated_cost":0.2,
          "expected_lemmas":["invariant"],
          "tags":["induction","recurrence"]
        }
        """
            .formatted(id),
        StrategyCard.class);
  }

  private static StrategyCard strategyWithDependency(
      String id, String title, String dependency) {
    return ContractObjectMapper.read(
        """
        {
          "strategy_id":"%s",
          "title":"%s",
          "core_idea":"Use a distinct implementation mechanism.",
          "independence_basis":"Different labels are not proof of independence.",
          "bottleneck":"Close the load-bearing bridge.",
          "falsification_test":"Search for a counterexample to the bridge.",
          "estimated_success":0.8,
          "estimated_cost":0.2,
          "critical_claims":[{
            "claim_id":"critical-%s",
            "statement":"%s",
            "falsification_test":"Find one violating state.",
            "necessity":"required",
            "status":"needs_check"
          }],
          "expected_lemmas":[],
          "tags":["different-label"]
        }
        """
            .formatted(id, title, id, dependency),
        StrategyCard.class);
  }

  private static ClaimCard claim(
      String id, String sourceAttemptId, String statement, String status) {
    String source =
        sourceAttemptId == null ? "null" : "\"" + sourceAttemptId + "\"";
    return ContractObjectMapper.read(
        """
        {
          "claim_id":"%s",
          "statement":"%s",
          "conclusion":"%s",
          "source_attempt_id":%s,
          "status":"%s",
          "tags":["induction"]
        }
        """
            .formatted(id, statement, statement, source, status),
        ClaimCard.class);
  }
}
