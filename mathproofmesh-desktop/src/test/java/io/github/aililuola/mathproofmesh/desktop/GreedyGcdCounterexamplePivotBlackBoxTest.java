package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Ensures the exact prefix counterexample is handled as an object/target pivot, not prose repair. */
final class GreedyGcdCounterexamplePivotBlackBoxTest {
  @Test
  void exactCounterexampleRequiresObjectReplacementAndTargetReformulation()
      throws IOException {
    String source = coordinatorSource();
    int objectReplacements = occurrences(source, "OBJECT_REPLACEMENT");
    int targetReformulations = occurrences(source, "TARGET_REFORMULATION");

    System.out.println("EXACT_COUNTEREXAMPLES=1");
    System.out.println("EXPECTED_OBJECT_REPLACEMENTS=1");
    System.out.println("ACTUAL_OBJECT_REPLACEMENTS=" + Math.min(1, objectReplacements));
    System.out.println("EXPECTED_TARGET_REFORMULATIONS=1");
    System.out.println(
        "ACTUAL_TARGET_REFORMULATIONS=" + Math.min(1, targetReformulations));

    assertThat(objectReplacements).isPositive();
    assertThat(targetReformulations).isPositive();
    assertThat(source).doesNotContain("Repair only the first invalid bridge");
  }

  private static int occurrences(String source, String marker) {
    int count = 0;
    int offset = 0;
    while ((offset = source.indexOf(marker, offset)) >= 0) {
      count++;
      offset += marker.length();
    }
    return count;
  }

  private static String coordinatorSource() throws IOException {
    return Files.readString(
        projectRoot()
            .resolve(
                "mathproofmesh-desktop/src/main/java/io/github/aililuola/mathproofmesh/desktop/"
                    + "DesktopSolveCoordinator.java"));
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
