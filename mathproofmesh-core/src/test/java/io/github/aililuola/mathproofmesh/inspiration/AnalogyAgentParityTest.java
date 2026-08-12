package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AnalogyAgentParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_analogy_uses_verified_local_record_and_preserves_transfer_limits",
        "test_missing_analogy_library_degrades_to_empty_result");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("AnalogyAgentParityTest", authorityFunction);
  }
}
