package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimCourtSemanticContextBindingTest {
  @TempDir Path temporaryDirectory;

  @Test
  void productionCourtFreezesServerCompiledClaimLocalContext() throws Exception {
    var frozen =
        DesktopClaimCourtSemanticContextTestSupport.freeze(
            temporaryDirectory.resolve("bound"),
            "bound",
            "forall",
            List.of("x"),
            List.of("H"),
            List.of("local-scope"),
            "positive");

    assertThat(frozen.assumptions()).contains("H");
    assertThat(frozen.quantifiers())
        .anyMatch(
            value ->
                value.variableId()
                        .equals(DesktopClaimCourtSemanticContextTestSupport.VARIABLE_ID)
                    && value.kind().equals("forall"));
    assertThat(frozen.variableBindings())
        .anyMatch(
            value ->
                value.variableId()
                    .equals(DesktopClaimCourtSemanticContextTestSupport.VARIABLE_ID));
    assertThat(frozen.scopeLimitations()).contains("local-scope");
    assertThat(frozen.polarity()).isEqualTo("positive");
  }
}
