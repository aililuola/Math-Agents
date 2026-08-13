package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.agent.BoundedJsonRepairer;
import io.github.aililuola.mathproofmesh.agent.CallLedger;
import io.github.aililuola.mathproofmesh.agent.PromptFactory;
import io.github.aililuola.mathproofmesh.agent.PromptRedactor;
import io.github.aililuola.mathproofmesh.agent.StructuredAgentRunner;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.api.SolveRequest;
import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.computation.ComputationLimits;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.InitialExplorationAction;
import io.github.aililuola.mathproofmesh.contract.InitialExplorationTurn;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofAttempt;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import io.github.aililuola.mathproofmesh.memory.LemmaMemory;
import io.github.aililuola.mathproofmesh.memory.TypedMemory;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.ClaimLifecycleController;
import io.github.aililuola.mathproofmesh.proofcontrol.RootGoalContract;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.AgentRuntime;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class DesktopClaimSalvageTestHarness implements AutoCloseable {
  static final String SOURCE = DesktopNegativeKnowledgeTestHarness.SOURCE;
  static final String PROBLEM_HASH = DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH;

  private final DesktopSolveCoordinator coordinator;
  private final AgentPool pool;
  private final Path runDirectory;
  private final ClaimReviewResponder responder;

  private DesktopClaimSalvageTestHarness(
      DesktopSolveCoordinator coordinator,
      AgentPool pool,
      Path runDirectory,
      ClaimReviewResponder responder) {
    this.coordinator = coordinator;
    this.pool = pool;
    this.runDirectory = runDirectory;
    this.responder = responder;
  }

  static DesktopClaimSalvageTestHarness open(Path runDirectory, String runId) {
    SystemConfig source =
        new DesktopRuntimeLocator(projectRoot(), null).loadProfile("proof-control-active.yaml");
    SystemConfig config = mockConfig(source);
    ClaimReviewResponder responder = new ClaimReviewResponder();
    Map<String, MockResponder> responders = new LinkedHashMap<>();
    config.agents().forEach(agent -> responders.put(agent.id(), responder));
    ProviderClientRegistry providers =
        new ProviderClientRegistry(
            config,
            responders,
            ignored -> request -> {
              throw new AssertionError("claim salvage test attempted network access");
            },
            false,
            ignored -> null);
    AgentPool pool = new AgentPool(config, providers);
    CallLedger ledger = new CallLedger(20_000L, null, null);
    StructuredAgentRunner runner =
        new StructuredAgentRunner(
            pool,
            new ArtifactStore(runDirectory.resolve("runtime-artifacts"), runId),
            new InMemoryProviderCallRepository(),
            ledger,
            new PromptRedactor(List.of()),
            new BoundedJsonRepairer(1_500_000));
    DesktopLiveRuntimeFactory.PreparedRuntime runtime =
        new DesktopLiveRuntimeFactory.PreparedRuntime(
            "claim-salvage-production", config, Map.of(), false);
    ComputationBroker computation =
        new ComputationBroker(
            runId,
            ComputationLimits.defaultsEnabled(),
            ComputationHandlerRegistry.javaOnly(),
            new InMemoryComputationCache());
    DesktopSolveCoordinator coordinator =
        new DesktopSolveCoordinator(
            new SolveRequest(SOURCE, runId, null, "claim-salvage-production"),
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
            PROBLEM_HASH);
    return new DesktopClaimSalvageTestHarness(coordinator, pool, runDirectory, responder);
  }

  void freezeAndCreateRoute() throws Exception {
    invoke("freezeProblem");
    setField(
        coordinator,
        "strategySet",
        new StrategySet("A bounded production route.", List.of(), List.of(validStrategy())));
    invoke("generateAndAdmitStrategies");
    invoke("ensureInitialRoutes");
  }

  void addCounterexampleTargets() {
    for (int round = 0; round < 20; round += 4) {
      String id = counterTarget(round);
      proofGraph()
          .addObligation(
              new ProofObligation(
                  List.of(),
                  0.5d,
                  "",
                  List.of(),
                  List.of(),
                  List.of(),
                  null,
                  ObligationKind.LEMMA,
                  "Counterexample target for round " + round,
                  id,
                  0.7d,
                  PROBLEM_HASH,
                  List.of(),
                  List.of("route-1"),
                  "Counterexample target for round " + round,
                  "open"));
    }
  }

  void runFailedRound(int round) throws Exception {
    installFailedAttempt(round, standardClaims(round));
    invoke("integrateCommittedRoutes");
  }

  void runForcedPassRound(int round, String statement) throws Exception {
    installFailedAttempt(
        round,
        List.of(claim("forced-pass-" + round, statement, List.of())));
    invoke("integrateCommittedRoutes");
  }

  void runSingleLegacyClaimRound(int round, String claimId, String statement) throws Exception {
    installFailedAttempt(round, List.of(claim(claimId, statement, List.of("local_lemma"))));
    invoke("integrateCommittedRoutes");
  }

  List<String> exploreSubsequentRouteAndCaptureVerifiedFacts() throws Exception {
    invoke(
        "addRoute",
        new Class<?>[] {StrategyCard.class, int.class},
        new Object[] {validStrategy("claim-salvage-followup"), 0});
    invoke(
        "exploreUnstartedRoutes",
        new Class<?>[] {boolean.class},
        new Object[] {false});
    return responder.lastExplorationVerifiedFacts();
  }

  private void installFailedAttempt(int round, List<ClaimCard> claims)
      throws ReflectiveOperationException {
    setRound(round);
    Object route = route();
    AgentRuntime author = field(route, "author", AgentRuntime.class);
    StrategyCard strategy = field(route, "strategy", StrategyCard.class);
    ProofAttempt attempt =
        new ProofAttempt(
            author.id(),
            attemptId(round),
            List.of(),
            List.of(),
            List.of("the route theorem is false"),
            List.of(),
            List.of("checked the failing bridge"),
            "FALSE_ROUTE_THEOREM_R" + round,
            null,
            null,
            PROBLEM_HASH,
            "The route contains bounded local results but its main bridge fails.",
            List.of(),
            claims,
            "artifact://failed-route/" + round,
            null,
            round,
            1,
            0.4d,
            AttemptStatus.FAILED,
            strategy.strategyId(),
            List.of("main bridge remains unproved"),
            new UsageRecord());
    setField(route, "attempt", attempt);
    setField(route, "status", "unverified");
    setField(route, "failureReason", "the route theorem does not follow");
    setField(route, "reviewComplete", true);
    setField(route, "checkpointProcessed", true);
    setField(route, "integrated", false);
    setField(route, "delta", null);
    setField(route, "deltaId", null);
    setField(route, "claimReview", null);
  }

  private static List<ClaimCard> standardClaims(int round) {
    List<ClaimCard> claims = new ArrayList<>();
    claims.add(
        claim(
            "correct-local-" + round,
            "CORRECT_LOCAL_R" + round + ": every square of an even integer is divisible by four.",
            List.of("local_lemma")));
    claims.add(
        claim(
            "false-local-" + round,
            "FALSE_LOCAL_R" + round + ": every odd integer is divisible by two.",
            List.of("local_lemma")));
    claims.add(
        claim(
            "unsupported-local-" + round,
            "UNSUPPORTED_LOCAL_R" + round + ": a bridge exists without proof.",
            List.of("local_lemma")));
    if (round % 4 == 0) {
      claims.add(
          claim(
              "counterexample-" + round,
              "EXACT_COUNTEREXAMPLE_R" + round + ": witness n=1 refutes the exact target.",
              List.of(
                  "artifact:counterexample",
                  "counterexample-target:" + counterTarget(round))));
    }
    return List.copyOf(claims);
  }

  private static ClaimCard claim(String id, String statement, List<String> tags) {
    return new ClaimCard(
        List.of(), id, statement, "", "bounded", List.of(), List.of(), List.of(), List.of(),
        List.of(), 0.9d, null, null, null, statement, ClaimStatus.PROPOSED, tags, null);
  }

  DesktopSolveCheckpoint checkpointRoundTrip() throws Exception {
    invoke(
        "persist",
        new Class<?>[] {String.class, boolean.class},
        new Object[] {"claim_salvage_restore", false});
    Path state = runDirectory.resolve("structured").resolve("desktop-solve-state.json");
    DesktopSolveCheckpoint checkpoint =
        ContractObjectMapper.read(Files.readString(state), DesktopSolveCheckpoint.class);
    return ContractObjectMapper.read(
        ContractObjectMapper.write(checkpoint), DesktopSolveCheckpoint.class);
  }

  void restore(DesktopSolveCheckpoint checkpoint) throws Exception {
    invoke("restore", DesktopSolveCheckpoint.class, checkpoint);
  }

  TypedMemory typedMemory() {
    return uncheckedField(coordinator, "typedMemory", TypedMemory.class);
  }

  LemmaMemory lemmaMemory() {
    return uncheckedField(coordinator, "lemmaMemory", LemmaMemory.class);
  }

  ProofGraphStore proofGraph() {
    return uncheckedField(coordinator, "proofGraph", ProofGraphStore.class);
  }

  AttemptArtifactLedger attemptArtifacts() {
    return uncheckedField(coordinator, "attemptArtifacts", AttemptArtifactLedger.class);
  }

  ClaimLifecycleController claimLifecycle() {
    Object facade = rawFieldUnchecked(coordinator, "proofControl");
    try {
      return (ClaimLifecycleController) facade.getClass().getMethod("claims").invoke(facade);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  RootGoalContract rootGoal() {
    return uncheckedField(coordinator, "rootGoal", RootGoalContract.class);
  }

  String exactStatement() {
    return uncheckedField(
            coordinator,
            "frozenProblem",
            io.github.aililuola.mathproofmesh.contract.ProblemContract.class)
        .exactStatement();
  }

  List<ProviderRequest> claimReviewRequests() {
    return responder.requests();
  }

  int reviewCalls(String attemptId) {
    return responder.reviewCalls(attemptId);
  }

  List<String> futureRouteFactStatements() {
    return typedMemory().factsForRoute("future-route").stream().map(message -> message.statement()).toList();
  }

  String permanentNegativeHash() {
    return CanonicalJson.stableHash(
        typedMemory().negativeKnowledgeRegistry().records().stream()
            .filter(record -> record.permanent())
            .toList());
  }

  String negativeRegistryHash() {
    return typedMemory().negativeKnowledgeRegistry().registryHash();
  }

  long permanentNegativeCount() {
    return typedMemory().negativeKnowledgeRegistry().records().stream()
        .filter(record -> record.permanent())
        .count();
  }

  ProductionState productionState() {
    Object route = routeUnchecked();
    @SuppressWarnings("unchecked")
    List<Object> pending = (List<Object>) rawFieldUnchecked(coordinator, "pendingProofTasks");
    return new ProductionState(
        routesUnchecked().size(),
        uncheckedListField(coordinator, "admittedStrategies").size(),
        typedMemory().insights().size(),
        proofGraph().obligations().size(),
        pending.size(),
        typedMemory().facts().size(),
        uncheckedListField(route, "claimIds").size());
  }

  Object routeUnchecked() {
    return routesUnchecked().getFirst();
  }

  static String attemptId(int round) {
    return "failed-attempt-" + round;
  }

  static String counterTarget(int round) {
    return "counter-target-" + round;
  }

  @Override
  public void close() {
    pool.close();
  }

  private Object route() throws ReflectiveOperationException {
    return routes(coordinator).getFirst();
  }

  @SuppressWarnings("unchecked")
  private static List<Object> routes(DesktopSolveCoordinator coordinator)
      throws ReflectiveOperationException {
    return List.copyOf((List<Object>) rawField(coordinator, "routes"));
  }

  private List<Object> routesUnchecked() {
    try {
      return routes(coordinator);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private void setRound(int round) throws ReflectiveOperationException {
    field(coordinator, "roundIndex", AtomicInteger.class).set(round);
  }

  private Object invoke(String name) throws Exception {
    return invoke(name, new Class<?>[0], new Object[0]);
  }

  private Object invoke(String name, Class<?> type, Object argument) throws Exception {
    return invoke(name, new Class<?>[] {type}, new Object[] {argument});
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

  private static Object rawField(Object target, String name)
      throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static Object rawFieldUnchecked(Object target, String name) {
    try {
      return rawField(target, name);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static <T> T field(Object target, String name, Class<T> type)
      throws ReflectiveOperationException {
    return type.cast(rawField(target, name));
  }

  private static <T> T uncheckedField(Object target, String name, Class<T> type) {
    try {
      return field(target, name, type);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Object> uncheckedListField(Object target, String name) {
    return List.copyOf((List<Object>) rawFieldUnchecked(target, name));
  }

  private static void setField(Object target, String name, Object value)
      throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static StrategyCard validStrategy() {
    return validStrategy("claim-salvage-strategy");
  }

  private static StrategyCard validStrategy(String strategyId) {
    return new StrategyCard(
        null,
        "Prove the exact target through independently checked local lemmas.",
        List.of(),
        List.of(),
        List.of(),
        "Use bounded local claims and keep the route theorem separate.",
        List.of(),
        0.2d,
        0.9d,
        List.of("Establish a reusable local identity."),
        "Try to refute every local claim.",
        "The route keeps claim and attempt authority separate.",
        null,
        null,
        List.of(),
        List.of(),
        strategyId,
        List.of("claim_scoped_review"),
        "Claim salvage route");
  }

  private static RunExecutionBackend.ProgressSink noOpProgress() {
    return (type, stage, agentId, status, summary, reference) -> {};
  }

  private static SystemConfig mockConfig(SystemConfig source) {
    List<AgentConfig> agents =
        source.agents().stream().map(DesktopClaimSalvageTestHarness::mockAgent).toList();
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
        source.id(), "mock", "claim-review-model", null, null, null, source.roles(),
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

  record ProductionState(
      int routeCount,
      int admittedStrategyCount,
      int insightCount,
      int obligationCount,
      int pendingTaskCount,
      int factCount,
      int routeClaimCount) {}

  private static final class ClaimReviewResponder implements MockResponder {
    private final List<ProviderRequest> requests = new ArrayList<>();
    private final Map<String, Integer> reviewCalls = new LinkedHashMap<>();
    private final List<List<String>> explorationVerifiedFacts = new ArrayList<>();

    @Override
    public LLMResponse respond(ProviderRequest request) {
      if ("InitialExplorationTurn".equals(request.schemaName())) {
        JsonNode context = sanitizedContext(request);
        List<String> facts = new ArrayList<>();
        context.path("verified_facts").forEach(fact -> facts.add(fact.path("statement").asText()));
        explorationVerifiedFacts.add(List.copyOf(facts));
        InitialExplorationTurn turn =
            new InitialExplorationTurn(
                InitialExplorationAction.ABANDON,
                null,
                null,
                null,
                "verified facts captured for the next route");
        return response(request, ContractObjectMapper.write(turn));
      }
      if (!"ClaimReviewBatch".equals(request.schemaName())) {
        throw new AssertionError("unexpected claim-salvage schema: " + request.schemaName());
      }
      requests.add(request);
      JsonNode context = sanitizedContext(request);
      String routeId = context.path("route_id").asText();
      String attemptId = context.path("attempt_id").asText();
      reviewCalls.merge(attemptId, 1, Integer::sum);
      List<ClaimReviewDecision> decisions = new ArrayList<>();
      for (JsonNode artifact : context.path("candidate_artifacts")) {
        String claimId = artifact.path("claimId").asText();
        String statement = artifact.path("statement").asText();
        String kind = artifact.path("kind").asText();
        if (statement.contains("UNSUPPORTED_LOCAL")) {
          continue;
        }
        VerificationVerdict verdict =
            statement.contains("FALSE_LOCAL")
                ? VerificationVerdict.FAIL
                : VerificationVerdict.PASS;
        decisions.add(
            new ClaimReviewDecision(
                claimId,
                verdict,
                verdict == VerificationVerdict.PASS ? 0.99d : 0.95d,
                List.of(),
                true,
                true,
                true,
                true,
                "COUNTEREXAMPLE".equals(kind),
                List.of(),
                verdict == VerificationVerdict.PASS
                    ? "claim independently checked"
                    : "explicit countermodel found"));
      }
      ClaimReviewBatch batch =
          new ClaimReviewBatch(
              "claim-review-" + attemptId,
              "model-reviewer",
              routeId,
              attemptId,
              decisions,
              "artifact://claim-review/" + attemptId,
              new UsageRecord());
      return response(request, ContractObjectMapper.write(batch));
    }

    private static LLMResponse response(ProviderRequest request, String body) {
      return new LLMResponse(
          body,
          "claim-review-model",
          "mock",
          10,
          20,
          1.0d,
          "claim-review-request",
          "stop",
          false,
          JsonNodeFactory.instance.objectNode());
    }

    List<ProviderRequest> requests() {
      return List.copyOf(requests);
    }

    int reviewCalls(String attemptId) {
      return reviewCalls.getOrDefault(attemptId, 0);
    }

    List<String> lastExplorationVerifiedFacts() {
      return explorationVerifiedFacts.isEmpty()
          ? List.of()
          : explorationVerifiedFacts.getLast();
    }

    private static JsonNode sanitizedContext(ProviderRequest request) {
      String prompt = request.messages().getLast().content();
      String startMarker = "SANITIZED CONTEXT:\n";
      String endMarker = "\n\nOUTPUT LANGUAGE:";
      int start = prompt.indexOf(startMarker);
      if (start < 0) {
        throw new AssertionError("claim review prompt has no sanitized context");
      }
      start += startMarker.length();
      int end = prompt.indexOf(endMarker, start);
      if (end <= start) {
        throw new AssertionError("claim review prompt has no context terminator");
      }
      return ContractObjectMapper.parseTree(prompt.substring(start, end));
    }
  }
}
