package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MechanismOntologyParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_mechanism_normalizer_preserves_raw_and_extension_tags",
        "test_route_tags_are_classified_by_dimension_instead_of_copied",
        "test_unknown_labels_cannot_independently_force_a_duplicate",
        "test_known_aliases_are_detected_as_the_same_mechanism");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("MechanismOntologyParityTest", authorityFunction);
  }
}
