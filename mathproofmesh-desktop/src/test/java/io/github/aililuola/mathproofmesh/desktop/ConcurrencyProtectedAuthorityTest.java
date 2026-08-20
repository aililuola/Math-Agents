package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ConcurrencyProtectedAuthorityTest {
  @Test
  void concurrencyInfrastructureDoesNotImportProtectedMathematicalAuthorityServices()
      throws Exception {
    Path root =
        Path.of("..", "mathproofmesh-core", "src", "main", "java", "io", "github", "aililuola", "mathproofmesh", "concurrency");
    StringBuilder source = new StringBuilder();
    try (var files = Files.walk(root)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        source.append(Files.readString(file));
      }
    }
    assertThat(source.toString())
        .doesNotContain("ExactGoalContractChecker")
        .doesNotContain("NegativeKnowledgeRegistry")
        .doesNotContain("ClaimLifecycleController")
        .doesNotContain("SemanticPivotController")
        .doesNotContain("StrategyPortfolioOptimizer")
        .doesNotContain("RunStateReconciler");
  }
}
