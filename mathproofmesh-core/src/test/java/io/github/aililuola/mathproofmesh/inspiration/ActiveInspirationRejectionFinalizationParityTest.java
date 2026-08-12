package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ActiveInspirationRejectionFinalizationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_active_rejected_inspiration_survives_blind_finalization");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("ActiveInspirationRejectionFinalizationParityTest", authorityFunction);
  }
}
