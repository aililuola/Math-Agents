package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CommonModeAssumptionDomainsParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_protocol_constraints_excluded_from_common_mode",
        "test_semantically_related_assumptions_form_family",
        "test_common_mode_family_creates_challenger",
        "test_multiple_agent_agreement_does_not_promote_assumption",
        "test_non_propositional_bottleneck_is_not_a_common_mode_assumption");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("CommonModeAssumptionDomainsParityTest", authorityFunction);
  }
}
