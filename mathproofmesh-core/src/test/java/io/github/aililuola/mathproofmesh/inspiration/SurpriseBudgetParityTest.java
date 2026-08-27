package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SurpriseBudgetParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_surprise_budget_protects_finalization_and_path_caps",
        "test_low_novelty_rejections_enter_cooldown");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("SurpriseBudgetParityTest", authorityFunction);
  }
}
