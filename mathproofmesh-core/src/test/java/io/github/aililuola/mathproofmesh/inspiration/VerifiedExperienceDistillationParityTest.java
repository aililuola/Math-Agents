package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class VerifiedExperienceDistillationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_only_fact_gated_proposal_enters_verified_experience_library",
        "test_rejected_analogy_enters_negative_not_positive_library");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("VerifiedExperienceDistillationParityTest", authorityFunction);
  }
}
