package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimCourtNoProblemSpecificDependencyTest {
  @Test
  void productionPackageContainsNoProblemSpecificStrategyDependency() throws IOException {
    Path workingDirectory = Path.of("").toAbsolutePath();
    Path moduleRoot =
        Files.isDirectory(workingDirectory.resolve("mathproofmesh-core"))
            ? workingDirectory.resolve("mathproofmesh-core")
            : workingDirectory;
    Path root =
        moduleRoot.resolve(
            "src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/claimcourt");
    List<String> forbidden =
        List.of(
            "GreedyGcd",
            "isGreedyGcdSequenceProblem",
            "GreedyGcdNegativeKnowledgeSeeds",
            "a1=6",
            "a1=15",
            "hitting set");
    try (var paths = Files.walk(root)) {
      List<Path> violations =
          paths.filter(path -> path.toString().endsWith(".java"))
              .filter(
                  path -> {
                    try {
                      String source = Files.readString(path);
                      return forbidden.stream().anyMatch(source::contains);
                    } catch (IOException exception) {
                      throw new java.io.UncheckedIOException(exception);
                    }
                  })
              .toList();
      assertThat(violations).isEmpty();
    }
  }
}
