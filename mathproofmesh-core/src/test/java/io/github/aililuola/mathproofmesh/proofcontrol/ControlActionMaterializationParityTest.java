package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ControlActionMaterializationParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_dispatcher_executes_an_idempotent_action_once",
        "test_resume_uses_postcondition_before_reexecuting_an_action",
        "test_dispatcher_rejects_missing_sources_and_targets",
        "test_failed_action_has_explicit_failure_reason",
        "test_handler_can_materialize_a_deferred_action",
        "test_every_rewrite_request_materializes_action_and_bridge",
        "test_blueprint_prevents_unnecessary_rewrite_verdict",
        "test_minimal_bridge_record_materializes_obligation");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("ControlActionMaterializationParityTest", authorityFunction);
  }
}
