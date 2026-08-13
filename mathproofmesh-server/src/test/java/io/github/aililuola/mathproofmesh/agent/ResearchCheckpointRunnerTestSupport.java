package io.github.aililuola.mathproofmesh.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceStore;
import io.github.aililuola.mathproofmesh.config.StrictYamlConfigLoader;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.CheckpointedResearchEnvelope;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ResearchCheckpointFrame;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDraft;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingKind;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingUpdateBatch;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointFrameParser;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class ResearchCheckpointRunnerTestSupport {
  private ResearchCheckpointRunnerTestSupport() {}

  static Fixture fixture(Path directory, String runId, MockResponder responder, int parseRetries) {
    SystemConfig config =
        new StrictYamlConfigLoader()
            .read(
                """
                agents:
                  - id: agent-a
                    provider: mock
                    model: mock-model
                    roles: [general]
                    thinking_enabled: true
                    reasoning_effort: max
                    pricing:
                      input_per_million: 1.0
                      output_per_million: 2.0
                runtime:
                  request_retries: 0
                """);
    ProviderClientRegistry registry =
        new ProviderClientRegistry(
            config,
            Map.of("agent-a", responder),
            ignored -> request -> {
              throw new AssertionError("research checkpoint test attempted HTTP");
            },
            false);
    AgentPool pool = new AgentPool(config, registry);
    InMemoryProviderCallRepository calls = new InMemoryProviderCallRepository();
    ReasoningTraceStore traces = new ReasoningTraceStore(directory, runId);
    StructuredAgentRunner runner =
        new StructuredAgentRunner(
            pool,
            new ArtifactStore(directory.resolve("artifacts"), runId),
            calls,
            new CallLedger(20, null, BigDecimal.TEN),
            new PromptRedactor(List.of()),
            new BoundedJsonRepairer(16_384),
            traces,
            parseRetries,
            4_096);
    return new Fixture(pool, runner, calls, traces);
  }

  static CheckpointedPromptBundle<Answer> prompt() {
    PromptBundle<Answer> original =
        new PromptBundle<>(
            "independent_exploration",
            "Return one strict answer object.",
            "public route context",
            Answer.class,
            0.0d,
            256,
            false,
            PromptJsonSchema.forType(Answer.class));
    return new ResearchCheckpointedPromptFactory().checkpoint(original);
  }

  static ResearchCheckpointFrame frame(String statement) {
    return new ResearchCheckpointFrame(
        0,
        "A bounded public result.",
        List.of(
            new ResearchFindingDraft(
                ResearchFindingKind.REPRESENTATION_INSIGHT,
                statement,
                "Exact finite structure supports it.",
                List.of(),
                List.of("current route"),
                null,
                null,
                null,
                null,
                null)));
  }

  static String marker(ResearchCheckpointFrame frame) {
    return ResearchCheckpointFrameParser.BEGIN_MARKER
        + "\n"
        + ContractObjectMapper.write(frame)
        + "\n"
        + ResearchCheckpointFrameParser.END_MARKER;
  }

  static LLMResponse envelopeResponse(
      ResearchCheckpointFrame frame, String answer, String publicTrace) {
    var metadata = JsonNodeFactory.instance.objectNode();
    if (publicTrace != null) {
      metadata
          .putObject("reasoning")
          .put("present", true)
          .put("characters", publicTrace.length())
          .put("public_checkpoint_text", publicTrace);
    }
    CheckpointedResearchEnvelope envelope =
        new CheckpointedResearchEnvelope(
            frame,
            ResearchFindingUpdateBatch.empty(),
            ContractObjectMapper.toTree(new Answer(answer)));
    return response(ContractObjectMapper.write(envelope), metadata, "stop", 20);
  }

  static LLMResponse response(
      String text,
      com.fasterxml.jackson.databind.JsonNode metadata,
      String finishReason,
      long outputTokens) {
    return new LLMResponse(
        text,
        "mock-model",
        "mock",
        8,
        outputTokens,
        2.0d,
        "checkpoint-request",
        finishReason,
        false,
        metadata);
  }

  record Answer(String answer) {}

  record Fixture(
      AgentPool pool,
      StructuredAgentRunner runner,
      InMemoryProviderCallRepository calls,
      ReasoningTraceStore traces)
      implements AutoCloseable {
    @Override
    public void close() {
      pool.close();
    }
  }
}
