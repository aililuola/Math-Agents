package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NegativeKnowledgeAtomicMutationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void blockedRevisionLeavesEveryActiveProductionProjectionUnchanged() throws Exception {
    try (DesktopNegativeKnowledgeTestHarness harness =
        DesktopNegativeKnowledgeTestHarness.open(
            temporaryDirectory.resolve("atomic-revision"), "atomic-revision")) {
      harness.freezeAndCreateValidRoute();
      DesktopNegativeKnowledgeTestHarness.ProductionState before = harness.state();

      boolean revised =
          harness.attemptRevision(1, DesktopNegativeKnowledgeTestHarness.alias(0));

      assertThat(revised).isFalse();
      assertThat(harness.state()).isEqualTo(before);
      assertThat(harness.containsInvalidActiveState(1)).isFalse();
    }
  }

  @Test
  void blockedInspirationLeavesEveryActiveProductionProjectionUnchanged() throws Exception {
    try (DesktopNegativeKnowledgeTestHarness harness =
        DesktopNegativeKnowledgeTestHarness.open(
            temporaryDirectory.resolve("atomic-inspiration"), "atomic-inspiration")) {
      harness.freezeAndCreateValidRoute();
      DesktopNegativeKnowledgeTestHarness.ProductionState before = harness.state();

      harness.attemptInspiration(2, DesktopNegativeKnowledgeTestHarness.alias(1));

      assertThat(harness.state()).isEqualTo(before);
      assertThat(harness.containsInvalidActiveState(2)).isFalse();
    }
  }

  @Test
  void blockedRevisionChecksTheCompleteDraftBeforeAnyActiveStateMutation() throws IOException {
    String method = coordinatorMethod(
        "private boolean prepareRouteRevision(", "private void archiveCurrentAttempt(");
    int gate = method.indexOf("negativeKnowledgeGate.requireAllAllowed");

    assertThat(gate).isGreaterThanOrEqualTo(0);
    assertAfter(method, gate, "strategyArchive.registerChild");
    assertAfter(method, gate, "strategyBlueprints.put");
    assertAfter(method, gate, "goalLinks.put");
    assertAfter(method, gate, "archiveCurrentAttempt");
    assertAfter(method, gate, "checkpoints.branchForStrategy");
    assertAfter(method, gate, "target.strategy = revision");
    assertAfter(method, gate, "addBlueprintObligations");
  }

  @Test
  void blockedInspirationChecksAllDraftsBeforeItsFirstBusinessMutation() throws IOException {
    String method = coordinatorMethod(
        "private void materializeInspiration(", "private StrategyCard inspiredStrategy(");
    int gate = method.indexOf("negativeKnowledgeGate.requireAllAllowed");

    assertThat(gate).isGreaterThanOrEqualTo(0);
    assertAfter(method, gate, "admittedStrategies =");
    assertAfter(method, gate, "addRoute");
    assertAfter(method, gate, "typedMemory.addInsight");
    assertAfter(method, gate, "addObligation");
    assertAfter(method, gate, "enqueueProofTask");
  }

  private static void assertAfter(String source, int gate, String mutation) {
    assertThat(source.indexOf(mutation)).isGreaterThan(gate);
  }

  private static String coordinatorMethod(String startMarker, String endMarker) throws IOException {
    String source = Files.readString(projectRoot().resolve(
        "mathproofmesh-desktop/src/main/java/io/github/aililuola/mathproofmesh/desktop/DesktopSolveCoordinator.java"));
    int start = source.indexOf(startMarker);
    int end = source.indexOf(endMarker, start + startMarker.length());
    assertThat(start).isGreaterThanOrEqualTo(0);
    assertThat(end).isGreaterThan(start);
    return source.substring(start, end);
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
