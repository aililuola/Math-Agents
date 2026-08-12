package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class NoProofControlFactBypassParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_proof_control_package_has_no_fact_or_graph_close_authority",
        "test_active_scope_gate_blocks_eventual_fact_before_memory_or_graph");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("NoProofControlFactBypassParityTest", authorityFunction);
  }
}
