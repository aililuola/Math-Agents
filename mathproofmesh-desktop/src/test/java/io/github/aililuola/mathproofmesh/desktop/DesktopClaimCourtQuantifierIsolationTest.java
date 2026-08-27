package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimCourtQuantifierIsolationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void forallAndExistsClaimsHaveDistinctProductionSemanticIdentities()
      throws Exception {
    var forallClaim =
        DesktopClaimCourtSemanticContextTestSupport.freeze(
            temporaryDirectory.resolve("forall"),
            "forall",
            "forall",
            List.of("x"),
            List.of("H"),
            List.of("scope=A"),
            "positive");
    var existsClaim =
        DesktopClaimCourtSemanticContextTestSupport.freeze(
            temporaryDirectory.resolve("exists"),
            "exists",
            "exists",
            List.of("x"),
            List.of("H"),
            List.of("scope=A"),
            "positive");

    assertThat(forallClaim.claimSemanticHash())
        .isNotEqualTo(existsClaim.claimSemanticHash());
    assertThat(forallClaim.courtCaseId()).isNotEqualTo(existsClaim.courtCaseId());
  }
}
