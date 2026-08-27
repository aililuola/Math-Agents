package io.github.aililuola.mathproofmesh.orchestration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RefereeClaimLedgerPropagationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_referee_acceptance_updates_claim_ledger",
        "test_referee_rejection_blocks_fact_candidate",
        "test_fact_candidate_requires_recorded_referee_review",
        "test_referee_record_is_exactly_once",
        "test_delta_level_acceptance_requires_claim_mapping",
        "test_explicit_claim_acceptance_maps_to_referee_record");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("RefereeClaimLedgerPropagationParityTest", authorityFunction);
  }
}
