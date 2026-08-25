package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.api.SolveRequest;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import io.github.aililuola.mathproofmesh.provider.HttpTransport;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopSelfContainedProofToolGateTest {
  private static final String PROFILE = "self-contained-tool-gate";
  private static final String RUN_ID = "self-contained-tool-gate-run";

  @TempDir Path temporaryDirectory;

  @Test
  void unusedStrategyPlanningCheckCannotRejectACompleteSelfContainedProof() throws Exception {
    Path runDirectory = temporaryDirectory.resolve("run");
    List<ProviderRequest> requests = new CopyOnWriteArrayList<>();

    RunExecutionBackend.RunExecutionResult result =
        backend(requests)
            .execute(
                new SolveRequest("Prove that 1 + 1 = 2.", RUN_ID, null, PROFILE),
                RUN_ID,
                "trace-self-contained-tool-gate",
                runDirectory,
                (type, stage, agentId, status, summary, reference) -> {});

    long toolReplayCalls =
        requests.stream().filter(request -> "ToolAuditReport".equals(request.schemaName())).count();
    JsonNode state =
        ContractObjectMapper.parseTree(
            Files.readString(runDirectory.resolve("structured/desktop-solve-state.json")));
    List<JsonNode> routes = new ArrayList<>();
    state.path("routes").forEach(routes::add);

    assertThat(result.status()).isEqualTo("completed");
    assertThat(routes).hasSize(3);
    assertThat(routes)
        .allSatisfy(
            route -> {
              assertThat(route.path("status").asText()).isEqualTo("verified");
              assertThat(route.path("toolAudit").isNull()).isTrue();
              assertThat(route.path("teamResult").path("toolReplayPassed").asBoolean()).isTrue();
            });
    assertThat(toolReplayCalls).isZero();

    System.out.println("SELF-CONTAINED PROOF TOOL-GATE DIAGNOSTIC");
    System.out.println("STRATEGY_PLANNING_CHECKS=" + routes.size());
    System.out.println("ATTEMPT_TOOL_DEPENDENCIES=0");
    System.out.println("TOOL_REPLAY_CALLS=" + toolReplayCalls);
    System.out.println("VERIFIED_ROUTES=" + routes.size());
    System.out.println("FALSE_TOOL_GATE_REJECTIONS=0");
    System.out.println("RESULT=PASS");
  }

  private DesktopLiveRunExecutionBackend backend(List<ProviderRequest> requests) {
    Path project = DesktopLiveRunExecutionBackendTest.projectRoot();
    DesktopRuntimeLocator locator = new DesktopRuntimeLocator(project, null);
    SystemConfig config =
        DesktopLiveRunExecutionBackendTest.mockConfig(
            locator.loadProfile("proof-control-active.yaml"));
    DesktopLiveRuntimeFactory.PreparedRuntime prepared =
        new DesktopLiveRuntimeFactory.PreparedRuntime(PROFILE, config, Map.of(), false);
    DesktopLiveRuntimeFactory runtimes = mock(DesktopLiveRuntimeFactory.class);
    when(runtimes.prepare(eq(PROFILE), any(DesktopSettings.class))).thenReturn(prepared);
    when(runtimes.openProviders(prepared)).thenAnswer(ignored -> providers(config, requests));

    DesktopPaths paths = DesktopTestSupport.paths(temporaryDirectory.resolve("desktop-data"));
    SettingsStore settings = new SettingsStore(paths.settingsFile(), DesktopTestSupport.MAPPER);
    return new DesktopLiveRunExecutionBackend(
        paths, settings, runtimes, locator, new DockerSandboxPreflight());
  }

  private static ProviderClientRegistry providers(
      SystemConfig config, List<ProviderRequest> requests) {
    AtomicLong requestIds = new AtomicLong();
    MockResponder responder =
        request -> {
          requests.add(request);
          LLMResponse source =
              DesktopLiveRunExecutionBackendTest.response(
                  request, DesktopLiveRunExecutionBackendTest.Mode.COMPLETED);
          String text = source.text();
          if ("StrategySet".equals(request.schemaName())) {
            StrategySet strategies = ContractObjectMapper.read(text, StrategySet.class);
            text = ContractObjectMapper.write(withPlanningChecks(strategies));
          }
          return new LLMResponse(
              text,
              source.model(),
              source.provider(),
              source.inputTokens(),
              source.outputTokens(),
              source.latencyMs(),
              source.requestId() + '-' + requestIds.incrementAndGet(),
              source.finishReason(),
              source.streaming(),
              source.metadata());
        };
    Map<String, MockResponder> responders = new LinkedHashMap<>();
    config.agents().forEach(agent -> responders.put(agent.id(), responder));
    HttpTransport noNetwork = request -> {
      throw new AssertionError("self-contained proof tool-gate test attempted network access");
    };
    return new ProviderClientRegistry(
        config, responders, ignored -> noNetwork, false, ignored -> null);
  }

  private static StrategySet withPlanningChecks(StrategySet source) {
    return new StrategySet(
        source.coverageNotes(),
        source.omittedDirections(),
        source.strategies().stream()
            .map(DesktopSelfContainedProofToolGateTest::withPlanningCheck)
            .toList());
  }

  private static StrategyCard withPlanningCheck(StrategyCard source) {
    return new StrategyCard(
        source.assignedAgentId(),
        source.bottleneck(),
        List.of(planningCheck()),
        source.calculationEvidenceRefs(),
        source.computationHints(),
        source.coreIdea(),
        source.criticalClaims(),
        source.estimatedCost(),
        source.estimatedSuccess(),
        source.expectedLemmas(),
        source.falsificationTest(),
        source.independenceBasis(),
        source.inspirationProposalId(),
        source.keyOriginalStep(),
        source.parentStrategyIds(),
        source.prerequisites(),
        source.strategyId(),
        source.tags(),
        source.title(),
        source.mechanismOperations(),
        source.criticalClaimContextBindings());
  }

  private static ToolRequest planningCheck() {
    return new ToolRequest(
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        "bounded_integer_search",
        10,
        "Optionally falsify the planning reduction before attempting a symbolic proof.",
        "strategy-planning-check");
  }
}
