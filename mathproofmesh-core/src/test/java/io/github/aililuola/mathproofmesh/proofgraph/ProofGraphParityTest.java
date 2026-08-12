package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.GraphEdgeType;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofGraphEdge;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProofGraphParityTest {

  @Test
  void factClosesObligationAndRefutationReopensDependents() {
    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    graph.addObligation(
        ProofGraphFixtures.obligation("base", "base", "route-a", List.of()));
    graph.addObligation(
        ProofGraphFixtures.obligation(
            "dependent", "dependent", "route-a", List.of("base")));
    MessageEnvelope fact = ProofGraphFixtures.fact("proof", "base");
    graph.addClaimNode(fact);
    graph.closeObligation("base", fact.messageId(), 0.95);
    graph.closeObligation("dependent", fact.messageId(), 0.95);

    assertThat(graph.proofDebt("route-a")).isZero();
    graph.refuteObligation("base", null);

    assertThat(graph.getObligation("base").status()).isEqualTo("refuted");
    assertThat(graph.getObligation("dependent").status()).isEqualTo("open");
    assertThat(graph.needsReverify("dependent")).isTrue();
  }

  @Test
  void dependencyCycleAndWritesAfterFreezeAreRejected() {
    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    graph.addObligation(
        ProofGraphFixtures.obligation("a", "a", "route-a", List.of()));
    graph.addObligation(
        ProofGraphFixtures.obligation("b", "b", "route-a", List.of("a")));

    assertThatThrownBy(
            () ->
                graph.addEdge(
                    new ProofGraphEdge(
                        null,
                        GraphEdgeType.DEPENDS_ON,
                        null,
                        null,
                        "a",
                        "b")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle");

    graph.freeze();
    assertThatThrownBy(
            () ->
                graph.addObligation(
                    ProofGraphFixtures.obligation(
                        "c", "c", "route-a", List.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("frozen");
  }

  @Test
  void missingDependencyIsRejectedBeforeMutation() {
    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);

    assertThatThrownBy(
            () ->
                graph.addObligation(
                    ProofGraphFixtures.obligation(
                        "derived", "derived", "route-a", List.of("missing"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing proof dependency");
    assertThat(graph.obligations()).isEmpty();
  }

  @Test
  void dependencyClosureAndTopologicalOrderUseJGraphTProjection() {
    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    graph.addObligation(
        ProofGraphFixtures.obligation("base", "base", "route-a", List.of()));
    graph.addObligation(
        ProofGraphFixtures.obligation(
            "middle", "middle", "route-a", List.of("base")));
    graph.addObligation(
        ProofGraphFixtures.obligation(
            "goal",
            "goal",
            List.of("route-a"),
            List.of("middle"),
            ObligationKind.MAIN_GOAL,
            1.0,
            1.0));

    assertThat(graph.coreDependencyClosure())
        .containsExactlyInAnyOrder("goal", "middle", "base");
    assertThat(graph.topologicalOrder())
        .extracting(ProofObligation::obligationId)
        .containsExactly("goal", "middle", "base");
  }

  @Test
  void proofDebtAndBottleneckReflectOpenCentralDependencies() {
    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    graph.addObligation(
        ProofGraphFixtures.obligation(
            "base",
            "base",
            List.of("route-a", "route-b"),
            List.of(),
            ObligationKind.LEMMA,
            0.9,
            1.0));
    graph.addObligation(
        ProofGraphFixtures.obligation(
            "goal",
            "goal",
            List.of("route-a"),
            List.of("base"),
            ObligationKind.MAIN_GOAL,
            1.0,
            0.8));

    assertThat(graph.proofDebt("route-a")).isPositive();
    assertThat(graph.coreBottleneck()).isEqualTo("base");
    assertThat(graph.coreOpenObligations()).hasSize(2);
  }

  @Test
  void counterexampleRefutesDirectTargetAndReopensTransitiveClosure() {
    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    graph.addObligation(
        ProofGraphFixtures.obligation("base", "claim p", "route-a", List.of()));
    graph.addObligation(
        ProofGraphFixtures.obligation(
            "dependent", "claim q", "route-a", List.of("base")));
    MessageEnvelope proof = ProofGraphFixtures.fact("proof", "claim p");
    graph.addClaimNode(proof);
    graph.closeObligation("base", proof.messageId(), 1.0);
    graph.closeObligation("dependent", proof.messageId(), 1.0);
    MessageEnvelope counterexample =
        ProofGraphFixtures.message(
            "counterexample",
            "claim p",
            "claim p",
            "route-c",
            "hunter",
            io.github.aililuola.mathproofmesh.contract.MessageType.COUNTEREXAMPLE,
            io.github.aililuola.mathproofmesh.contract.EvidenceType.COUNTEREXAMPLE,
            io.github.aililuola.mathproofmesh.contract.MemoryTier.NEGATIVE,
            io.github.aililuola.mathproofmesh.contract.ClaimStatus.REJECTED);

    assertThat(graph.applyCounterexample(counterexample)).containsExactly("base");
    assertThat(graph.getObligation("base").status()).isEqualTo("refuted");
    assertThat(graph.getObligation("dependent").status()).isEqualTo("open");
  }

  @Test
  void minimalSubgraphIncludesTransitiveDependenciesAndEvidence() {
    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    graph.addObligation(
        ProofGraphFixtures.obligation("base", "base", "route-a", List.of()));
    graph.addObligation(
        ProofGraphFixtures.obligation(
            "goal", "goal", "route-a", List.of("base")));
    MessageEnvelope fact = ProofGraphFixtures.fact("fact", "base");
    graph.addClaimNode(fact);
    graph.closeObligation("base", "fact", 1.0);

    ProofGraphSnapshot minimal = graph.minimalSubgraph(List.of("goal"));

    assertThat(minimal.frozen()).isTrue();
    assertThat(minimal.obligations()).containsOnlyKeys("goal", "base");
    assertThat(minimal.claimNodes()).containsOnlyKeys("fact");
  }

  @Test
  void snapshotRoundTripPreservesFreezeVersionsAndReverification() {
    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    graph.addObligation(
        ProofGraphFixtures.obligation("base", "base", "route-a", List.of()));
    graph.reopenObligation("base");
    graph.freeze();

    ProofGraphStore restored =
        ProofGraphStore.restore(graph.snapshot(), ProofGraphPolicy.defaults());

    assertThat(restored.frozen()).isTrue();
    assertThat(restored.version("base")).isEqualTo(graph.version("base"));
    assertThat(restored.needsReverify("base")).isTrue();
    assertThat(restored.obligations()).hasSize(1);
  }
}
