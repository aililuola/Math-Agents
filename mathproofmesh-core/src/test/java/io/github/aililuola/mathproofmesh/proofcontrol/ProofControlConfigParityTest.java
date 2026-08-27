package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ProofControlConfigParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_existing_yaml_defaults_proof_control_off",
        "test_shadow_requires_explicit_enablement",
        "test_active_requires_hierarchical_dependencies",
        "test_fast_lane_rejects_fact_or_sandbox_bypass");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("ProofControlConfigParityTest", authorityFunction);
  }
}
