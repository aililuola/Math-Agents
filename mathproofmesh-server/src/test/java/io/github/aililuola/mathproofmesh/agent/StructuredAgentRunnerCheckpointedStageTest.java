package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructuredAgentRunnerCheckpointedStageTest {
  @Test
  void checkpointedCallCommitsTraceAndEnvelopeBeforeApplication(@TempDir Path directory) {
    var frame = ResearchCheckpointRunnerTestSupport.frame("same-support representative");
    List<ResearchCheckpointCapture> captures = new ArrayList<>();
    try (var fixture =
        ResearchCheckpointRunnerTestSupport.fixture(
            directory,
            "checkpointed-stage",
            request ->
                ResearchCheckpointRunnerTestSupport.envelopeResponse(
                    frame,
                    "continue",
                    ResearchCheckpointRunnerTestSupport.marker(frame)),
            1)) {
      var result =
          fixture
              .runner()
              .callCheckpointed(
                  "checkpointed-stage",
                  "research-call",
                  "general",
                  ResearchCheckpointRunnerTestSupport.prompt(),
                  fixture.pool().get("agent-a"),
                  "depth",
                  true,
                  "max",
                  captures::add);

      assertThat(result.result().value().answer()).isEqualTo("continue");
      assertThat(result.traceFrames()).hasSize(1);
      assertThat(captures).isNotEmpty();
      assertThat(fixture.runner().apply(result.result(), "test-application")).isTrue();
    }
  }

  @Test
  void exactAllowlistRejectsAuditAndFinalStages() {
    PromptBundle<ResearchCheckpointRunnerTestSupport.Answer> forbidden =
        new PromptBundle<>(
            "triage",
            "system",
            "user",
            ResearchCheckpointRunnerTestSupport.Answer.class,
            0.0d,
            100,
            false,
            PromptJsonSchema.forType(ResearchCheckpointRunnerTestSupport.Answer.class));
    assertThatThrownBy(() -> new ResearchCheckpointedPromptFactory().checkpoint(forbidden))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden");
    assertThat(ResearchCheckpointedPromptFactory.allowedResearchStages())
        .doesNotContain(
            "triage",
            "claim_salvage_review",
            "synthesis",
            "tool_replay",
            "experiment_codegen");
  }
}
