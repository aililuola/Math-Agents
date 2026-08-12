package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RouteAdmissionGateParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_active_admission_rewrites_overstrong_and_blocks_unbridged_necessary",
        "test_minimal_sufficient_novel_route_passes",
        "test_shadow_admission_records_but_does_not_block_runtime");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("RouteAdmissionGateParityTest", authorityFunction);
  }
}
