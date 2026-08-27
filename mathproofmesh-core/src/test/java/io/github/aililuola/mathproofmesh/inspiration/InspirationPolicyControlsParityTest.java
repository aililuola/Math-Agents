package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InspirationPolicyControlsParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_referee_switch_fails_closed_and_per_trigger_route_cap_is_enforced",
        "test_inspiration_tasks_are_admitted_before_calls_and_respect_budget");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("InspirationPolicyControlsParityTest", authorityFunction);
  }
}
