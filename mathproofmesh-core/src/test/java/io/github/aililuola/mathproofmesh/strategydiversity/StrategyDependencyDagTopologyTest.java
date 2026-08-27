package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyBlueprintCompiler;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyDependencyDagTopologyTest {
  @Test
  void equalNodeAndRelationMultisetsDoNotHideDifferentDirectedTopology() {
    StrategyCard strategy =
        StrategyDiversityTestFixtures.strategy(
            "topology", "Topology", "Use a direct structural reduction", "Bridge U holds.", 0.7d);
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();

    StrategyMechanismSignature chain =
        analyzer.signature(
            StrategyDiversityTestFixtures.PROBLEM_HASH,
            StrategyDiversityTestFixtures.ROOT_HASH,
            strategy,
            StrategyDiversityTestFixtures.control(strategy),
            compilation(false));
    StrategyMechanismSignature fork =
        analyzer.signature(
            StrategyDiversityTestFixtures.PROBLEM_HASH,
            StrategyDiversityTestFixtures.ROOT_HASH,
            strategy,
            StrategyDiversityTestFixtures.control(strategy),
            compilation(true));

    assertThat(chain.dependencyDagShapeHash()).isNotEqualTo(fork.dependencyDagShapeHash());
    assertThat(chain.structuralSignatureHash()).isNotEqualTo(fork.structuralSignatureHash());
  }

  private static StrategyBlueprintCompiler.Compilation compilation(boolean fork) {
    List<StrategyBlueprintCompiler.Node> nodes =
        List.of(
            node("a", ProofControlModels.BlueprintNodeKind.LEMMA, "First bridge"),
            node("b", ProofControlModels.BlueprintNodeKind.LEMMA, "Second bridge"),
            node("c", ProofControlModels.BlueprintNodeKind.LEMMA, "Third bridge"),
            node("target", ProofControlModels.BlueprintNodeKind.TARGET, StrategyDiversityTestFixtures.GOAL));
    List<StrategyBlueprintCompiler.Edge> edges =
        fork
            ? List.of(edge("a", "b"), edge("a", "c"), edge("c", "target"))
            : List.of(edge("a", "b"), edge("b", "c"), edge("c", "target"));
    return new StrategyBlueprintCompiler.Compilation(
        new StrategyBlueprintCompiler.Blueprint(
            "blueprint", "topology", StrategyDiversityTestFixtures.PROBLEM_HASH, nodes, edges,
            "target", List.of("c"), List.of("a"), List.of("a", "b", "c"), true, true,
            0.9d, "accepted"),
        List.of(),
        List.of("a", "b", "c"));
  }

  private static StrategyBlueprintCompiler.Node node(
      String id, ProofControlModels.BlueprintNodeKind kind, String statement) {
    return new StrategyBlueprintCompiler.Node(
        id, kind, statement, kind == ProofControlModels.BlueprintNodeKind.TARGET ? "main_goal" : "expected_lemma", "", 0.9d);
  }

  private static StrategyBlueprintCompiler.Edge edge(String source, String target) {
    return new StrategyBlueprintCompiler.Edge(
        source + '-' + target, source, target, "implies", List.of("structured implication"), false,
        "test");
  }
}
