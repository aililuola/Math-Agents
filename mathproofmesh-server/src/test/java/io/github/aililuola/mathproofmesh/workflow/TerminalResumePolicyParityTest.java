package io.github.aililuola.mathproofmesh.workflow;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TerminalResumePolicyParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_hard_stopped_run_without_new_work_uses_zero_model_calls",
        "test_pending_task_allows_resume",
        "test_config_change_allows_resume",
        "test_reopen_with_pivot_creates_intervention",
        "test_repeated_normal_resume_is_idempotent");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    TemporalParityScenarios.verify("TerminalResumePolicyParityTest", authorityFunction);
  }
}
