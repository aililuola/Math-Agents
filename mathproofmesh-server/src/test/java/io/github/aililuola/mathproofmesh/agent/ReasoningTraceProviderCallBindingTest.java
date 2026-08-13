package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReasoningTraceProviderCallBindingTest {
  @Test
  void readsOnlyTheExactProviderCallRatherThanLatestStageApproximation(@TempDir Path directory) {
    var first = ResearchCheckpointRunnerTestSupport.frame("first exact call");
    var second = ResearchCheckpointRunnerTestSupport.frame("second exact call");
    try (var fixture =
        ResearchCheckpointRunnerTestSupport.fixture(
            directory,
            "provider-binding",
            new io.github.aililuola.mathproofmesh.provider.MockResponder() {
              private int calls;

              @Override
              public io.github.aililuola.mathproofmesh.provider.LLMResponse respond(
                  io.github.aililuola.mathproofmesh.provider.ProviderRequest request) {
                var frame = calls++ == 0 ? first : second;
                return ResearchCheckpointRunnerTestSupport.envelopeResponse(
                    frame, "ok", ResearchCheckpointRunnerTestSupport.marker(frame));
              }
            },
            0)) {
      var one =
          fixture
              .runner()
              .callCheckpointed(
                  "provider-binding",
                  "one",
                  "general",
                  ResearchCheckpointRunnerTestSupport.prompt(),
                  fixture.pool().get("agent-a"),
                  "depth",
                  true,
                  "max",
                  ignored -> {});
      var two =
          fixture
              .runner()
              .callCheckpointed(
                  "provider-binding",
                  "two",
                  "general",
                  ResearchCheckpointRunnerTestSupport.prompt(),
                  fixture.pool().get("agent-a"),
                  "depth",
                  true,
                  "max",
                  ignored -> {});

      assertThat(fixture.traces().readCall(one.checkpointedProviderCallId()).text())
          .contains("first exact call")
          .doesNotContain("second exact call");
      assertThat(fixture.traces().readCall(two.checkpointedProviderCallId()).text())
          .contains("second exact call")
          .doesNotContain("first exact call");
    }
  }
}
