package io.github.aililuola.mathproofmesh.orchestration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class NoLegacyClaimRevisionBypassParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_hierarchical_final_revision_never_sees_legacy_claim");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("NoLegacyClaimRevisionBypassParityTest", authorityFunction);
  }
}
