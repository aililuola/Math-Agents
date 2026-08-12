package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.config.StrictYamlConfigLoader;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.InitialExplorationTurn;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderCallRecord;
import io.github.aililuola.mathproofmesh.provider.ProviderCallState;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.provider.ProviderException;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructuredAgentRunnerTest {
  @TempDir Path temporaryDirectory;

  @Test
  void auditedPipelineRedactsArtifactsReplaysIdempotentlyAndAppliesOnce() {
    AtomicInteger providerCalls = new AtomicInteger();
    MockResponder responder =
        request -> {
          providerCalls.incrementAndGet();
          return response("prefix {\"answer\":\"proved\"} suffix");
        };
    try (Fixture fixture =
        fixture(
            "run-a",
            responder,
            new CallLedger(3, null, BigDecimal.TEN),
            List.of("literal-secret"))) {
      PromptBundle<Answer> bundle =
          bundle(
              "Bearer abcdefghijkl literal-secret api_key=topsecretvalue");

      StructuredCallResult<Answer> first =
          fixture.runner().call(
              "run-a",
              "stable-key",
              "general",
              bundle,
              fixture.pool().get("agent-a"),
              "proof");
      StructuredCallResult<Answer> replay =
          fixture.runner().call(
              "run-a",
              "stable-key",
              "general",
              bundle,
              fixture.pool().get("agent-a"),
              "proof");

      assertThat(first.value().answer()).isEqualTo("proved");
      assertThat(first.runId()).isEqualTo("run-a");
      assertThat(first.callId()).isEqualTo(replay.callId());
      assertThat(providerCalls).hasValue(1);
      ProviderCallRecord stored =
          fixture.calls().findByRun("run-a").getFirst();
      assertThat(stored.state()).isEqualTo(ProviderCallState.SUCCEEDED);
      assertThat(stored.version()).isEqualTo(2);
      assertThat(stored.requestArtifactHash()).isNotBlank();
      assertThat(stored.responseArtifactHash()).isNotBlank();
      assertThat(stored.requestId()).isEqualTo("fixture-request");
      assertThat(
              new String(
                  fixture.artifacts().read(first.promptArtifactRef()),
                  StandardCharsets.UTF_8))
          .doesNotContain(
              "abcdefghijkl",
              "literal-secret",
              "topsecretvalue")
          .contains("[REDACTED]");
      assertThat(fixture.runner().apply(first, "checkpoint:one")).isTrue();
      assertThat(fixture.runner().apply(replay, "checkpoint:one")).isFalse();
      assertThat(
              fixture
                  .budget()
                  .reconcile(fixture.calls().usageTotals("run-a"))
                  .matches())
          .isTrue();
    }
  }

  @Test
  void boundedRepairOnlyRemovesARepresentationWrapper() {
    try (Fixture fixture =
        fixture(
            "run-repair",
            request -> response("{\"result\":{\"answer\":\"x^2+y^2\"}}"),
            new CallLedger(2, null, BigDecimal.TEN),
            List.of())) {
      StructuredCallResult<Answer> result =
          fixture.runner().call(
              "run-repair",
              "repair-key",
              "general",
              bundle("public prompt"),
              fixture.pool().get("agent-a"),
              "proof");

      assertThat(result.value().answer()).isEqualTo("x^2+y^2");
      assertThat(result.repaired()).isTrue();
      assertThat(
              new BoundedJsonRepairer(4096)
                  .repair("{\"result\":{\"answer\":\"x^2+y^2\"}}"))
          .contains("x^2+y^2");
    }
  }

  @Test
  void strictContractFailureDoesNotRewriteMathematicsOrUnbillSuccess() {
    CallLedger budget = new CallLedger(2, null, BigDecimal.TEN);
    try (Fixture fixture =
        fixture(
            "run-invalid",
            request ->
                response("{\"answer\":\"claim p\",\"extra\":\"invented\"}"),
            budget,
            List.of())) {
      assertThatThrownBy(
              () ->
                  fixture.runner().call(
                      "run-invalid",
                      "invalid-key",
                      "general",
                      bundle("public prompt"),
                      fixture.pool().get("agent-a"),
                      "proof"))
          .isInstanceOf(StructuredOutputError.class)
          .hasMessageContaining("strict contract");

      assertThat(fixture.calls().findByRun("run-invalid"))
          .singleElement()
          .extracting(ProviderCallRecord::state)
          .isEqualTo(ProviderCallState.SUCCEEDED);
      assertThat(budget.totals().calls()).isEqualTo(1);
    }
  }

  @Test
  void configuredParseRetryUsesANonThinkingProviderRepairAndAggregatesUsage() {
    AtomicInteger providerCalls = new AtomicInteger();
    AtomicReference<ProviderRequest> repairRequest = new AtomicReference<>();
    MockResponder responder =
        request -> {
          if (providerCalls.incrementAndGet() == 1) {
            return response("{\"answer\":\"claim p\",\"extra\":\"invalid\"}");
          }
          repairRequest.set(request);
          return response("{\"answer\":\"claim p\"}");
        };
    CallLedger budget = new CallLedger(3, null, BigDecimal.TEN);
    try (Fixture fixture =
        fixture("run-provider-repair", responder, budget, List.of(), 1)) {
      StructuredCallResult<Answer> result =
          fixture
              .runner()
              .call(
                  "run-provider-repair",
                  "repair-key",
                  "general",
                  bundle("original public task"),
                  fixture.pool().get("agent-a"),
                  "proof",
                  true,
                  "max");

      assertThat(result.value().answer()).isEqualTo("claim p");
      assertThat(result.repaired()).isTrue();
      assertThat(result.usage().totalTokens()).isEqualTo(26);
      assertThat(providerCalls).hasValue(2);
      assertThat(budget.totals().calls()).isEqualTo(2);
      assertThat(fixture.calls().findByRun("run-provider-repair"))
          .hasSize(2)
          .allMatch(call -> call.state() == ProviderCallState.SUCCEEDED);
      assertThat(repairRequest.get().thinkingEnabled()).isFalse();
      assertThat(repairRequest.get().reasoningEffort()).isNull();
      assertThat(repairRequest.get().messages().getLast().content())
          .contains(
              "MALFORMED OUTPUT",
              "VALIDATION ERROR",
              "ORIGINAL TASK CONTEXT",
              "original public task");
    }
  }

  @Test
  void appliesAuthorityPayloadNormalizationBeforeRequestingProviderRepair() {
    AtomicInteger providerCalls = new AtomicInteger();
    String responseText =
        """
        {
          "action": "request_computation",
          "attempt": null,
          "experiment_impact": "execution",
          "experiment_spec": {
            "arguments": {},
            "assumptions": [],
            "broad_search": false,
            "decision_if_confirmed": "continue the route",
            "decision_if_refuted": "abandon the route",
            "domains": {},
            "exact_arithmetic": true,
            "execution_hash": "model-authored-execution-hash",
            "experiment_id": "experiment-local-normalization",
            "max_cases": 100,
            "method": "bounded_greedy_sequence",
            "noncomputational_alternative": "prove the finite reduction directly",
            "purpose": "discover_pattern",
            "reasoning_basis": "inspect a bounded exact prefix",
            "request_hash": "model-authored-request-hash",
            "runtime_fingerprint": {},
            "seed": 20260719,
            "target_claim": "a candidate period appears in the exact prefix",
            "why_computation_is_needed": "falsify the candidate before proving it"
          },
          "reason": "run one bounded exact check"
        }
        """;
    try (Fixture fixture =
        fixture(
            "run-normalization",
            request -> {
              providerCalls.incrementAndGet();
              return response(responseText);
            },
            new CallLedger(3, null, BigDecimal.TEN),
            List.of(),
            1)) {
      PromptBundle<InitialExplorationTurn> bundle =
          new PromptBundle<>(
              "independent_exploration",
              "Return one strict exploration turn.",
              "public route context",
              InitialExplorationTurn.class,
              0.0d,
              4096,
              false,
              null);

      StructuredCallResult<InitialExplorationTurn> result =
          fixture
              .runner()
              .call(
                  "run-normalization",
                  "normalized-turn",
                  "general",
                  bundle,
                  fixture.pool().get("agent-a"),
                  "depth");

      assertThat(providerCalls).hasValue(1);
      assertThat(result.repaired()).isTrue();
      assertThat(result.value().experimentImpact()).isNull();
      assertThat(result.value().experimentSpec().broadSearch()).isTrue();
      assertThat(result.value().experimentSpec().requestHash())
          .hasSize(64)
          .doesNotContain("model-authored");
    }
  }

  @Test
  void explicitStagePolicyCanDisableAgentDefaultThinking() {
    AtomicReference<ProviderRequest> captured = new AtomicReference<>();
    try (Fixture fixture =
        fixture(
            "run-thinking-policy",
            request -> {
              captured.set(request);
              return response("{\"answer\":\"classified\"}");
            },
            new CallLedger(2, null, BigDecimal.TEN),
            List.of())) {
      fixture
          .runner()
          .call(
              "run-thinking-policy",
              "triage",
              "general",
              bundle("public prompt"),
              fixture.pool().get("agent-a"),
              "breadth",
              false,
              null);

      assertThat(captured.get().thinkingEnabled()).isFalse();
      assertThat(captured.get().reasoningEffort()).isNull();
    }
  }

  @Test
  void reasoningOnlyLengthResponseIsClassifiedBeforeJsonRepair() {
    AtomicInteger providerCalls = new AtomicInteger();
    var metadata = JsonNodeFactory.instance.objectNode();
    metadata.putObject("reasoning").put("present", true).put("characters", 2048);
    LLMResponse exhausted =
        new LLMResponse(
            "",
            "mock-model",
            "mock",
            8,
            256,
            25.0d,
            "fixture-request",
            "length",
            true,
            metadata);
    CallLedger budget = new CallLedger(2, null, BigDecimal.TEN);
    try (Fixture fixture =
        fixture(
            "run-reasoning-exhausted",
            request -> {
              providerCalls.incrementAndGet();
              return exhausted;
            },
            budget,
            List.of())) {
      assertThatThrownBy(
              () ->
                  fixture
                      .runner()
                      .call(
                          "run-reasoning-exhausted",
                          "deep-call",
                          "general",
                          bundle("public prompt"),
                          fixture.pool().get("agent-a"),
                          "proof"))
          .isInstanceOf(ReasoningBudgetExhaustedError.class)
          .hasMessageContaining("without returning a public artifact");
      assertThatThrownBy(
              () ->
                  fixture
                      .runner()
                      .call(
                          "run-reasoning-exhausted",
                          "deep-call",
                          "general",
                          bundle("public prompt"),
                          fixture.pool().get("agent-a"),
                          "proof"))
          .isInstanceOf(ReasoningBudgetExhaustedError.class)
          .hasMessageContaining("without returning a public artifact");

      assertThat(providerCalls).hasValue(1);
      assertThat(budget.totals().calls()).isEqualTo(1);
      assertThat(fixture.calls().findByRun("run-reasoning-exhausted").getFirst().state())
          .isEqualTo(ProviderCallState.SUCCEEDED);
    }
  }

  @Test
  void unknownRemoteResultIsAmbiguousAndReservesPossibleDuplicateCost() {
    AtomicInteger calls = new AtomicInteger();
    CallLedger budget = new CallLedger(2, null, BigDecimal.TEN);
    try (Fixture fixture =
        fixture(
            "run-ambiguous",
            request -> {
              calls.incrementAndGet();
              throw ProviderException.network(
                  new java.io.IOException("fixture disconnect"));
            },
            budget,
            List.of())) {
      assertThatThrownBy(
              () ->
                  fixture.runner().call(
                      "run-ambiguous",
                      "ambiguous-key",
                      "general",
                      bundle("public prompt"),
                      fixture.pool().get("agent-a"),
                      "proof"))
          .isInstanceOf(
              io.github.aililuola.mathproofmesh.provider.AgentCallFailure.class);

      ProviderCallRecord stored =
          fixture.calls().findByRun("run-ambiguous").getFirst();
      assertThat(stored.state()).isEqualTo(ProviderCallState.AMBIGUOUS);
      assertThat(stored.possibleDuplicateCostUsd()).isPositive();
      assertThat(stored.ambiguityPayload().path("remote_result_unknown").asBoolean())
          .isTrue();
      assertThat(calls).hasValue(1);
      assertThat(budget.totals().calls()).isEqualTo(1);
    }
  }

  @Test
  void budgetReservationStopsDispatchBeforeProviderCallPlanning() {
    AtomicInteger providerCalls = new AtomicInteger();
    CallLedger budget = new CallLedger(1, null, BigDecimal.TEN);
    try (Fixture fixture =
        fixture(
            "run-budget",
            request -> {
              providerCalls.incrementAndGet();
              return response("{\"answer\":\"ok\"}");
            },
            budget,
            List.of())) {
      fixture.runner().call(
          "run-budget",
          "first",
          "general",
          bundle("first prompt"),
          fixture.pool().get("agent-a"),
          "proof");

      assertThatThrownBy(
              () ->
                  fixture.runner().call(
                      "run-budget",
                      "second",
                      "general",
                      bundle("second prompt"),
                      fixture.pool().get("agent-a"),
                      "proof"))
          .isInstanceOf(BudgetExhaustedError.class);
      assertThat(providerCalls).hasValue(1);
      assertThat(fixture.calls().findByRun("run-budget")).hasSize(1);
    }
  }

  @Test
  void promptCatalogCoversEveryLegacyStageAndRejectsBlindMetadata() {
    assertThat(PromptCatalog.stages())
        .containsKeys(
            "goal_normalization",
            "triage",
            "strategy_generation",
            "independent_exploration",
            "checkpoint_verification",
            "claim_extraction",
            "blind_structural_verification",
            "blind_detailed_verification",
            "synthesis",
            "final_revision",
            "proof_control_blueprint_rewrite");
    PromptFactory factory = new PromptFactory("English");
    PromptBundle<Answer> safe =
        factory.typedStage(
            "blind_detailed_verification",
            Answer.class,
            Map.of("claim", "p"),
            0.0d,
            256,
            false);
    assertThat(safe.user()).contains("SANITIZED CONTEXT", "\"claim\":\"p\"");

    assertThatThrownBy(
            () ->
                factory.typedStage(
                    "blind_detailed_verification",
                    Answer.class,
                    Map.of("agent_id", "author"),
                    0.0d,
                    256,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden metadata");
  }

  private Fixture fixture(
      String runId,
      MockResponder responder,
      CallLedger budget,
      List<String> explicitSecrets) {
    return fixture(runId, responder, budget, explicitSecrets, 0);
  }

  private Fixture fixture(
      String runId,
      MockResponder responder,
      CallLedger budget,
      List<String> explicitSecrets,
      int parseRetries) {
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
            ignored ->
                request -> {
                  throw new AssertionError("mock provider must not use HTTP");
                },
            false);
    AgentPool pool = new AgentPool(config, registry);
    ArtifactStore artifacts =
        new ArtifactStore(temporaryDirectory.resolve(runId), runId);
    InMemoryProviderCallRepository calls =
        new InMemoryProviderCallRepository();
    StructuredAgentRunner runner =
        new StructuredAgentRunner(
            pool,
            artifacts,
            calls,
            budget,
            new PromptRedactor(explicitSecrets),
            new BoundedJsonRepairer(4096),
            null,
            parseRetries,
            4096);
    return new Fixture(pool, artifacts, calls, budget, runner);
  }

  private static PromptBundle<Answer> bundle(String user) {
    return new PromptBundle<>(
        "route_prove",
        "Return one strict answer object.",
        user,
        Answer.class,
        0.0d,
        256,
        false,
        null);
  }

  private static LLMResponse response(String text) {
    return new LLMResponse(
        text,
        "mock-model",
        "mock",
        8,
        5,
        12.5d,
        "fixture-request",
        "stop",
        false,
        JsonNodeFactory.instance.objectNode());
  }

  public record Answer(String answer) {}

  private record Fixture(
      AgentPool pool,
      ArtifactStore artifacts,
      InMemoryProviderCallRepository calls,
      CallLedger budget,
      StructuredAgentRunner runner)
      implements AutoCloseable {
    @Override
    public void close() {
      pool.close();
    }
  }
}
