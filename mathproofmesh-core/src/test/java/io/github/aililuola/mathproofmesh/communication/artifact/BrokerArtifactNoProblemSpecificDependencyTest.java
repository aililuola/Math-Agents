package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BrokerArtifactNoProblemSpecificDependencyTest {
  @Test
  void modernBrokerPackageHasNoProblemSpecificProductionDependency() throws IOException {
    Path source = Path.of("src/main/java/io/github/aililuola/mathproofmesh/communication/artifact");
    String text;
    try (var files = Files.list(source)) {
      text =
          files.filter(path -> path.toString().endsWith(".java"))
              .map(
                  path -> {
                    try {
                      return Files.readString(path);
                    } catch (IOException exception) {
                      throw new IllegalStateException(exception);
                    }
                  })
              .collect(java.util.stream.Collectors.joining("\n"));
    }
    assertThat(text)
        .doesNotContain("GreedyGcd", "isGreedyGcdSequenceProblem", "prime support", "a1=6");
  }
}
