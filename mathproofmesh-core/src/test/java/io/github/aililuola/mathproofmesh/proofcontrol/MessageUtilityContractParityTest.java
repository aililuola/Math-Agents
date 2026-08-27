package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MessageUtilityContractParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_contract_requires_known_target_and_explicit_effect",
        "test_delivery_without_verified_mathematical_use_has_zero_utility",
        "test_trusted_verified_artifacts_create_usage_credit",
        "test_expired_unused_contract_counts_as_no_use",
        "test_counterexample_is_contract_exempt");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("MessageUtilityContractParityTest", authorityFunction);
  }
}
