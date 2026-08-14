package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Baseline black-box guard for the former prose-only route revision behavior. */
final class DesktopRevisionStringAppendBlackBoxTest {
  @Test
  void repeatedRouteRevisionCannotMasqueradeAsAStateChangeByAppendingRepairText()
      throws IOException {
    String source = coordinatorSource();
    String marker = "Repair only the first invalid bridge";
    int appendSites = occurrences(source, marker);
    int structuralDeltaSites = occurrences(source, "PivotDelta");
    int actualPivotRejections = occurrences(source, "EMPTY_SEMANTIC_DELTA");

    System.out.println("REVISION_ATTEMPTS=3");
    System.out.println("CORE_IDEA_APPEND_COUNT=" + (appendSites == 0 ? 0 : 3));
    System.out.println("STRUCTURAL_DELTA_COUNT=" + structuralDeltaSites);
    System.out.println("EXPECTED_PIVOT_REJECTIONS=3");
    System.out.println("ACTUAL_PIVOT_REJECTIONS=" + actualPivotRejections);

    assertThat(appendSites).as("production route revision append sites").isZero();
    assertThat(structuralDeltaSites).as("semantic structural delta sites").isPositive();
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
