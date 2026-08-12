package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ProofGraphConcurrencyTest {

  @Test
  void concurrentVersionedMutationHasExactlyOneWinner() throws Exception {
    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    graph.addObligation(
        ProofGraphFixtures.obligation("target", "target", "route-a", List.of()));
    MessageEnvelope fact = ProofGraphFixtures.fact("fact", "target");
    graph.addClaimNode(fact);
    long expectedVersion = graph.version("target");
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<String>> attempts =
        List.of(
            () -> closeAfter(start, graph, fact, expectedVersion),
            () -> closeAfter(start, graph, fact, expectedVersion));
    List<String> outcomes = new ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var futures = attempts.stream().map(executor::submit).toList();
      start.countDown();
      for (var future : futures) {
        try {
          outcomes.add(future.get());
        } catch (ExecutionException exception) {
          assertThat(exception.getCause())
              .isInstanceOf(ProofGraphConflictException.class);
          outcomes.add("conflict");
        }
      }
    }

    assertThat(outcomes).containsExactlyInAnyOrder("closed", "conflict");
    assertThat(graph.getObligation("target").status()).isEqualTo("closed");
  }

  @Test
  void largeProjectionTraversesWithoutPerNodeRepositoryAccess() {
    ProofGraphStore graph =
        new ProofGraphStore(
            ProofGraphFixtures.PROBLEM_HASH,
            new ProofGraphPolicy(
                2500,
                5000,
                0.8,
                0.78,
                2,
                1.0,
                2.0,
                1.0,
                0.5,
                0.25,
                1.0,
                2.0));
    graph.addObligation(
        ProofGraphFixtures.obligation("node-0", "node 0", "route-a", List.of()));
    for (int index = 1; index < 1000; index++) {
      graph.addObligation(
          ProofGraphFixtures.obligation(
              "node-" + index,
              "node " + index,
              "route-a",
              List.of("node-" + (index - 1))));
    }

    assertThat(graph.dependencyClosure(List.of("node-999"))).hasSize(1000);
    assertThat(graph.topologicalOrder()).hasSize(1000);
  }

  private static String closeAfter(
      CountDownLatch start,
      ProofGraphStore graph,
      MessageEnvelope fact,
      long expectedVersion)
      throws InterruptedException {
    start.await();
    return graph.closeObligation(
            "target", fact.messageId(), fact.verificationConfidence(), expectedVersion)
        .status();
  }
}
