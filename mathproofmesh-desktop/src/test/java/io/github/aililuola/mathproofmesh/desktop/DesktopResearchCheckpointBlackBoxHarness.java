package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.agent.BoundedJsonRepairer;
import io.github.aililuola.mathproofmesh.agent.CallLedger;
import io.github.aililuola.mathproofmesh.agent.PromptFactory;
import io.github.aililuola.mathproofmesh.agent.PromptRedactor;
import io.github.aililuola.mathproofmesh.agent.StructuredCallResult;
import io.github.aililuola.mathproofmesh.agent.StructuredAgentRunner;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceBinding;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceCall;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceStore;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.api.SolveRequest;
import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.computation.ComputationLimits;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import io.github.aililuola.mathproofmesh.contract.CheckpointedResearchEnvelope;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.Difficulty;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import io.github.aililuola.mathproofmesh.contract.InitialExplorationAction;
import io.github.aililuola.mathproofmesh.contract.InitialExplorationTurn;
import io.github.aililuola.mathproofmesh.contract.ProblemKind;
import io.github.aililuola.mathproofmesh.contract.ProofAttempt;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDisposition;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDispositionAction;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingUpdateBatch;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.contract.TaskRequirement;
import io.github.aililuola.mathproofmesh.contract.TriageResult;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointLedger;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import io.github.aililuola.mathproofmesh.provider.AgentRuntime;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Drives the old production coordinator path without depending on issue-004 APIs. */
final class DesktopResearchCheckpointBlackBoxHarness implements AutoCloseable {
  enum Scenario {
    BUDGET_EXHAUSTION,
    CAMPAIGN_DEFER,
    CAMPAIGN_KEEP_ACTIVE,
    CAMPAIGN_PROPAGATION,
    FINAL_JSON_OMISSION,
    NORMAL,
    TRUNCATED_RESULT,
    UNKNOWN_FINDING_UPDATE,
    MULTI_ROUND
  }

  private static final String MARKER_BEGIN = "<MPM_PUBLIC_RESEARCH_CHECKPOINT_V1>";
  private static final String MARKER_END = "</MPM_PUBLIC_RESEARCH_CHECKPOINT_V1>";

  private final DesktopSolveCoordinator coordinator;
  private final AgentPool pool;
  private final ReasoningTraceStore traces;
  private final ScriptedResponder responder;
  private final InMemoryProviderCallRepository providerCalls;
  private final Path runDirectory;
  private final String runId;
  private final Scenario scenario;
  private final String finding;

  private DesktopResearchCheckpointBlackBoxHarness(
      DesktopSolveCoordinator coordinator,
      AgentPool pool,
      ReasoningTraceStore traces,
      ScriptedResponder responder,
      InMemoryProviderCallRepository providerCalls,
      Path runDirectory,
      String runId,
      Scenario scenario,
      String finding) {
    this.coordinator = coordinator;
    this.pool = pool;
    this.traces = traces;
    this.responder = responder;
    this.providerCalls = providerCalls;
    this.runDirectory = runDirectory;
    this.runId = runId;
    this.scenario = scenario;
    this.finding = finding;
  }

  static DesktopResearchCheckpointBlackBoxHarness open(
      Path runDirectory, String runId, Scenario scenario, String finding) {
    SystemConfig source =
        new DesktopRuntimeLocator(projectRoot(), null).loadProfile("proof-control-active.yaml");
    SystemConfig config = mockConfig(source);
    ReasoningTraceStore traces = new ReasoningTraceStore(runDirectory, runId);
    ScriptedResponder responder = new ScriptedResponder(traces, scenario, finding);
    Map<String, MockResponder> responders = new LinkedHashMap<>();
    config.agents().forEach(agent -> responders.put(agent.id(), responder));
    ProviderClientRegistry providers =
        new ProviderClientRegistry(
            config,
            responders,
            ignored -> request -> {
              throw new AssertionError("research checkpoint black-box test attempted network access");
            },
            false,
            ignored -> null);
    AgentPool pool = new AgentPool(config, providers);
    CallLedger ledger = new CallLedger(20_000L, null, null);
    InMemoryProviderCallRepository providerCalls = new InMemoryProviderCallRepository();
    StructuredAgentRunner runner =
        new StructuredAgentRunner(
            pool,
            new ArtifactStore(runDirectory.resolve("runtime-artifacts"), runId),
            providerCalls,
            ledger,
            new PromptRedactor(List.of()),
            new BoundedJsonRepairer(1_500_000),
            traces);
    DesktopLiveRuntimeFactory.PreparedRuntime runtime =
        new DesktopLiveRuntimeFactory.PreparedRuntime(
            "research-checkpoint-black-box", config, Map.of(), false);
    ComputationBroker computation =
        new ComputationBroker(
            runId,
            ComputationLimits.defaultsEnabled(),
            ComputationHandlerRegistry.javaOnly(),
            new InMemoryComputationCache());
    DesktopSolveCoordinator coordinator =
        new DesktopSolveCoordinator(
            new SolveRequest(
                DesktopNegativeKnowledgeTestHarness.SOURCE,
                runId,
                null,
                "research-checkpoint-black-box"),
            runId,
            runDirectory,
            runtime,
            runner,
            new PromptFactory("en"),
            pool,
            ledger,
            computation,
            false,
            noOpProgress(),
            DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH);
    return new DesktopResearchCheckpointBlackBoxHarness(
        coordinator,
        pool,
        traces,
        responder,
        providerCalls,
        runDirectory,
        runId,
        scenario,
        finding);
  }

  void runProductionExploration() throws Exception {
    invoke("freezeProblem");
    setField(
        coordinator,
        "strategySet",
        new StrategySet("One bounded research route.", List.of(), List.of(strategy())));
    invoke("generateAndAdmitStrategies");
    invoke("ensureInitialRoutes");
    invoke("exploreUnstartedRoutes", new Class<?>[] {boolean.class}, new Object[] {false});
  }

  void freezeProblemOnly() throws Exception {
    invoke("freezeProblem");
  }

  void generateCampaignStrategyAndAdmitRoute() throws Exception {
    if (scenario != Scenario.CAMPAIGN_DEFER
        && scenario != Scenario.CAMPAIGN_PROPAGATION
        && scenario != Scenario.CAMPAIGN_KEEP_ACTIVE) {
      throw new IllegalStateException("campaign strategy generation requires CAMPAIGN_PROPAGATION");
    }
    setField(coordinator, "triage", triage());
    invoke("generateAndAdmitStrategies");
    invoke("ensureInitialRoutes");
  }

  void explorePreparedRoutes() throws Exception {
    invoke("exploreUnstartedRoutes", new Class<?>[] {boolean.class}, new Object[] {false});
  }

  void prepareProductionRoute() throws Exception {
    invoke("freezeProblem");
    setField(
        coordinator,
        "strategySet",
        new StrategySet("One bounded research route.", List.of(), List.of(strategy())));
    invoke("generateAndAdmitStrategies");
    invoke("ensureInitialRoutes");
  }

  StructuredCallResult<InitialExplorationTurn> runCheckpointedRound(int round) throws Exception {
    if (scenario != Scenario.MULTI_ROUND) {
      throw new IllegalStateException("checkpointed rounds require MULTI_ROUND scenario");
    }
    responder.setCurrentRound(round);
    @SuppressWarnings("unchecked")
    List<Object> routes = (List<Object>) rawField(coordinator, "routes");
    Object route = routes.getFirst();
    AgentRuntime author = (AgentRuntime) rawField(route, "author");
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("route_id", "route-1");
    context.put("research_round", round);
    context.put("active_research_findings", researchLedger().activeFindings("route-1"));
    context.put(
        "completed_checkpoint_frames", researchLedger().checkpointsForRoute("route-1"));
    @SuppressWarnings("unchecked")
    StructuredCallResult<InitialExplorationTurn> result =
        (StructuredCallResult<InitialExplorationTurn>)
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
                new Object[] {
                  "research-round-" + round,
                  "independent_exploration",
                  InitialExplorationTurn.class,
                  context,
                  author,
                  "depth",
                  "Research checkpoint round " + round
                });
    return result;
  }

  boolean traceContains(String finding) throws Exception {
    return Files.readString(traces.path(), StandardCharsets.UTF_8).contains(finding);
  }

  boolean downstreamPromptContains(String finding) {
    return responder.downstreamPrompts().stream().anyMatch(prompt -> prompt.contains(finding));
  }

  List<String> downstreamPrompts() {
    return responder.downstreamPrompts();
  }

  int campaignFindingsEmitted() {
    return responder.campaignFindingsEmitted();
  }

  int submittedAttemptCount() {
    try {
      @SuppressWarnings("unchecked")
      List<Object> routes = (List<Object>) rawField(coordinator, "routes");
      return (int)
          routes.stream()
              .filter(
                  route -> {
                    try {
                      return rawField(route, "attempt") != null;
                    } catch (ReflectiveOperationException exception) {
                      throw new IllegalStateException(exception);
                    }
                  })
              .count();
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  int failedRouteCount() {
    try {
      @SuppressWarnings("unchecked")
      List<Object> routes = (List<Object>) rawField(coordinator, "routes");
      return (int)
          routes.stream()
              .filter(
                  route -> {
                    try {
                      return "failed".equals(rawField(route, "status"));
                    } catch (ReflectiveOperationException exception) {
                      throw new IllegalStateException(exception);
                    }
                  })
              .count();
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  ResearchCheckpointLedger researchLedger() {
    try {
      return (ResearchCheckpointLedger) rawField(coordinator, "researchCheckpoints");
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  DesktopSolveCheckpoint checkpointRoundTrip() throws Exception {
    invoke("persist", new Class<?>[] {String.class, boolean.class}, new Object[] {"test", false});
    String json =
        Files.readString(
            runDirectory().resolve("structured/desktop-solve-state.json"), StandardCharsets.UTF_8);
    DesktopSolveCheckpoint checkpoint =
        ContractObjectMapper.read(json, DesktopSolveCheckpoint.class);
    return ContractObjectMapper.read(
        ContractObjectMapper.write(checkpoint), DesktopSolveCheckpoint.class);
  }

  void restore(DesktopSolveCheckpoint checkpoint) throws Exception {
    invoke(
        "restore",
        new Class<?>[] {DesktopSolveCheckpoint.class},
        new Object[] {checkpoint});
  }

  DesktopResearchCheckpointBlackBoxHarness restored(DesktopSolveCheckpoint checkpoint)
      throws Exception {
    DesktopResearchCheckpointBlackBoxHarness restored =
        open(runDirectory, runId, scenario, finding);
    restored.restore(checkpoint);
    return restored;
  }

  String rootHash() {
    try {
      Object root = rawField(coordinator, "rootGoal");
      Method method = root.getClass().getMethod("sourceStatementHash");
      return (String) method.invoke(root);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  String negativeHash() {
    try {
      Object registry = rawField(coordinator, "negativeKnowledgeRegistry");
      return (String) registry.getClass().getMethod("registryHash").invoke(registry);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  String attemptArtifactHash() {
    return hashMethod("attemptArtifacts", "ledgerHash");
  }

  String claimLifecycleHash() {
    try {
      Object proofControl = rawField(coordinator, "proofControl");
      Object claims = proofControl.getClass().getMethod("claims").invoke(proofControl);
      Object snapshot = claims.getClass().getMethod("snapshot").invoke(claims);
      return io.github.aililuola.mathproofmesh.contract.CanonicalJson.stableHash(snapshot);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  DesktopSolveCheckpoint currentCheckpoint() throws Exception {
    return checkpointRoundTrip();
  }

  int providerCallCount() {
    return providerCalls.findByRun(runId).size();
  }

  long duplicateProviderCallIds() {
    List<String> ids =
        providerCalls.findByRun(runId).stream().map(record -> record.callId()).toList();
    return ids.size() - ids.stream().distinct().count();
  }

  long directFactPromotions() throws ReflectiveOperationException {
    Object typedMemory = rawField(coordinator, "typedMemory");
    Object snapshot = typedMemory.getClass().getMethod("snapshot").invoke(typedMemory);
    return ((io.github.aililuola.mathproofmesh.memory.TypedMemorySnapshot) snapshot)
        .tiers()
        .values()
        .stream()
        .filter(tier -> tier == io.github.aililuola.mathproofmesh.contract.MemoryTier.FACT)
        .count();
  }

  int directClaimVerifications() throws ReflectiveOperationException {
    Object proofControl = rawField(coordinator, "proofControl");
    Object claims = proofControl.getClass().getMethod("claims").invoke(proofControl);
    var snapshot =
        (io.github.aililuola.mathproofmesh.proofcontrol.ClaimLifecycleSnapshot)
            claims.getClass().getMethod("snapshot").invoke(claims);
    return snapshot.entries().size();
  }

  int mainGoalClosures() throws ReflectiveOperationException {
    Object graph = rawField(coordinator, "proofGraph");
    var snapshot =
        (io.github.aililuola.mathproofmesh.proofgraph.ProofGraphSnapshot)
            graph.getClass().getMethod("snapshot").invoke(graph);
    return (int)
        snapshot.obligations().values().stream()
            .filter(obligation -> obligation.kind().name().equals("MAIN_GOAL"))
            .filter(obligation -> obligation.status().equals("closed"))
            .count();
  }

  int permanentNegativeRegistrations() {
    try {
      Object registry = rawField(coordinator, "negativeKnowledgeRegistry");
      var snapshot =
          (io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeSnapshot)
              registry.getClass().getMethod("snapshot").invoke(registry);
      return (int) snapshot.records().stream().filter(record -> record.permanent()).count();
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private String hashMethod(String field, String methodName) {
    try {
      Object value = rawField(coordinator, field);
      return (String) value.getClass().getMethod(methodName).invoke(value);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  Path runDirectory() {
    try {
      return (Path) rawField(coordinator, "runDirectory");
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @Override
  public void close() {
    pool.close();
  }

  private Object invoke(String name) throws Exception {
    return invoke(name, new Class<?>[0], new Object[0]);
  }

  private Object invoke(String name, Class<?>[] types, Object[] arguments) throws Exception {
    Method method = DesktopSolveCoordinator.class.getDeclaredMethod(name, types);
    method.setAccessible(true);
    try {
      return method.invoke(coordinator, arguments);
    } catch (InvocationTargetException exception) {
      if (exception.getCause() instanceof Exception cause) {
        throw cause;
      }
      if (exception.getCause() instanceof Error cause) {
        throw cause;
      }
      throw exception;
    }
  }

  private static void setField(Object target, String name, Object value)
      throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object rawField(Object target, String name)
      throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static StrategyCard strategy() {
    return DesktopStrategyMetadataTestSupport.complete(
        new StrategyCard(
        null,
        "Find a reusable local representation before attempting the exact theorem.",
        List.of(),
        List.of(),
        List.of(),
        "Explore exact finite structures and retain every material intermediate finding.",
        List.of(),
        0.2d,
        0.9d,
        List.of("Derive one precise local lemma."),
        "Search a bounded range for a counterexample.",
        "The route is independent and bounded.",
        null,
        null,
        List.of(),
        List.of(),
        "research-checkpoint-black-box-strategy",
            List.of("bounded_research"),
            "Durable intermediate research"));
  }

  private static TriageResult triage() {
    return new TriageResult(
        0.99d,
        Difficulty.HARD,
        List.of("Retain campaign-wide material findings."),
        List.of(),
        ProblemKind.PROOF,
        "decomposition",
        "Generate one legal route from the immutable problem.",
        null,
        1,
        1,
        List.of(TaskRequirement.PROOF));
  }

  private static ExperimentSpec boundedExperiment() {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    arguments.putObject("target").put("lhs", "n - n").put("rhs", "0").put("relation", "ne");
    arguments.putArray("constraints");
    ObjectNode domains = JsonNodeFactory.instance.objectNode();
    domains.putObject("n").put("min", 0).put("max", 3);
    return new ExperimentSpec(
        arguments,
        List.of("n is an integer"),
        false,
        "Continue the route when no counterexample is found.",
        "Abandon the route if a counterexample is found.",
        domains,
        true,
        null,
        "research-checkpoint-bounded-search",
        4,
        ComputationMethod.BOUNDED_INTEGER_SEARCH,
        "Check the four cases by hand.",
        null,
        null,
        ComputationPurpose.FALSIFY_CLAIM,
        "A finite exact search can falsify this local identity.",
        null,
        null,
        JsonNodeFactory.instance.objectNode(),
        17,
        "n equals n for the bounded cases.",
        null,
        "A counterexample would stop the continuation immediately.");
  }

  private static RunExecutionBackend.ProgressSink noOpProgress() {
    return (type, stage, agentId, status, summary, reference) -> {};
  }

  private static SystemConfig mockConfig(SystemConfig source) {
    List<AgentConfig> agents =
        source.agents().stream().map(DesktopResearchCheckpointBlackBoxHarness::mockAgent).toList();
    return new SystemConfig(
        source.systemName(),
        agents,
        source.budget(),
        source.scheduler(),
        source.topology(),
        source.verification(),
        source.continuation(),
        source.deepExplorationPolicy(),
        source.computation().withSandboxedPythonEnabled(false),
        source.runtime());
  }

  private static AgentConfig mockAgent(AgentConfig source) {
    return new AgentConfig(
        source.id(), "mock", "research-checkpoint-model", null, null, null, source.roles(),
        source.specialties(), source.maxConcurrency(), source.requestsPerMinute(),
        source.temperature(), source.maxOutputTokens(), source.providerMaxOutputTokens(),
        source.timeoutSeconds(), source.trustPrior(), source.enabled(), source.pricing(), Map.of(),
        null, source.thinkingEnabled(), source.reasoningEffort(), false, null);
  }

  private static Path projectRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("config/proof-control-active.yaml"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("project root was not found");
  }

  private static final class ScriptedResponder implements MockResponder {
    private final ReasoningTraceStore traces;
    private final Scenario scenario;
    private final String finding;
    private final AtomicInteger explorationCalls = new AtomicInteger();
    private final AtomicInteger campaignFindingsEmitted = new AtomicInteger();
    private final List<String> prompts = new ArrayList<>();
    private final Map<Integer, Integer> roundCalls = new LinkedHashMap<>();
    private int currentRound = -1;

    private ScriptedResponder(ReasoningTraceStore traces, Scenario scenario, String finding) {
      this.traces = traces;
      this.scenario = scenario;
      this.finding = finding;
    }

    @Override
    public synchronized LLMResponse respond(ProviderRequest request) {
      if (scenario == Scenario.CAMPAIGN_DEFER
          || scenario == Scenario.CAMPAIGN_PROPAGATION
          || scenario == Scenario.CAMPAIGN_KEEP_ACTIVE) {
        return campaignPropagationResponse(request);
      }
      if (!"InitialExplorationTurn".equals(request.schemaName())) {
        throw new AssertionError(
            "unexpected research checkpoint black-box schema: " + request.schemaName());
      }
      int call = explorationCalls.incrementAndGet();
      String prompt = request.messages().getLast().content();
      prompts.add(prompt);
      if (scenario == Scenario.MULTI_ROUND) {
        return multiRoundResponse(request, prompt);
      }
      if (call == 1) {
        writeReasoningTrace(request);
        if (scenario == Scenario.UNKNOWN_FINDING_UPDATE) {
          return checkpointedResponse(
              new InitialExplorationTurn(
                  InitialExplorationAction.ABANDON,
                  null,
                  null,
                  null,
                  "The valid structured result remains usable after optional update rejection."),
              new ResearchFindingUpdateBatch(
                  List.of(
                      new ResearchFindingDisposition(
                          "candidate_lemma_identity",
                          ResearchFindingDispositionAction.KEEP_ACTIVE,
                          null,
                          null),
                      new ResearchFindingDisposition(
                          "candidate_lemma_prime_valuation",
                          ResearchFindingDispositionAction.KEEP_ACTIVE,
                          null,
                          null),
                      new ResearchFindingDisposition(
                          "exact_example_1",
                          ResearchFindingDispositionAction.KEEP_ACTIVE,
                          null,
                          null),
                      new ResearchFindingDisposition(
                          "next_micro_obligation_1",
                          ResearchFindingDispositionAction.KEEP_ACTIVE,
                          null,
                          null))));
        }
        if (scenario == Scenario.BUDGET_EXHAUSTION) {
          ObjectNode metadata = JsonNodeFactory.instance.objectNode();
          metadata
              .putObject("reasoning")
              .put("present", true)
              .put("characters", finding.length());
          return new LLMResponse(
              "",
              "research-checkpoint-model",
              "mock",
              10,
              request.maxOutputTokens(),
              1.0d,
              "budget-exhausted",
              "length",
              false,
              metadata);
        }
        if (scenario == Scenario.TRUNCATED_RESULT) {
          return new LLMResponse(
              "{\"action\":\"request_computation\"",
              "research-checkpoint-model",
              "mock",
              10,
              20,
              1.0d,
              "truncated-result",
              "stop",
              false,
              JsonNodeFactory.instance.objectNode());
        }
        return response(requestComputation());
      }
      if (scenario == Scenario.BUDGET_EXHAUSTION && call == 2) {
        return response(requestComputation());
      }
      if (scenario == Scenario.TRUNCATED_RESULT && call == 2) {
        return response(requestComputation());
      }
      if (scenario == Scenario.FINAL_JSON_OMISSION) {
        return response(requestComputation());
      }
      return response(
          new InitialExplorationTurn(
              InitialExplorationAction.ABANDON,
              null,
              null,
              null,
              "The scripted route stops after the next prompt is captured."));
    }

    private LLMResponse campaignPropagationResponse(ProviderRequest request) {
      if ("StrategySet".equals(request.schemaName())) {
        writeReasoningTrace(
            request,
            "strategy_generation",
            List.of(
                Map.of(
                    "kind", "representation_insight",
                    "statement", finding,
                    "rationale", "A global exact reduction produced this reusable direction.",
                    "scope_limitations", List.of("campaign-wide candidate"))));
        campaignFindingsEmitted.incrementAndGet();
        return strategyResponse(
            new StrategySet("One bounded research route.", List.of(), List.of(strategy())));
      }
      if ("InitialExplorationTurn".equals(request.schemaName())) {
        explorationCalls.incrementAndGet();
        String prompt = request.messages().getLast().content();
        prompts.add(prompt);
        if (scenario == Scenario.CAMPAIGN_KEEP_ACTIVE || scenario == Scenario.CAMPAIGN_DEFER) {
          String findingId = campaignFindingId(prompt);
          ResearchFindingDispositionAction action =
              scenario == Scenario.CAMPAIGN_KEEP_ACTIVE
                  ? ResearchFindingDispositionAction.KEEP_ACTIVE
                  : ResearchFindingDispositionAction.DEFER;
          return checkpointedResponse(
              submitAttempt(),
              new ResearchFindingUpdateBatch(
                  List.of(
                      new ResearchFindingDisposition(
                          findingId,
                          action,
                          action == ResearchFindingDispositionAction.KEEP_ACTIVE
                              ? "The route observed this campaign candidate without adopting it."
                              : "The route tried to mutate a campaign-owned candidate.",
                          null))));
        }
        return response(
            new InitialExplorationTurn(
                InitialExplorationAction.ABANDON,
                null,
                null,
                null,
                "The campaign propagation prompt has been captured."));
      }
      throw new AssertionError(
          "unexpected campaign propagation schema: " + request.schemaName());
    }

    private static String campaignFindingId(String prompt) {
      java.util.regex.Matcher matcher =
          java.util.regex.Pattern.compile("research_finding_[0-9a-f]{32}").matcher(prompt);
      if (!matcher.find()) {
        throw new AssertionError("campaign finding id is missing from exploration prompt");
      }
      return matcher.group();
    }

    private static InitialExplorationTurn submitAttempt() {
      ProofAttempt attempt =
          new ProofAttempt(
              "model-explorer",
              "model-attempt",
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of("Checked the exact reduction."),
              "The bounded route supplies a complete candidate proof.",
              null,
              null,
              "model-problem-hash",
              "Apply the exact reduction and discharge the remaining elementary step.",
              List.of(),
              List.of(),
              null,
              null,
              0,
              1,
              0.9d,
              AttemptStatus.COMPLETE,
              "model-strategy",
              List.of(),
              new UsageRecord());
      return new InitialExplorationTurn(
          InitialExplorationAction.SUBMIT_ATTEMPT,
          attempt,
          null,
          null,
          "Submit the auditable candidate without adopting campaign findings.");
    }

    private LLMResponse multiRoundResponse(ProviderRequest request, String prompt) {
      int round = currentRound >= 0 ? currentRound : extractRound(prompt);
      int attempt = roundCalls.merge(round, 1, Integer::sum);
      int mode = Math.floorMod(round, 4);
      if (attempt == 1) {
        writeReasoningTrace(request, roundFindings(round));
        if (mode == 1) {
          ObjectNode metadata = JsonNodeFactory.instance.objectNode();
          metadata.putObject("reasoning").put("present", true).put("characters", 256);
          return new LLMResponse(
              "",
              "research-checkpoint-model",
              "mock",
              10,
              request.maxOutputTokens(),
              1.0d,
              "multi-round-budget-" + round,
              "length",
              false,
              metadata);
        }
        if (mode == 2) {
          return new LLMResponse(
              "{\"action\":\"abandon\"",
              "research-checkpoint-model",
              "mock",
              10,
              20,
              1.0d,
              "multi-round-truncated-" + round,
              "stop",
              false,
              JsonNodeFactory.instance.objectNode());
        }
      }
      return response(
          new InitialExplorationTurn(
              InitialExplorationAction.ABANDON,
              null,
              null,
              null,
              "The public checkpoint is complete; the final artifact intentionally omits it."));
    }

    private void setCurrentRound(int round) {
      currentRound = round;
    }

    private static int extractRound(String prompt) {
      java.util.regex.Matcher matcher =
          java.util.regex.Pattern.compile(
                  "(?:research(?:_| )round|\\\"research_round\\\")\\D+(\\d+)")
              .matcher(prompt);
      if (!matcher.find()) {
        java.util.regex.Matcher repair =
            java.util.regex.Pattern.compile("research-round-(\\d+)").matcher(prompt);
        if (repair.find()) {
          return Integer.parseInt(repair.group(1));
        }
        java.util.regex.Matcher finding =
            java.util.regex.Pattern.compile("\\\"statement\\\":\\\"round (\\d+)")
                .matcher(prompt);
        if (finding.find()) {
          return Integer.parseInt(finding.group(1));
        }
        throw new AssertionError("research round is missing from checkpoint prompt: " + prompt);
      }
      return Integer.parseInt(matcher.group(1));
    }

    private List<Map<String, Object>> roundFindings(int round) {
      return List.of(
          Map.of(
              "kind", "candidate_lemma",
              "statement", "round " + round + " candidate lemma",
              "rationale", "A bounded derivation produced a candidate.",
              "scope_limitations", List.of("route-1")),
          Map.of(
              "kind", "sharp_obstruction",
              "statement", "round " + round + " sharp obstruction",
              "rationale", "The exact case split exposes this obstruction.",
              "scope_limitations", List.of("route-1")),
          Map.of(
              "kind", "next_micro_obligation",
              "statement", "round " + round + " next micro obligation",
              "rationale", "This is the smallest unresolved public step.",
              "scope_limitations", List.of("route-1")));
    }

    private void writeReasoningTrace(ProviderRequest request) {
      writeReasoningTrace(
          request,
          List.of(
              Map.of(
                  "kind", "representation_insight",
                  "statement", finding,
                  "rationale", "The exact finite analysis supports this structure.",
                  "scope_limitations", List.of("current route"))));
    }

    private void writeReasoningTrace(
        ProviderRequest request, List<Map<String, Object>> findings) {
      writeReasoningTrace(request, "independent_exploration", findings);
    }

    private void writeReasoningTrace(
        ProviderRequest request, String stage, List<Map<String, Object>> findings) {
      String frame =
          MARKER_BEGIN
              + "\n"
              + ContractObjectMapper.write(
                  Map.of(
                      "frame_sequence", 0,
                      "summary", "A material public intermediate finding was reached.",
                      "findings", findings))
              + "\n"
              + MARKER_END;
      ReasoningTraceBinding binding =
          ReasoningTraceBinding.current()
              .orElseThrow(() -> new AssertionError("provider call trace binding is missing"));
      ReasoningTraceCall trace =
          traces.beginCall(
              binding.taskId(),
              request.userId(),
              stage,
              binding.providerCallId(),
              true,
              "max");
      trace.append(frame);
      trace.finish(ReasoningTraceCall.Status.COMPLETED);
    }

    private static InitialExplorationTurn requestComputation() {
      return new InitialExplorationTurn(
          InitialExplorationAction.REQUEST_COMPUTATION,
          null,
          null,
          boundedExperiment(),
          "Run one exact bounded check before continuing.");
    }

    private static LLMResponse response(InitialExplorationTurn turn) {
      return new LLMResponse(
          ContractObjectMapper.write(turn),
          "research-checkpoint-model",
          "mock",
          10,
          20,
          1.0d,
          "research-checkpoint-response",
          "stop",
          false,
          JsonNodeFactory.instance.objectNode());
    }

    private static LLMResponse checkpointedResponse(
        InitialExplorationTurn turn, ResearchFindingUpdateBatch findingUpdates) {
      return new LLMResponse(
          ContractObjectMapper.write(
              new CheckpointedResearchEnvelope(
                  null, findingUpdates, ContractObjectMapper.toTree(turn))),
          "research-checkpoint-model",
          "mock",
          10,
          20,
          1.0d,
          "research-checkpoint-envelope-response",
          "stop",
          false,
          JsonNodeFactory.instance.objectNode());
    }

    private static LLMResponse strategyResponse(StrategySet strategies) {
      return new LLMResponse(
          ContractObjectMapper.write(strategies),
          "research-checkpoint-model",
          "mock",
          10,
          20,
          1.0d,
          "campaign-strategy-response",
          "stop",
          false,
          JsonNodeFactory.instance.objectNode());
    }

    private List<String> downstreamPrompts() {
      if (scenario == Scenario.CAMPAIGN_PROPAGATION) {
        return List.copyOf(prompts);
      }
      if (prompts.size() <= 1) {
        return List.of();
      }
      int start = scenario == Scenario.BUDGET_EXHAUSTION ? Math.min(2, prompts.size()) : 1;
      return List.copyOf(prompts.subList(start, prompts.size()));
    }

    private int campaignFindingsEmitted() {
      return campaignFindingsEmitted.get();
    }
  }
}
