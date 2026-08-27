package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ProofControlLogicTrapsParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_all_proof_control_logic_traps_pass_offline_contracts",
        "test_off_shadow_active_gate_semantics_are_distinct");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("ProofControlLogicTrapsParityTest", authorityFunction);
  }
}
