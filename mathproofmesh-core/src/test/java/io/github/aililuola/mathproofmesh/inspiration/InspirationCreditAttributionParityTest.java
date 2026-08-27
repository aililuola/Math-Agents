package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InspirationCreditAttributionParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_surprise_debt_uses_the_same_frozen_route_set_before_and_after",
        "test_existing_route_and_obligation_only_proposals_receive_explicit_credit",
        "test_credit_targets_and_fixed_scopes_survive_checkpoint_restore",
        "test_composed_fact_credits_every_explicit_source_proposal",
        "test_ucb_profiles_include_only_enabled_schedulable_mechanisms");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("InspirationCreditAttributionParityTest", authorityFunction);
  }
}
