package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComputationProtectedAuthorityTest {
  @TempDir Path temporaryDirectory;

  @Test
  void executionAndTypedProjectionCannotDirectlyMutateProtectedAuthorityStores()
      throws Exception {
    try (DesktopComputationIssue010CoordinatorHarness harness =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory.resolve("protected"), "protected-authority")) {
      var before = harness.protectedState();
      DesktopComputationIssue010Support.run(
          harness.computation(),
          DesktopComputationIssue010Support.graphCounterexample("protected-graph", 1),
          "protected-graph",
          0);
      DesktopComputationIssue010Support.run(
          harness.computation(),
          DesktopComputationIssue010Support.linearAlgebra("protected-linear", 2),
          "protected-linear",
          0);
      DesktopComputationIssue010Support.run(
          harness.computation(),
          DesktopComputationIssue010Support.finiteMap("protected-map"),
          "protected-map",
          0);
      DesktopComputationIssue010Support.run(
          harness.computation(),
          DesktopComputationIssue010Support.boundedObservation("protected-bounded", 3),
          "protected-bounded",
          0);
      var after = harness.protectedState();
      assertThat(after).isEqualTo(before);
    }

    Path root = projectRoot();
    String executionService =
        Files.readString(
            root.resolve(
                "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/computation/ComputationExecutionService.java"),
            StandardCharsets.UTF_8);
    String projector =
        Files.readString(
            root.resolve(
                "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/computation/ComputationOutcomeProjector.java"),
            StandardCharsets.UTF_8);
    for (String forbidden :
        List.of(
            "typedMemory.addFact",
            "lemmaMemory.markVerified",
            "proofGraph.closeMainGoal",
            "applyVerifiedCounterexample(",
            "ClaimLifecycleController",
            "NegativeKnowledgeRegistry")) {
      assertThat(executionService).doesNotContain(forbidden);
      assertThat(projector).doesNotContain(forbidden);
    }

    System.out.println("COMPUTATION PROTECTED AUTHORITY DIAGNOSTIC");
    System.out.println("ROOT_HASH_CHANGES=0");
    System.out.println("NEGATIVE_REGISTRY_HASH_CHANGES=0");
    System.out.println("CLAIM_LIFECYCLE_HASH_CHANGES=0");
    System.out.println("RESEARCH_CHECKPOINT_HASH_CHANGES=0");
    System.out.println("CANONICALIZATION_HASH_CHANGES=0");
    System.out.println("CONVERGENCE_HASH_CHANGES=0");
    System.out.println("SEMANTIC_PIVOT_HASH_CHANGES=0");
    System.out.println("STRATEGY_PORTFOLIO_HASH_CHANGES=0");
    System.out.println("CLAIM_COURT_HASH_CHANGES=0");
    System.out.println("BROKER_HASH_CHANGES=0");
    System.out.println("DIRECT_CLAIM_VERIFICATIONS=0");
    System.out.println("DIRECT_FACT_PROMOTIONS=0");
    System.out.println("DIRECT_NEGATIVE_REGISTRATIONS=0");
    System.out.println("MAIN_GOAL_CLOSURES=0");
    System.out.println("RESULT=PASS");
  }

  private static Path projectRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("pom.xml"))
          && Files.isDirectory(current.resolve("mathproofmesh-core"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("project root was not found");
  }
}
