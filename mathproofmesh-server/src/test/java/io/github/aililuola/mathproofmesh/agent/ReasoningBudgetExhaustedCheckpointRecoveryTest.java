package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReasoningBudgetExhaustedCheckpointRecoveryTest {
  @Test
  void completeFrameIsDeliveredBeforeBudgetControlFlowEscapes(@TempDir Path directory) {
    var frame = ResearchCheckpointRunnerTestSupport.frame("durable pre-exhaustion finding");
    List<ResearchCheckpointCapture> captures = new ArrayList<>();
    try (var fixture =
        ResearchCheckpointRunnerTestSupport.fixture(
            directory,
            "budget-recovery",
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
                          "budget-recovery",
                          "exhausted",
                          "general",
                          ResearchCheckpointRunnerTestSupport.prompt(),
                          fixture.pool().get("agent-a"),
                          "depth",
                          true,
                          "max",
                          captures::add));

      assertThat(failure).isInstanceOf(ReasoningBudgetExhaustedError.class);
      assertThat(captures)
          .singleElement()
          .satisfies(capture -> assertThat(capture.traceFrames()).hasSize(1));
    }
  }
}
