package io.github.aililuola.mathproofmesh.workflow;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class V081ResumeExactlyOnceParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_mid_pivot_checkpoint_recovers_without_reexecuting_authority",
        "test_queued_message_before_checkpoint_schedules_one_route_update_after_resume",
        "test_v080_sidecar_without_v081_fields_migrates_to_empty_defaults");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    TemporalParityScenarios.verify("V081ResumeExactlyOnceParityTest", authorityFunction);
  }
}
