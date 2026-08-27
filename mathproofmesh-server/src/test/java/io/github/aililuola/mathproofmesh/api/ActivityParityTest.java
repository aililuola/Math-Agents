package io.github.aililuola.mathproofmesh.api;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ActivityParityTest {
  @TempDir Path temporaryDirectory;

  static Stream<String> authorityCases() {
    return Stream.of(
        "test_activity_stream_redacts_and_persists",
        "test_activity_stream_infers_and_preserves_topology_parent",
        "test_compact_console_view_prints_one_snapshot_per_task_without_ansi",
        "test_long_agent_call_emits_content_free_heartbeat",
        "test_activity_stream_continues_existing_timeline_after_restart");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ApiParityScenarios.verify("ActivityParityTest", authorityFunction, temporaryDirectory);
  }
}
