package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SynthesisReadinessGateParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_active_readiness_blocks_open_core_scope_and_conflict",
        "test_necessary_only_link_and_unadmitted_fact_block_synthesis",
        "test_shadow_records_and_ready_graph_passes_active",
        "test_verified_complete_candidate_scopes_planning_debt_and_assumptions",
        "test_verified_candidate_still_blocks_an_explicit_open_dependency");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("SynthesisReadinessGateParityTest", authorityFunction);
  }
}
