package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class NoveltySignatureParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_mechanism_signature_detects_reworded_duplicate");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("NoveltySignatureParityTest", authorityFunction);
  }
}
