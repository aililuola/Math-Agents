package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.communication.artifact.MathematicalArtifactBroker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BrokerArtifactProtectedAuthorityTest {
  @Test
  void brokerHasNoAuthorityMutationApiOrProtectedControllerDependency() throws Exception {
    Set<String> methods =
        Arrays.stream(MathematicalArtifactBroker.class.getDeclaredMethods())
            .map(method -> method.getName())
            .collect(Collectors.toSet());
    assertThat(methods)
        .doesNotContain(
            "promoteFact", "verifyClaim", "registerNegative", "closeMainGoal", "applyPivot");

    Path source =
        projectRoot()
            .resolve(
                "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/communication/artifact/MathematicalArtifactBroker.java");
    String text = Files.readString(source);
    assertThat(text)
        .doesNotContain(
            "NegativeKnowledgeRegistry",
            "ClaimLifecycleController",
            "ProofGraphStore",
            "SemanticPivotController",
            "StrategyPortfolioRegistry",
            "RootGoalContract");
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
    throw new IllegalStateException("project root not found");
  }
}
