package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ConcurrencyInfrastructureNoProblemSpecificDependencyTest {
  @Test
  void productionPackageContainsNoProblemSpecificRules() throws Exception {
    String source = readProductionSources();
    assertThat(source).doesNotContain("GreedyGcd", "isGreedyGcdSequenceProblem", "a1=6", "a1=15");
  }

  static String readProductionSources() throws Exception {
    Path root = Path.of("src/main/java/io/github/aililuola/mathproofmesh/concurrency");
    StringBuilder sources = new StringBuilder();
    try (var files = Files.walk(root)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
        sources.append(Files.readString(file));
      }
    }
    return sources.toString();
  }
}
