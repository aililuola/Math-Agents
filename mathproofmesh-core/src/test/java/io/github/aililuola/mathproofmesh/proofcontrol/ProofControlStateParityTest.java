package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ProofControlStateParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_state_round_trip_is_stable_and_sorted",
        "test_unknown_old_record_is_skipped_with_migration_event",
        "test_missing_payload_initializes_empty_state");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("ProofControlStateParityTest", authorityFunction);
  }
}
