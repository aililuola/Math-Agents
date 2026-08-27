package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NoCoreIdeaAppendRevisionArchitectureTest {
  @Test
  void productionCoordinatorContainsNoLegacyCoreIdeaRepairAppend() throws Exception {
    Path root = Path.of("").toAbsolutePath().normalize();
    while (root != null && !Files.isRegularFile(root.resolve("config/proof-control-active.yaml"))) {
      root = root.getParent();
    }
    assertThat(root).isNotNull();
    String source =
        Files.readString(
            root.resolve(
                "mathproofmesh-desktop/src/main/java/io/github/aililuola/mathproofmesh/desktop/DesktopSolveCoordinator.java"));
    assertThat(source).doesNotContain("Repair only the first invalid bridge");
    assertThat(source).doesNotContain("coreIdea() + repair");
  }
}
