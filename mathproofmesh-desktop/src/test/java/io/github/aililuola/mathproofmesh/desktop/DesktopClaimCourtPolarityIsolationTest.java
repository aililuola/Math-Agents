package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimCourtPolarityIsolationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void oppositePolaritiesHaveDistinctProductionSemanticIdentities()
      throws Exception {
    var positive =
        DesktopClaimCourtSemanticContextTestSupport.freeze(
            temporaryDirectory.resolve("positive"),
            "positive",
            "forall",
            List.of("x"),
            List.of("H"),
            List.of("scope=A"),
            "positive");
    var negative =
        DesktopClaimCourtSemanticContextTestSupport.freeze(
            temporaryDirectory.resolve("negative"),
            "negative",
            "forall",
            List.of("x"),
            List.of("H"),
            List.of("scope=A"),
            "negative");

    assertThat(positive.claimSemanticHash())
        .isNotEqualTo(negative.claimSemanticHash());
  }
}
