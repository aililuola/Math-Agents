package io.github.aililuola.mathproofmesh.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ReasoningTraceParityTest {
  @TempDir Path temporaryDirectory;

  static Stream<String> authorityCases() {
    return Stream.of(
        "test_reasoning_trace_is_single_append_only_archive_with_secret_redaction",
        "test_reasoning_trace_cursor_exposes_live_deltas_once",
        "test_deepseek_streaming_trace_preserves_reasoning_chunk_order",
        "test_deepseek_non_streaming_and_failed_stream_trace_lifecycle");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ApiParityScenarios.verify("ReasoningTraceParityTest", authorityFunction, temporaryDirectory);
  }

  @Test
  void bindingIsInheritedByTheVirtualProviderThread() throws Exception {
    ReasoningTraceStore store = new ReasoningTraceStore(temporaryDirectory, "virtual-run");
    ReasoningTraceBinding binding =
        new ReasoningTraceBinding(store, "agent:explore:agent-1", "agent-1", "explore");
    ReasoningTraceBinding.Scope scope = binding.bind();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      assertThat(executor.submit(ReasoningTraceBinding::current).get()).contains(binding);
    } finally {
      scope.close();
    }
  }

  @Test
  void largeArchiveRestartAndCursorScanRemainLinear() throws Exception {
    Path reports = Files.createDirectories(temporaryDirectory.resolve("reports"));
    Path archive = reports.resolve(ReasoningTraceStore.FILE_NAME);
    StringBuilder content = new StringBuilder(6_000_000);
    int priorCalls = 0;
    for (int index = 0; index < 25_000; index++) {
      if (index % 250 == 0) {
        priorCalls++;
        content
            .append("{\"type\":\"start\",\"task_id\":\"task-a\",\"call_index\":")
            .append(priorCalls)
            .append("}\n");
      } else {
        content.append(
            "{\"type\":\"delta\",\"task_id\":\"task-a\",\"text\":\"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\"}\n");
      }
    }
    Files.writeString(archive, content, StandardCharsets.UTF_8);
    long tailOffset = Files.size(archive);

    ReasoningTraceStore restarted =
        assertTimeout(
            Duration.ofSeconds(10),
            () -> new ReasoningTraceStore(temporaryDirectory, "large-run"));
    assertTimeout(
        Duration.ofSeconds(10),
        () -> assertThat(ReasoningTraceStore.readRecords(archive, null, 0).records())
            .hasSize(25_000));

    ReasoningTraceCall next =
        restarted.beginCall("task-a", "agent-a", "verify", false, null);
    Map<String, Object> start =
        ReasoningTraceStore.readRecords(archive, "task-a", tailOffset).records().getFirst();
    assertThat(start.get("call_index")).isEqualTo((long) priorCalls + 1L);
    next.finish(ReasoningTraceCall.Status.CANCELLED);
  }
}
