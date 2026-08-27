package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ObligationDomainsAndClustersParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_process_obligation_excluded_from_core_debt",
        "test_verification_obligation_not_used_as_route_target",
        "test_semantic_obligations_materialize_cluster");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("ObligationDomainsAndClustersParityTest", authorityFunction);
  }
}
