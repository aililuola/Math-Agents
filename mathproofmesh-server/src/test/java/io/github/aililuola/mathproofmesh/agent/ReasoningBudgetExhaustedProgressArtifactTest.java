package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReasoningBudgetExhaustedProgressArtifactTest {
  @Test
  void budgetErrorCarriesExactTraceAndResponseReferencesWithoutRawReasoning(@TempDir Path directory) {
    String material = "same-support minimal representative";
    var frame = ResearchCheckpointRunnerTestSupport.frame(material);
    AtomicReference<ResearchCheckpointCapture> capture = new AtomicReference<>();
    try (var fixture =
        ResearchCheckpointRunnerTestSupport.fixture(
            directory,
            "budget-progress",
            request -> {
              String trace = ResearchCheckpointRunnerTestSupport.marker(frame);
              var metadata = JsonNodeFactory.instance.objectNode();
              metadata
                  .putObject("reasoning")
                  .put("present", true)
                  .put("characters", trace.length())
                  .put("public_checkpoint_text", trace);
              return ResearchCheckpointRunnerTestSupport.response(
                  "", metadata, "length", request.maxOutputTokens());
            },
            1)) {
      Throwable failure =
          catchThrowable(
              () ->
                  fixture
                      .runner()
                      .callCheckpointed(
                          "budget-progress",
                          "budget-call",
                          "general",
                          ResearchCheckpointRunnerTestSupport.prompt(),
                          fixture.pool().get("agent-a"),
                          "depth",
                          true,
                          "max",
                          capture::set));

      assertThat(failure).isInstanceOf(ReasoningBudgetExhaustedError.class);
      var progress = ((ReasoningBudgetExhaustedError) failure).progress();
      assertThat(progress)
          .containsKeys(
              "provider_call_id",
              "response_artifact_ref",
              "reasoning_trace_call_id",
              "reasoning_trace_task_id",
              "reasoning_trace_sha256",
              "reasoning_trace_characters");
      assertThat(progress.toString()).doesNotContain(material);
      assertThat(capture.get().traceFrames()).hasSize(1);
    }
  }
}
