package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimCourtVariableBindingIsolationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void differentVariableBindingsHaveDistinctProductionSemanticIdentities()
      throws Exception {
    var first =
        DesktopClaimCourtSemanticContextTestSupport.freeze(
            temporaryDirectory.resolve("first"),
            "first",
            "forall",
            List.of("x", "domain-element"),
            List.of("H"),
            List.of("scope=A"),
            "positive");
    var second =
        DesktopClaimCourtSemanticContextTestSupport.freeze(
            temporaryDirectory.resolve("second"),
            "second",
            "forall",
            List.of("x", "codomain-element"),
            List.of("H"),
            List.of("scope=A"),
            "positive");

    assertThat(first.claimSemanticHash()).isNotEqualTo(second.claimSemanticHash());
  }
}
