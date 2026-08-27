package io.github.aililuola.mathproofmesh.orchestration;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class V082StaticNoSpecializationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_route_admission_source_orders_blueprint_before_gate",
        "test_no_problem_specific_conditionals_in_production",
        "test_dispatcher_has_no_fact_write_or_direct_close_authority",
        "test_rewrite_execution_calls_semantic_gate_first",
        "test_proof_control_does_not_assign_reasoning_limits");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    OrchestrationParityScenarios.verify("V082StaticNoSpecializationParityTest", authorityFunction);
  }
}
