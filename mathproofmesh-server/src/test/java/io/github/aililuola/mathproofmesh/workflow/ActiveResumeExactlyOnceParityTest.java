package io.github.aililuola.mathproofmesh.workflow;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ActiveResumeExactlyOnceParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_acknowledged_active_delivery_is_exactly_once_across_resume");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    TemporalParityScenarios.verify("ActiveResumeExactlyOnceParityTest", authorityFunction);
  }
}
