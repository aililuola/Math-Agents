package io.github.aililuola.mathproofmesh.inspiration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InspirationBudgetReservationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_active_inspiration_reserves_the_complete_first_cycle",
        "test_assignment_count_drives_exact_inspiration_reservation",
        "test_resume_reconciles_charged_calls_and_releases_orphaned_reserve");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) throws Exception {
    InspirationParityScenarios.verify("InspirationBudgetReservationParityTest", authorityFunction);
  }
}
