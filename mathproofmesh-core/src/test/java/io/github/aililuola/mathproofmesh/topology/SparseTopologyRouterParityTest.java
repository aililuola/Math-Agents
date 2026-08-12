package io.github.aililuola.mathproofmesh.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SparseTopologyRouterParityTest {
  private final SparseTopologyRouter router = new SparseTopologyRouter();

  @Test
  void emptyTextsHaveUnitSimilarity() {
    assertEquals(1.0, router.jaccardSimilarity("", ""));
  }

  @Test
  void disjointMechanismsHaveZeroSimilarity() {
    assertEquals(0.0, router.jaccardSimilarity("induction", "geometry"));
  }

  @Test
  void neighborsAreRelevanceRankedAndBounded() {
    Map<String, String> routes = new LinkedHashMap<>();
    routes.put("a", "induction recurrence squares");
    routes.put("b", "induction recurrence identity");
    routes.put("c", "finite difference telescope");
    Map<String, List<String>> selected = router.selectSparseRouteNeighbors(routes, 1);
    assertEquals(List.of("b"), selected.get("a"));
    assertTrue(selected.values().stream().allMatch(items -> items.size() <= 1));
  }

  @Test
  void diverseSelectionAvoidsNearDuplicateSecondChoice() {
    Map<String, String> strategies = new LinkedHashMap<>();
    strategies.put("a", "induction recurrence squares");
    strategies.put("b", "induction recurrence square identity");
    strategies.put("c", "finite differences telescope");
    assertEquals(List.of("a", "c"), router.selectDiverseStrategies(strategies, 2));
  }

  @Test
  void renamedLatexAndAsciiBoundsHaveEquivalentMathStructure() {
    String latex = "$a_{n+1} \\le a_n + C$";
    String ascii = "b_{k+1} <= b_k + D";
    assertTrue(router.mathSimilarity(latex, ascii) >= 0.6);
    assertTrue(router.jaccardSimilarity(latex, ascii) < 0.2);
    assertTrue(router.cosineSimilarity(router.mathEmbedding(latex), router.mathEmbedding(ascii))
        >= 0.9);
  }

  @Test
  void alphaRenamingAndNumericSubscriptsAreOrderCanonical() {
    assertEquals(router.mathNormalize("f(x)+g(y)"), router.mathNormalize("u(s)+w(t)"));
    assertEquals(router.mathNormalize("a_1 + a_2"), router.mathNormalize("x_1 + x_2"));
  }

  @Test
  void functionWordsAndFixedOperatorsSurviveNormalization() {
    String functions = router.mathNormalize("\\gcd(m, n) = 1 and sin x + log y");
    assertTrue(functions.contains("gcd"));
    assertTrue(functions.contains("sin"));
    assertTrue(functions.contains("log"));
    assertTrue(functions.contains("v1"));

    String quantified = router.mathNormalize("$\\forall x \\in S, x \\le y$");
    assertTrue(quantified.contains("\u5bf9\u4efb\u610f"));
    assertTrue(quantified.contains("\u2208"));
    assertTrue(!quantified.contains("\\") && !quantified.contains("$"));
  }

  @Test
  void genuinelyDifferentMathematicsStaysSeparated() {
    String recurrence = "$a_{n+1} \\le a_n + C$";
    String quadratic = "\\forall x \\in S, f(x) = x^2 + 1";
    String combinatorial = "double counting of set partitions";
    assertTrue(router.mathSimilarity(recurrence, quadratic) < 0.5);
    assertTrue(router.mathSimilarity(recurrence, combinatorial) < 0.5);
  }
}
