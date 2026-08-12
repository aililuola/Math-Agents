package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MetaStrategistParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_meta_strategist_explains_decision_from_observable_metrics",
        "test_cooled_mechanism_is_not_selected_consecutively");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("MetaStrategistParityTest", authorityFunction);
  }
}
