package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.GraphEdgeType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofGraphEdge;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Phase17ProofGraphHardeningTest {

  @Test
  void hashesCollisionsAliasesAndCapacityAreStrict() {
    ProofGraphStore adopting = new ProofGraphStore("");
    ProofObligation first = obligation("first", "same statement", List.of("r1"), List.of());
    assertThat(adopting.addObligation(first)).isEqualTo(first);
    assertThat(adopting.problemHash()).isEqualTo(ProofGraphFixtures.PROBLEM_HASH);
    assertThat(adopting.addObligation(first)).isEqualTo(first);
    assertThatThrownBy(() -> adopting.addObligation(obligation("first", "different", List.of("r1"), List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("collision");

    ProofObligation duplicate =
        obligation("alias", "same statement", List.of("r1", "r2"), List.of());
    assertThat(adopting.addObligation(duplicate).obligationId()).isEqualTo("first");
    assertThat(adopting.getObligation("alias").routeIds()).contains("r1", "r2");
    assertThat(adopting.version("alias")).isPositive();

    var claim = ProofGraphFixtures.fact("claim", "claim");
    adopting.addClaimNode(claim);
    assertThat(adopting.addClaimNode(claim)).isEqualTo(claim);
    assertThatThrownBy(() -> adopting.addClaimNode(ProofGraphFixtures.fact("claim", "other")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("collision");
    assertThatThrownBy(
            () ->
                adopting.addClaimNode(
                    messageWithProblemHash(
                        ProofGraphFixtures.fact("other-claim", "claim"), "8".repeat(64))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("problem_hash");

    ProofGraphPolicy oneNode = policy(1, 3);
    ProofGraphStore bounded = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH, oneNode);
    bounded.addObligation(obligation("only", "only", List.of("r"), List.of()));
    assertThatThrownBy(() -> bounded.addClaimNode(ProofGraphFixtures.fact("too-many", "x")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("node limit");
    assertThatThrownBy(() -> new ProofGraphStore("", null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void edgesCoverSelfUnknownDuplicateAliasNormalizationCycleAndLimit() {
    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    graph.addObligation(obligation("a", "a", List.of("r"), List.of()));
    graph.addObligation(obligation("b", "b", List.of("r"), List.of()));
    graph.addObligation(obligation("alias-a", "a", List.of("r"), List.of()));
    assertThatThrownBy(() -> graph.addEdge(edge(GraphEdgeType.DEPENDS_ON, "a", "a", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("self");
    assertThatThrownBy(() -> graph.addEdge(edge(GraphEdgeType.DEPENDS_ON, "a", "missing", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown");

    ProofGraphEdge normalized =
        graph.addEdge(edge(GraphEdgeType.DEPENDS_ON, "b", "alias-a", null));
    assertThat(normalized.targetId()).isEqualTo("a");
    assertThat(graph.addEdge(edge(GraphEdgeType.DEPENDS_ON, "b", "a", null)))
        .isEqualTo(normalized);
    assertThatThrownBy(() -> graph.addEdge(edge(GraphEdgeType.IMPLIES, "a", "b", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle");
    assertThat(graph.addEdge(edge(GraphEdgeType.REFUTES, "a", "b", null))).isNotNull();

    ProofGraphStore bounded =
        new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH, policy(4, 1));
    bounded.addObligation(obligation("x", "x", List.of("r"), List.of()));
    bounded.addObligation(obligation("y", "y", List.of("r"), List.of()));
    bounded.addEdge(edge(GraphEdgeType.REFUTES, "x", "y", "e1"));
    assertThatThrownBy(
            () -> bounded.addEdge(edge(GraphEdgeType.EQUIVALENT_TO, "x", "y", "e2")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("edge limit");
  }

  @Test
  void closeReopenRefuteInvalidationAndCounterexamplesCoverAllStates() {
    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    graph.addObligation(obligation("base", "claim p", List.of("r"), List.of()));
    graph.addObligation(obligation("dependent", "claim q", List.of("r"), List.of("base")));
    assertThat(graph.closeObligation("base", "missing", 0.2d).status()).isEqualTo("tentative");
    assertThatThrownBy(() -> graph.closeObligation("base", "missing", 1.0d))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("evidence");
    var insight =
        ProofGraphFixtures.message(
            "insight",
            "claim p",
            "claim p",
            "route-a",
            "author",
            MessageType.FAILURE_RECORD,
            EvidenceType.UNVERIFIED_IDEA,
            MemoryTier.INSIGHT,
            ClaimStatus.PROPOSED);
    graph.addClaimNode(insight);
    assertThatThrownBy(() -> graph.closeObligation("base", "insight", 1.0d))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("verified fact");

    var fact1 = ProofGraphFixtures.fact("fact-1", "claim p");
    var fact2 = ProofGraphFixtures.fact("fact-2", "claim p");
    graph.addClaimNode(fact1);
    graph.addClaimNode(fact2);
    long stale = graph.version("base") - 1;
    assertThatThrownBy(() -> graph.closeObligation("base", "fact-1", 1.0d, stale))
        .isInstanceOf(ProofGraphConflictException.class);
    graph.closeObligation("base", "fact-1", 1.0d);
    graph.closeObligation("base", "fact-2", 1.0d);
    assertThat(graph.invalidateEvidenceMessages(List.of(), "unused")).isEmpty();
    assertThat(graph.invalidateEvidenceMessages(List.of("fact-1"), "superseded")).isEmpty();
    assertThat(graph.getObligation("base").status()).isEqualTo("closed");
    assertThat(graph.invalidateEvidenceMessages(List.of("fact-2"), "revoked")).contains("base");
    assertThat(graph.needsReverify("base")).isTrue();
    assertThatThrownBy(() -> graph.invalidateEvidenceMessages(List.of("unknown"), " "))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(graph.refuteObligation("base", "").evidenceMessageIds()).isEmpty();
    graph.reopenObligation("base", graph.version("base"));
    assertThatThrownBy(() -> graph.reopenObligation("base", -1))
        .isInstanceOf(ProofGraphConflictException.class);
    assertThatThrownBy(() -> graph.getObligation("unknown"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(graph.applyCounterexample(ProofGraphFixtures.fact("not-counter", "claim p")))
        .isEmpty();
    var contained =
        ProofGraphFixtures.message(
            "counter-contained",
            "different",
            "claim",
            "route-c",
            "hunter",
            MessageType.COUNTEREXAMPLE,
            EvidenceType.COUNTEREXAMPLE,
            MemoryTier.NEGATIVE,
            ClaimStatus.REJECTED);
    assertThat(graph.applyCounterexample(contained)).contains("base", "dependent");
    var unmatched =
        ProofGraphFixtures.message(
            "counter-unmatched",
            "unrelated",
            "unrelated z",
            "route-c",
            "hunter",
            MessageType.COUNTEREXAMPLE,
            EvidenceType.COUNTEREXAMPLE,
            MemoryTier.NEGATIVE,
            ClaimStatus.REJECTED);
    assertThat(graph.applyCounterexample(unmatched)).isEmpty();
  }

  @Test
  void queriesDebtBottlenecksSnapshotsRestoreAndFreezeCoverBoundaries() {
    ProofGraphStore empty = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    assertThat(empty.coreBottleneck()).isEmpty();
    assertThat(empty.dependencyClosure(List.of("missing"))).isEmpty();
    assertThat(empty.findSharedBottlenecks(2)).isEmpty();

    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    graph.addObligation(
        detailed(
            "shared-a",
            "the same hard lemma",
            List.of("r1", "r2"),
            List.of(),
            ObligationKind.CONTRADICTION,
            1.0d,
            1.0d,
            "failure",
            "open"));
    graph.addObligation(
        detailed(
            "shared-b",
            "the same hard lemma follows",
            List.of("r3"),
            List.of(),
            ObligationKind.LEMMA,
            0.8d,
            0.7d,
            null,
            "open"));
    graph.addObligation(
        detailed(
            "goal",
            "goal",
            List.of("r1"),
            List.of("shared-a"),
            ObligationKind.MAIN_GOAL,
            1.0d,
            0.5d,
            null,
            "open"));
    graph.markBlocked("shared-a", "blocked");
    assertThat(graph.findSharedBottlenecks(2)).isNotEmpty();
    assertThat(graph.findSharedBottlenecks(99)).isEmpty();
    assertThat(graph.proofDebt("r1")).isPositive();
    assertThat(graph.proofDebt("absent")).isZero();
    assertThat(graph.findDependents("shared-a"))
        .extracting(ProofObligation::obligationId)
        .contains("goal");
    assertThat(graph.coreOpenObligations()).isNotEmpty();
    assertThat(graph.topologicalOrder()).isNotEmpty();
    graph.reopenBlockedByStatement("not present");
    graph.reopenBlockedByStatement("the same hard lemma");
    assertThat(graph.getObligation("shared-a").status()).isEqualTo("open");
    graph.recordExternal("external", "subject", Map.of("k", "v"));

    ProofGraphSnapshot minimum = graph.minimalSubgraph(List.of("goal", "missing"));
    assertThat(minimum.obligations()).containsKeys("goal", "shared-a");
    assertThat(minimum.versions()).containsKeys("goal", "shared-a");
    assertThat(graph.snapshot().aliases()).isNotNull();
    assertThat(graph.obligations()).hasSize(3);
    assertThat(graph.claimNodes()).isEmpty();
    assertThat(graph.edges()).isNotEmpty();
    assertThat(graph.audit()).isNotEmpty();
    assertThat(graph.frozen()).isFalse();
    graph.freeze();
    graph.freeze();
    assertThat(graph.frozen()).isTrue();
    assertThatThrownBy(() -> graph.refuteObligation("goal", null))
        .isInstanceOf(IllegalStateException.class);

    ProofGraphEdge dangling = edge(GraphEdgeType.DEPENDS_ON, "missing-a", "missing-b", null);
    ProofGraphSnapshot malformed =
        new ProofGraphSnapshot(
            ProofGraphFixtures.PROBLEM_HASH,
            false,
            Map.of(),
            Map.of(),
            Map.of(dangling.edgeId(), dangling),
            Map.of("loop-a", "loop-b", "loop-b", "loop-a"),
            Set.of(),
            Map.of(),
            List.of());
    ProofGraphStore restored = ProofGraphStore.restore(malformed, ProofGraphPolicy.defaults());
    assertThat(restored.edges()).hasSize(1);
    assertThat(restored.version("loop-a")).isZero();
  }

  @Test
  void policyValidationRejectsEveryInvalidDimension() {
    assertThatThrownBy(() -> policy(0, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy(1, 0)).isInstanceOf(IllegalArgumentException.class);
    ProofGraphPolicy p = ProofGraphPolicy.defaults();
    for (double value : List.of(-0.1d, 1.1d)) {
      assertThatThrownBy(
              () ->
                  new ProofGraphPolicy(
                      1, 1, value, p.bridgeSimilarityThreshold(), 2,
                      1, 1, 1, 1, 1, 1, 1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  new ProofGraphPolicy(
                      1, 1, p.closeObligationThreshold(), value, 2,
                      1, 1, 1, 1, 1, 1, 1))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                new ProofGraphPolicy(
                    1, 1, 0.5d, 0.5d, 1,
                    1, 1, 1, 1, 1, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ProofGraphPolicy policy(int nodes, int edges) {
    ProofGraphPolicy p = ProofGraphPolicy.defaults();
    return new ProofGraphPolicy(
        nodes,
        edges,
        p.closeObligationThreshold(),
        p.bridgeSimilarityThreshold(),
        p.sharedBottleneckMinRoutes(),
        p.obligationBaseWeight(),
        p.obligationMainGoalWeight(),
        p.obligationCentralityWeight(),
        p.obligationDependencyWeight(),
        p.obligationSharedRouteWeight(),
        p.obligationFailureWeight(),
        p.obligationConflictWeight());
  }

  private static ProofGraphEdge edge(
      GraphEdgeType type, String source, String target, String evidence) {
    return new ProofGraphEdge(null, type, evidence, null, source, target);
  }

  private static ProofObligation obligation(
      String id, String statement, List<String> routes, List<String> dependencies) {
    return detailed(
        id,
        statement,
        routes,
        dependencies,
        ObligationKind.SUBGOAL,
        0.8d,
        0.7d,
        null,
        "open");
  }

  private static ProofObligation detailed(
      String id,
      String statement,
      List<String> routes,
      List<String> dependencies,
      ObligationKind kind,
      double priority,
      double centrality,
      String fingerprint,
      String status) {
    return new ProofObligation(
        List.of(),
        centrality,
        "",
        dependencies,
        List.of(),
        List.of(),
        fingerprint,
        kind,
        statement,
        id,
        priority,
        ProofGraphFixtures.PROBLEM_HASH,
        List.of(),
        routes,
        statement,
        status);
  }

  private static io.github.aililuola.mathproofmesh.contract.MessageEnvelope messageWithProblemHash(
      io.github.aililuola.mathproofmesh.contract.MessageEnvelope source, String hash) {
    return new io.github.aililuola.mathproofmesh.contract.MessageEnvelope(
        source.artifactRefs(),
        source.assumptions(),
        source.conclusion(),
        "",
        source.createdAt(),
        source.dependencies(),
        source.dependencyRefs(),
        source.evidenceType(),
        source.memoryTier(),
        source.messageId(),
        source.messageType(),
        source.normalizationConfidence(),
        source.normalizedStatement(),
        hash,
        source.quantifiers(),
        source.rawSourceRef(),
        source.roundCreated(),
        source.schemaVersion(),
        source.scopeLimitations(),
        source.sourceAgentId(),
        source.sourceRole(),
        source.sourceRouteId(),
        source.statement(),
        source.targetRouteIds(),
        source.ttlRounds(),
        source.variableBindings(),
        source.verificationConfidence(),
        source.verificationStatus());
  }
}
