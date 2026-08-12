package io.github.aililuola.mathproofmesh.proofgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Phase17GraphPerformanceBenchmarkTest {
  private static final int NODE_COUNT = 2_000;

  @Test
  void largeDependencyClosureCounterexamplePropagationAndDebtAreMeasured()
      throws IOException {
    buildChain(new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH), "warmup-", 256);

    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    long buildStarted = System.nanoTime();
    buildChain(graph, "node-", NODE_COUNT);
    long buildNanos = System.nanoTime() - buildStarted;

    long closureStarted = System.nanoTime();
    var closure = graph.dependencyClosure(Set.of("node-" + (NODE_COUNT - 1)));
    var order = graph.topologicalOrder();
    double debt = graph.proofDebt("route-a");
    long closureNanos = System.nanoTime() - closureStarted;

    long propagationStarted = System.nanoTime();
    graph.refuteObligation("node-0", null);
    long propagationNanos = System.nanoTime() - propagationStarted;

    assertEquals(NODE_COUNT, closure.size());
    assertEquals(NODE_COUNT, order.size());
    assertTrue(debt > 0.0);
    assertEquals("refuted", graph.getObligation("node-0").status());
    assertTrue(graph.needsReverify("node-" + (NODE_COUNT - 1)));

    Path report =
        Path.of(System.getProperty("mathproofmesh.projectRoot"))
            .resolve("target/benchmark-reports/phase17-proof-graph.json");
    Files.createDirectories(report.getParent());
    Files.writeString(
        report,
        """
        {
          "scenario":"large-proof-graph-closure-counterexample-debt",
          "nodes":%d,
          "build_elapsed_ns":%d,
          "closure_and_debt_elapsed_ns":%d,
          "counterexample_propagation_elapsed_ns":%d,
          "closure_size":%d,
          "topological_order_size":%d,
          "result":"PASS"
        }
        """
            .formatted(
                NODE_COUNT,
                buildNanos,
                closureNanos,
                propagationNanos,
                closure.size(),
                order.size()),
        StandardCharsets.UTF_8);
  }

  private static void buildChain(ProofGraphStore graph, String prefix, int count) {
    for (int index = 0; index < count; index++) {
      String id = prefix + index;
      List<String> dependencies =
          index == 0 ? List.of() : List.of(prefix + (index - 1));
      graph.addObligation(
          ProofGraphFixtures.obligation(id, "claim " + index, "route-a", dependencies));
    }
  }
}
