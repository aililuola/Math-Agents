package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ComputationInfrastructureNoProblemSpecificDependencyTest {
  @Test
  void issue010InfrastructureDoesNotDependOnGreedyGcdProblemCode() throws Exception {
    Path source = Path.of("src/main/java/io/github/aililuola/mathproofmesh/computation");
    String combined;
    try (var files = Files.list(source)) {
      combined =
          files.filter(path -> path.getFileName().toString().startsWith("Computation"))
              .map(
                  path -> {
                    try {
                      return Files.readString(path);
                    } catch (java.io.IOException exception) {
                      throw new java.io.UncheckedIOException(exception);
                    }
                  })
              .collect(java.util.stream.Collectors.joining("\n"));
    }
    assertThat(combined)
        .doesNotContain("GreedyGcd")
        .doesNotContain("isGreedyGcdSequenceProblem")
        .doesNotContain("GreedyGcdNegativeKnowledgeSeeds");
  }
}
