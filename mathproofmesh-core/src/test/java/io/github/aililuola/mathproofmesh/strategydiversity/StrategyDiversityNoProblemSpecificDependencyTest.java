package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StrategyDiversityNoProblemSpecificDependencyTest {
  @Test
  void productionCoreHasNoProblemSpecificDependency() throws IOException {
    Path root =
        Path.of(
            "src",
            "main",
            "java",
            "io",
            "github",
            "aililuola",
            "mathproofmesh",
            "strategydiversity");
    if (!Files.isDirectory(root)) {
      root = Path.of("mathproofmesh-core").resolve(root);
    }
    String source;
    try (var files = Files.walk(root)) {
      source =
          files.filter(path -> path.toString().endsWith(".java"))
              .sorted()
              .map(StrategyDiversityNoProblemSpecificDependencyTest::read)
              .collect(java.util.stream.Collectors.joining("\n"));
    }
    assertThat(source)
        .doesNotContain(
            "GreedyGcd",
            "isGreedyGcdSequenceProblem",
            "GreedyGcdNegativeKnowledgeSeeds",
            "a1=6",
            "a1=15",
            "support reduction");
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
