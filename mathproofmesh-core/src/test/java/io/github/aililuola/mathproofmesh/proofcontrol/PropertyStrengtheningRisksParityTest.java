package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PropertyStrengtheningRisksParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_nonempty_intersection_does_not_imply_subset",
        "test_exists_component_does_not_imply_all_components",
        "test_partial_property_does_not_imply_total_property",
        "test_coverage_does_not_imply_exhaustiveness",
        "test_high_confidence_strengthening_risk_materializes_countermodel",
        "test_explicit_bridge_can_clear_strengthening_risk");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("PropertyStrengtheningRisksParityTest", authorityFunction);
  }
}
