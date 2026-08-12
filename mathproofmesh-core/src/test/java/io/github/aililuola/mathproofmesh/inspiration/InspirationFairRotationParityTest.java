package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InspirationFairRotationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_stagnation_rotates_through_every_enabled_inspiration_mechanism");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("InspirationFairRotationParityTest", authorityFunction);
  }
}
