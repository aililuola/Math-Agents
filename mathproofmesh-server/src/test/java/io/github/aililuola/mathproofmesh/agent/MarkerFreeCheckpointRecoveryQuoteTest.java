package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.CheckpointedResearchEnvelope;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ResearchCheckpointFrame;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDraft;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingKind;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingUpdateBatch;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkerFreeCheckpointRecoveryQuoteTest {

  @Test
  void markerFreeRecoveryAcceptsOnlyExactOriginalTraceQuote(@TempDir Path directory) {
    String trace = "private prefix\npublic triangle support\nprivate suffix";
    int start = trace.indexOf("public triangle support");
    ResearchCheckpointFrame valid =
        fallbackFrame(
            "public triangle support",
            start,
            start + "public triangle support".length(),
            CanonicalJson.stableHash("public triangle support"));
    ResearchCheckpointFrame invalid =
        fallbackFrame(
            "public triangle support",
            start,
            start + "public triangle support".length(),
            CanonicalJson.stableHash("wrong quote"));

    RecoveryResult accepted = run(directory.resolve("valid"), trace, valid);
    RecoveryResult rejected = run(directory.resolve("invalid"), trace, invalid);
    assertThat(accepted.captures()).hasSize(1);
    assertThat(accepted.publicCheckpoint()).isEqualTo(valid);
    assertThat(rejected.captures()).isEmpty();
    assertThat(rejected.publicCheckpoint()).isNull();
  }

  private static RecoveryResult run(
      Path directory, String trace, ResearchCheckpointFrame frame) {
    List<ResearchCheckpointCapture> captures = new ArrayList<>();
    try (var fixture =
        ResearchCheckpointRunnerTestSupport.fixture(
            directory,
            "marker-free-recovery",
            request -> {
              CheckpointedResearchEnvelope envelope =
                  new CheckpointedResearchEnvelope(
                      frame,
                      ResearchFindingUpdateBatch.empty(),
                      ContractObjectMapper.toTree(
                          new ResearchCheckpointRunnerTestSupport.Answer("recovered")));
              return ResearchCheckpointRunnerTestSupport.response(
                  ContractObjectMapper.write(envelope),
                  com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                  "stop",
                  20);
            },
            0)) {
      CheckpointedStructuredCallResult<ResearchCheckpointRunnerTestSupport.Answer> result =
          fixture
          .runner()
          .callCheckpointed(
              "marker-free-recovery",
              "recovery-call",
              "general",
              ResearchCheckpointRunnerTestSupport.prompt(),
              fixture.pool().get("agent-a"),
              "depth",
              false,
              null,
              captures::add,
              new ResearchCheckpointFallbackEvidence(trace, CanonicalJson.stableHash(trace)));
      return new RecoveryResult(List.copyOf(captures), result.publicCheckpoint());
    }
  }

  private static ResearchCheckpointFrame fallbackFrame(
      String quote, int start, int end, String hash) {
    return new ResearchCheckpointFrame(
        0,
        "marker-free fallback",
        List.of(
            new ResearchFindingDraft(
                ResearchFindingKind.REPRESENTATION_INSIGHT,
                "triangle support representation",
                "Bound to an exact original-trace quote.",
                List.of(),
                List.of("recovery only"),
                null,
                quote,
                start,
                end,
                hash)));
  }

  private record RecoveryResult(
      List<ResearchCheckpointCapture> captures, ResearchCheckpointFrame publicCheckpoint) {}
}
