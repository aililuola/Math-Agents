package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RepresentationSwitchboardParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_geometry_switchboard_generates_auditable_nonmechanical_candidates",
        "test_existing_representation_is_skipped_when_alternatives_exist");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("RepresentationSwitchboardParityTest", authorityFunction);
  }
}
