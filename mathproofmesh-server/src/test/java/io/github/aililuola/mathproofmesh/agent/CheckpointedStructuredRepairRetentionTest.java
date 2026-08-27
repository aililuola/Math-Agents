package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckpointedStructuredRepairRetentionTest {
  @Test
  void malformedEnvelopeRepairCannotDeleteAlreadyCommittedTraceFrame(@TempDir Path directory) {
    var frame = ResearchCheckpointRunnerTestSupport.frame("finding before malformed final JSON");
    AtomicInteger calls = new AtomicInteger();
    List<ResearchCheckpointCapture> captures = new ArrayList<>();
    try (var fixture =
        ResearchCheckpointRunnerTestSupport.fixture(
            directory,
            "repair-retention",
            request -> {
              if (calls.incrementAndGet() == 1) {
                String trace = ResearchCheckpointRunnerTestSupport.marker(frame);
                var metadata = JsonNodeFactory.instance.objectNode();
                metadata
                    .putObject("reasoning")
                    .put("present", true)
                    .put("characters", trace.length())
                    .put("public_checkpoint_text", trace);
                return ResearchCheckpointRunnerTestSupport.response(
                    "{\"public_checkpoint\":", metadata, "stop", 20);
              }
              return ResearchCheckpointRunnerTestSupport.envelopeResponse(null, "repaired", null);
            },
            1)) {
      var result =
          fixture
              .runner()
              .callCheckpointed(
                  "repair-retention",
                  "malformed",
                  "general",
                  ResearchCheckpointRunnerTestSupport.prompt(),
                  fixture.pool().get("agent-a"),
                  "depth",
                  true,
                  "max",
                  captures::add);

      assertThat(result.result().value().answer()).isEqualTo("repaired");
      assertThat(result.result().repaired()).isTrue();
      assertThat(captures).anyMatch(capture -> capture.traceFrames().size() == 1);
    }
  }
}
