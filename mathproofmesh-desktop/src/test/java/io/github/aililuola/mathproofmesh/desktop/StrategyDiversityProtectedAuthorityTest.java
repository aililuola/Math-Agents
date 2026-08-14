package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StrategyDiversityProtectedAuthorityTest {
  private static final String ISSUE_007_BASELINE =
      "d42c896ef353259707f017d7e2a90dbd706e28b7";

  @Test
  void issue007DoesNotAlterEarlierAuthorityOwnersOrUseTheLegacySelector() throws Exception {
    Path root = projectRoot();
    List<String> protectedPaths =
        List.of(
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/ExactGoalContractChecker.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/RootGoalContract.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/ProblemSemanticViewService.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/memory/NegativeKnowledgeRegistry.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/memory/NegativeKnowledgeAdmissionGate.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/AttemptArtifactLedger.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/ClaimLifecycleController.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/research/ResearchCheckpointLedger.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/SemanticPivotLedger.java");
    List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.add("diff");
    command.add("--name-only");
    command.add(ISSUE_007_BASELINE);
    command.add("--");
    command.addAll(protectedPaths);
    Process process = new ProcessBuilder(command).directory(root.toFile()).start();
    assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
    String changed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.exitValue()).isZero();
    assertThat(changed).isBlank();

    String coordinator =
        Files.readString(
            root.resolve(
                "mathproofmesh-desktop/src/main/java/io/github/aililuola/mathproofmesh/desktop/DesktopSolveCoordinator.java"));
    String initial = section(coordinator, "private void generateAndAdmitStrategies()", "private void ensureInitialRoutes()");
    String widening = section(coordinator, "private boolean widenRoutes()", "private boolean deepenRoute(");
    assertThat(initial).doesNotContain("selectDiverseStrategies(");
    assertThat(widening).doesNotContain("selectDiverseStrategies(", "sharesUnverifiedDependency(");
    assertThat(initial).contains("prepareStrategyPortfolio", "applyStrategyPortfolioAtomically");
    assertThat(widening).contains("strategyPortfolioAllowsWidening");
  }

  private static String section(String source, String startMarker, String endMarker) {
    int start = source.indexOf(startMarker);
    int end = source.indexOf(endMarker, start);
    assertThat(start).isGreaterThanOrEqualTo(0);
    assertThat(end).isGreaterThan(start);
    return source.substring(start, end);
  }

  private static Path projectRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("pom.xml"))
          && Files.isDirectory(current.resolve("mathproofmesh-desktop"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("project root was not found");
  }
}
