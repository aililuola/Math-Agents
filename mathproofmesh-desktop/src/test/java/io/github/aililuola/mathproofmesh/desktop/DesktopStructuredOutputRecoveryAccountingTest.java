package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.agent.BoundedJsonRepairer;
import io.github.aililuola.mathproofmesh.agent.CallLedger;
import io.github.aililuola.mathproofmesh.agent.PromptFactory;
import io.github.aililuola.mathproofmesh.agent.PromptRedactor;
import io.github.aililuola.mathproofmesh.agent.StructuredAgentRunner;
import io.github.aililuola.mathproofmesh.agent.StructuredCallResult;
import io.github.aililuola.mathproofmesh.agent.StructuredOutputError;
import io.github.aililuola.mathproofmesh.api.SolveRequest;
import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.computation.ComputationLimits;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadBenchmarkPlan;
import io.github.aililuola.mathproofmesh.orchestration.PricingSnapshot;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.AgentRuntime;
import io.github.aililuola.mathproofmesh.provider.HttpTransport;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import io.github.aililuola.mathproofmesh.provider.UsageTotals;
import io.github.aililuola.mathproofmesh.runstate.ProviderCallUsageEvidence;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopStructuredOutputRecoveryAccountingTest {
  private static final String PROBLEM = "Prove that 1 + 1 = 2.";

  @TempDir Path temporaryDirectory;

  @Test
  void benchmarkArtifactRecoveryHasEnoughRoomForOneCompleteStrategyArtifact() throws Exception {
    try (Harness harness = Harness.open(temporaryDirectory.resolve("recovery"), false)) {
      harness.freezeAndTriage();

      StructuredCallResult<StrategySet> recovered = harness.callStrategyStage();

      assertThat(recovered.value().strategies()).hasSize(3);
      assertThat(harness.strategyRequestLimits()).containsExactly(32_000, 32_000);
      assertThat(harness.strategyPrompts().get(1))
          .contains("compact_structured_artifact_only");
      assertThat(harness.providerCalls()).hasSize(3);
    }
  }

  @Test
  void failedRecoveryKeepsSemanticCheckpointStableAndDurablyAccountsEveryPhysicalCall()
      throws Exception {
    Path runDirectory = temporaryDirectory.resolve("failed-recovery");
    try (Harness harness = Harness.open(runDirectory, true)) {
      harness.freezeAndTriage();
      Path state = runDirectory.resolve("structured/desktop-solve-state.json");
      long checkpointCallsBefore =
          ContractObjectMapper.parseTree(Files.readString(state))
              .path("usageTotals")
              .path("calls")
              .asLong();

      Throwable failure = catchThrowable(harness::callStrategyStage);

      assertThat(failure).isInstanceOf(StructuredOutputError.class);
      assertThat(harness.strategyRequestLimits()).containsExactly(32_000, 32_000, 32_000);
      assertThat(harness.providerCalls()).hasSize(4);
      JsonNode checkpoint = ContractObjectMapper.parseTree(Files.readString(state));
      long checkpointCallsAfter = checkpoint.path("usageTotals").path("calls").asLong();
      assertThat(checkpointCallsAfter).isEqualTo(checkpointCallsBefore + 1L);
      assertThat(checkpointCallsAfter).isLessThan(harness.ledgerTotals().calls());
      assertThat(checkpoint.path("currentStage").asText())
          .isEqualTo("research_checkpoint_recovery");
      assertThat(harness.ledgerTotals().calls()).isEqualTo(4L);
      assertThat(harness.durableProviderEvidence()).hasSize(4);
      DesktopOlympiadEvidenceExporter.UsageAccountingAudit usageAudit =
          harness.usageAccountingAudit(checkpoint);
      assertThat(usageAudit.violations()).isZero();
      assertThat(usageAudit.durableStatus()).isEqualTo("DURABLE_EXTENSION");
      assertThat(usageAudit.durableEvidenceCount()).isEqualTo(4);
      assertThat(checkpoint.path("routes")).isEmpty();
      assertThat(checkpoint.path("strategySet").isNull()).isTrue();

      System.out.println("STRUCTURED OUTPUT RECOVERY ACCOUNTING DIAGNOSTIC");
      System.out.println("PRIMARY_OUTPUT_LIMIT=32000");
      System.out.println("ARTIFACT_RECOVERY_OUTPUT_LIMIT=32000");
      System.out.println("JSON_REPAIR_OUTPUT_LIMIT=32000");
      System.out.println("BILLED_PROVIDER_CALLS=" + harness.providerCalls().size());
      System.out.println("SEMANTIC_CHECKPOINT_PROVIDER_CALLS=" + checkpointCallsAfter);
      System.out.println("TERMINAL_LEDGER_PROVIDER_CALLS=" + harness.ledgerTotals().calls());
      System.out.println(
          "DURABLE_PROVIDER_CALL_EVIDENCE=" + usageAudit.durableEvidenceCount());
      System.out.println(
          "POST_CHECKPOINT_PROVIDER_CALLS="
              + (harness.ledgerTotals().calls() - checkpointCallsAfter));
      System.out.println("UNADMITTED_STRATEGY_LEAKS=0");
      System.out.println("RESULT=PASS");
    }
  }

  private static final class Harness implements AutoCloseable {
    private final String runId;
    private final Path runDirectory;
    private final SystemConfig config;
    private final DesktopSolveCoordinator coordinator;
    private final AgentPool pool;
    private final CallLedger ledger;
    private final InMemoryProviderCallRepository calls;
    private final List<Integer> strategyRequestLimits;
    private final List<String> strategyPrompts;

    private Harness(
        String runId,
        Path runDirectory,
        SystemConfig config,
        DesktopSolveCoordinator coordinator,
        AgentPool pool,
        CallLedger ledger,
        InMemoryProviderCallRepository calls,
        List<Integer> strategyRequestLimits,
        List<String> strategyPrompts) {
      this.runId = runId;
      this.runDirectory = runDirectory;
      this.config = config;
      this.coordinator = coordinator;
      this.pool = pool;
      this.ledger = ledger;
      this.calls = calls;
      this.strategyRequestLimits = strategyRequestLimits;
      this.strategyPrompts = strategyPrompts;
    }

    static Harness open(Path runDirectory, boolean failRecovery) {
      String runId = failRecovery ? "structured-recovery-failure" : "structured-recovery-success";
      DesktopRuntimeLocator locator =
          new DesktopRuntimeLocator(DesktopLiveRunExecutionBackendTest.projectRoot(), null);
      SystemConfig base = locator.loadProfile("deepseek-v4-pro.yaml");
      PricingSnapshot pricing = new DesktopBudgetRuntime("structured-recovery-test", base).pricing();
      OlympiadBenchmarkPlan.RunSpec smoke =
          OlympiadBenchmarkPlan.fullSchedule().stream()
              .filter(spec -> spec.identity().equals("P01/T1"))
              .findFirst()
              .orElseThrow();
      SystemConfig config =
          DesktopLiveRunExecutionBackendTest.mockConfig(
              DesktopOlympiadProductionExecutor.benchmarkConfig(base, smoke, pricing));
      List<Integer> strategyLimits = new ArrayList<>();
      List<String> strategyPrompts = new ArrayList<>();
      AtomicInteger strategyCalls = new AtomicInteger();
      MockResponder responder =
          request ->
              response(
                  request,
                  failRecovery,
                  strategyCalls.incrementAndGet(),
                  strategyLimits,
                  strategyPrompts);
      Map<String, MockResponder> responders = new LinkedHashMap<>();
      config.agents().forEach(agent -> responders.put(agent.id(), responder));
      HttpTransport noNetwork = request -> {
        throw new AssertionError("structured-output recovery test attempted network access");
      };
      ProviderClientRegistry providers =
          new ProviderClientRegistry(
              config, responders, ignored -> noNetwork, false, ignored -> null);
      AgentPool pool = new AgentPool(config, providers);
      CallLedger ledger =
          new CallLedger(
              config.budget().maxTotalCalls(),
              config.budget().maxTotalTokens().longValue(),
              BigDecimal.valueOf(config.budget().maxCostUsd()));
      InMemoryProviderCallRepository calls = new InMemoryProviderCallRepository();
      StructuredAgentRunner runner =
          new StructuredAgentRunner(
              pool,
              new ArtifactStore(runDirectory.resolve("runtime-artifacts"), runId),
              calls,
              ledger,
              new PromptRedactor(List.of()),
              new BoundedJsonRepairer(1_500_000),
              null,
              config.runtime().parseRetries(),
              config.runtime().jsonRepairMaxOutputTokens());
      DesktopLiveRuntimeFactory.PreparedRuntime runtime =
          new DesktopLiveRuntimeFactory.PreparedRuntime(
              "structured-recovery", config, Map.of(), false);
      ComputationBroker computation =
          new ComputationBroker(
              runId,
              ComputationLimits.defaultsEnabled(),
              ComputationHandlerRegistry.javaOnly(),
              new InMemoryComputationCache());
      DesktopSolveCoordinator coordinator =
          new DesktopSolveCoordinator(
              new SolveRequest(PROBLEM, runId, null, "structured-recovery"),
              runId,
              runDirectory,
              runtime,
              runner,
              new PromptFactory("en"),
              pool,
              ledger,
              computation,
              false,
              (type, stage, agentId, status, summary, reference) -> {},
              sha256(PROBLEM));
      return new Harness(
          runId,
          runDirectory,
          config,
          coordinator,
          pool,
          ledger,
          calls,
          strategyLimits,
          strategyPrompts);
    }

    void freezeAndTriage() throws Exception {
      invoke("freezeProblem", new Class<?>[0]);
      invoke("runTriage", new Class<?>[0]);
    }

    @SuppressWarnings("unchecked")
    StructuredCallResult<StrategySet> callStrategyStage() throws Exception {
      AgentRuntime planner =
          pool.select("planner", Set.of(), List.of("problem_decomposition"), null, true);
      return (StructuredCallResult<StrategySet>)
          invoke(
              "callStage",
              new Class<?>[] {
                String.class,
                String.class,
                Class.class,
                Map.class,
                AgentRuntime.class,
                String.class,
                String.class
              },
              "strategy-generation",
              "strategy_generation",
              StrategySet.class,
              Map.of(
                  "immutable_problem", PROBLEM,
                  "problem_hash", sha256(PROBLEM),
                  "strategies_requested", 3),
              planner,
              "breadth",
              "Generating independent proof strategies");
    }

    List<Integer> strategyRequestLimits() {
      return List.copyOf(strategyRequestLimits);
    }

    List<String> strategyPrompts() {
      return List.copyOf(strategyPrompts);
    }

    List<?> providerCalls() {
      return calls.findByRun(runId);
    }

    UsageTotals ledgerTotals() {
      return ledger.totals();
    }

    List<ProviderCallUsageEvidence> durableProviderEvidence() throws Exception {
      return ProviderUsageRecovery.recoverEvidence(runDirectory, config);
    }

    DesktopOlympiadEvidenceExporter.UsageAccountingAudit usageAccountingAudit(
        JsonNode checkpoint) {
      return DesktopOlympiadEvidenceExporter.usageAccountingAudit(
          runDirectory,
          checkpoint,
          DesktopLiveRunExecutionBackend.executionUsage(ledger.totals()));
    }

    private Object invoke(String name, Class<?>[] parameterTypes, Object... arguments)
        throws Exception {
      Method method = DesktopSolveCoordinator.class.getDeclaredMethod(name, parameterTypes);
      method.setAccessible(true);
      try {
        return method.invoke(coordinator, arguments);
      } catch (InvocationTargetException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof Exception checked) {
          throw checked;
        }
        if (cause instanceof Error error) {
          throw error;
        }
        throw exception;
      }
    }

    @Override
    public void close() {
      pool.close();
    }
  }

  private static LLMResponse response(
      ProviderRequest request,
      boolean failRecovery,
      int globalCall,
      List<Integer> strategyRequestLimits,
      List<String> strategyPrompts) {
    if (!"StrategySet".equals(request.schemaName())) {
      return DesktopLiveRunExecutionBackendTest.response(
          request, DesktopLiveRunExecutionBackendTest.Mode.COMPLETED);
    }
    strategyRequestLimits.add(request.maxOutputTokens());
    strategyPrompts.add(request.messages().getLast().content());
    int strategyCall = strategyRequestLimits.size();
    if (strategyCall == 1) {
      var metadata = JsonNodeFactory.instance.objectNode();
      metadata.putObject("reasoning").put("present", true).put("characters", 8_000);
      return new LLMResponse(
          "",
          "scripted-model",
          "mock",
          1_000,
          request.maxOutputTokens(),
          1.0d,
          "structured-request-" + globalCall,
          "length",
          false,
          metadata);
    }
    if (!failRecovery && strategyCall == 2) {
      return DesktopLiveRunExecutionBackendTest.response(
          request, DesktopLiveRunExecutionBackendTest.Mode.COMPLETED);
    }
    return new LLMResponse(
        "{",
        "scripted-model",
        "mock",
        1_000,
        request.maxOutputTokens(),
        1.0d,
        "structured-request-" + globalCall,
        "length",
        false,
        JsonNodeFactory.instance.objectNode());
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
