package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class NegativeKnowledgeNoBypassArchitectureTest {
  @Test
  void desktopUsesOneTypedGateInsteadOfTheOldKeywordGuardrail() throws IOException {
    String source = coordinatorSource();

    assertThat(source).doesNotContain("GreedyGcdStrategyGuardrails.violates");
    assertThat(Files.exists(projectRoot().resolve(
            "mathproofmesh-desktop/src/main/java/io/github/aililuola/mathproofmesh/desktop/GreedyGcdStrategyGuardrails.java")))
        .isFalse();
    assertThat(source).contains("NegativeKnowledgeAdmissionGate");
    assertThat(source).contains("GreedyGcdNegativeKnowledgeSeeds");
  }

  @Test
  void everyInitialRevisionAndInspirationMutationIsPrecededByTheUnifiedGate()
      throws IOException {
    String source = coordinatorSource();
    String initial = methodBody(source, "private void generateAndAdmitStrategies()", "private void ensureInitialRoutes()");
    String widening = methodBody(source, "private boolean widenRoutes()", "private boolean deepenRoute(");
    String revision = methodBody(source, "private boolean prepareRouteRevision(", "private void archiveCurrentAttempt(");
    String inspiration = methodBody(source, "private void materializeInspiration(", "private StrategyCard inspiredStrategy(");

    assertBefore(initial, "negativeKnowledgeGate.requireAllAllowed", "strategyArchive.archive");
    assertBefore(widening, "negativeKnowledgeGate.requireAllAllowed", "addRoute(candidate");
    assertBefore(revision, "negativeKnowledgeGate.requireAllAllowed", "strategyArchive.registerChild");
    assertBefore(inspiration, "negativeKnowledgeGate.requireAllAllowed", "admittedStrategies =");
    assertBefore(inspiration, "negativeKnowledgeGate.requireAllAllowed", "typedMemory.addInsight");
    assertBefore(inspiration, "negativeKnowledgeGate.requireAllAllowed", "enqueueProofTask");
  }

  @Test
  void proofGraphMutationsCannotBypassItsNegativeAwareWriter() throws IOException {
    String graph = Files.readString(projectRoot().resolve(
        "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofgraph/ProofGraphStore.java"));

    assertThat(graph).contains("NegativeAwareProofGraphWriter");
    assertThat(graph).contains("addRootGoalObligation");
    assertThat(graph).contains("addFalsificationObligation");
    assertThat(graph).contains("addObligationUnchecked");
  }

  private static void assertBefore(String source, String guard, String mutation) {
    assertThat(source.indexOf(guard)).isGreaterThanOrEqualTo(0);
    assertThat(source.indexOf(mutation)).isGreaterThan(source.indexOf(guard));
  }

  private static String methodBody(String source, String startMarker, String endMarker) {
    int start = source.indexOf(startMarker);
    int end = source.indexOf(endMarker, start + startMarker.length());
    assertThat(start).isGreaterThanOrEqualTo(0);
    assertThat(end).isGreaterThan(start);
    return source.substring(start, end);
  }

  private static String coordinatorSource() throws IOException {
    return Files.readString(projectRoot().resolve(
        "mathproofmesh-desktop/src/main/java/io/github/aililuola/mathproofmesh/desktop/DesktopSolveCoordinator.java"));
  }

  private static Path projectRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("config/proof-control-active.yaml"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("project root was not found");
  }
}
