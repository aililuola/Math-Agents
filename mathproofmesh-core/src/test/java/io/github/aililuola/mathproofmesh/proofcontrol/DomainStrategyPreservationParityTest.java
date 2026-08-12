package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DomainStrategyPreservationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_original_domain_strategy_archived_before_admission",
        "test_generic_fallback_cannot_replace_original_strategy",
        "test_rewrite_creates_child_lineage",
        "test_failed_child_does_not_delete_parent",
        "test_domain_objects_preserved_in_revised_strategy");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("DomainStrategyPreservationParityTest", authorityFunction);
  }
}
