package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CrossRunInspirationLearningParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_cross_run_store_admits_only_cited_verified_positive_experience",
        "test_unverified_run_never_persists_positive_experience",
        "test_cross_run_store_rejects_paths_outside_project",
        "test_historical_outcomes_feed_adaptive_mechanism_selection");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("CrossRunInspirationLearningParityTest", authorityFunction);
  }
}
