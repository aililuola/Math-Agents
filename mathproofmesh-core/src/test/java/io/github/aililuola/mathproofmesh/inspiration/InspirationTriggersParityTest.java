package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InspirationTriggersParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_verified_progress_suppresses_stagnation_but_not_shared_bottleneck",
        "test_all_failed_and_final_repair_failure_choose_mechanism_changes");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("InspirationTriggersParityTest", authorityFunction);
  }
}
