package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ActiveInspirationE2eParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_active_inspiration_builds_llm_prompts_and_materializes_proposals");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("ActiveInspirationE2eParityTest", authorityFunction);
  }
}
