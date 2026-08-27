package io.github.aililuola.mathproofmesh.desktop;

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
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.InspirationContextMode;
import io.github.aililuola.mathproofmesh.contract.InspirationMaterialization;
import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import io.github.aililuola.mathproofmesh.contract.InspirationProposal;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.inspiration.InspirationEngine;
import io.github.aililuola.mathproofmesh.memory.GreedyGcdNegativeKnowledgeSeeds;
import io.github.aililuola.mathproofmesh.memory.TypedMemory;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyArchive;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class DesktopNegativeKnowledgeTestHarness implements AutoCloseable {
  static final String SOURCE =
      "Let a_1=2. For every n>=1, define a_{n+1} to be the smallest integer greater "
          + "than a_n such that gcd(a_n,a_{n+1})>1. Prove that there exist positive "
          + "integers T and L such that for every n>=1, a_{n+T}=a_n+L.";
  static final String PROBLEM_HASH = sha256(SOURCE);

  private final DesktopSolveCoordinator coordinator;
  private final AgentPool pool;
  private final Path runDirectory;

  private DesktopNegativeKnowledgeTestHarness(
      DesktopSolveCoordinator coordinator, AgentPool pool, Path runDirectory) {
    this.coordinator = coordinator;
    this.pool = pool;
    this.runDirectory = runDirectory;
  }

  static DesktopNegativeKnowledgeTestHarness open(Path runDirectory, String runId) {
    SystemConfig source =
        new DesktopRuntimeLocator(projectRoot(), null)
            .loadProfile("proof-control-active.yaml");
    SystemConfig config = mockConfig(source);
    Map<String, MockResponder> responders = new LinkedHashMap<>();
    config.agents()
        .forEach(
            agent ->
                responders.put(
                    agent.id(),
                    request -> {
                      throw new AssertionError(
                          "negative-knowledge production test attempted a provider call: "
                              + request.schemaName());
                    }));
    ProviderClientRegistry providers =
        new ProviderClientRegistry(
            config,
            responders,
            ignored ->
                request -> {
                  throw new AssertionError(
                      "negative-knowledge production test attempted network access");
                },
            false,
            ignored -> null);
    AgentPool pool = new AgentPool(config, providers);
    CallLedger ledger = new CallLedger(10_000L, null, null);
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
            "negative-knowledge-production", config, Map.of(), false);
    ComputationBroker computation =
        new ComputationBroker(
            runId,
            ComputationLimits.defaultsEnabled(),
            ComputationHandlerRegistry.javaOnly(),
            new InMemoryComputationCache());
    DesktopSolveCoordinator coordinator =
        new DesktopSolveCoordinator(
            new SolveRequest(SOURCE, runId, null, "negative-knowledge-production"),
            runId,
            runDirectory,
            runtime,
            runner,
            new PromptFactory("zh-CN"),
            pool,
            ledger,
            computation,
            false,
            noOpProgress(),
            PROBLEM_HASH);
    return new DesktopNegativeKnowledgeTestHarness(coordinator, pool, runDirectory);
  }

  void freezeAndCreateValidRoute() throws Exception {
    invoke("freezeProblem");
    setStrategySet(List.of(validStrategy(), invalidStrategy("setup-invalid", alias(0))));
    invoke("generateAndAdmitStrategies");
    invoke("ensureInitialRoutes");
    persist("negative_knowledge_setup");
  }

  void setRound(int round) throws ReflectiveOperationException {
    field("roundIndex", AtomicInteger.class).set(round);
  }

  void attemptStrategyAdmission(int round, String statement) throws Exception {
    setStrategySet(
        List.of(validStrategy(), invalidStrategy("invalid-strategy-" + round, statement)));
    invoke("generateAndAdmitStrategies");
  }

  void attemptObligation(int round, String statement) {
    proofGraph()
        .addObligation(
            new ProofObligation(
                List.of(),
                0.6d,
                "",
                List.of(),
                List.of(),
                List.of(),
                null,
                ObligationKind.LEMMA,
                statement,
                "invalid-obligation-" + round,
                0.8d,
                PROBLEM_HASH,
                List.of(),
                List.of("route-1"),
                statement,
                "open"));
  }

  boolean attemptRevision(int round, String statement) throws Exception {
    Object route = routes().getFirst();
    StrategyCard revision =
        invalidStrategy("invalid-revision-" + round, statement);
    Method method =
        Arrays.stream(DesktopSolveCoordinator.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals("prepareRouteRevision"))
            .findFirst()
            .orElseThrow();
    method.setAccessible(true);
    return (boolean)
        invokeMethod(
            method,
            route,
            revision,
            "REVISE",
            StrategyArchive.RevisionReason.PLAN_FAILURE);
  }

  WideningAttempt attemptWidening(int round, String statement) throws Exception {
    List<StrategyCard> queued = new ArrayList<>(admittedStrategies());
    StrategyCard candidate = invalidStrategy("invalid-widening-" + round, statement);
    queued.add(candidate);
    setField("admittedStrategies", List.copyOf(queued));
    field("nextStrategyIndex", AtomicInteger.class).set(queued.size() - 1);
    ProductionState before = state();

    boolean widened = (boolean) invoke("widenRoutes");
    ProductionState after = state();
    boolean candidateRouted =
        routes().stream()
            .map(DesktopNegativeKnowledgeTestHarness::routeStrategyId)
            .anyMatch(candidate.strategyId()::equals);
    return new WideningAttempt(widened, candidateRouted, before, after);
  }

  void attemptInspiration(int round, String statement) throws Exception {
    String proposalId = "invalid-inspiration-" + round;
    InspirationProposal proposal =
        new InspirationProposal(
            null,
            null,
            null,
            InspirationContextMode.LOCAL,
            1,
            EvidenceType.UNVERIFIED_IDEA,
            0.8d,
            List.of(statement),
            null,
            InspirationMechanism.BRIDGE_LEMMA,
            null,
            0.9d,
            new NoveltySignature(),
            proposalId,
            0,
            "Attempt to reuse a previously rejected load-bearing claim.",
            null,
            null,
            "fake-inspiration-provider",
            statement,
            List.of("route-1"),
            "task-" + round,
            "trigger-" + round);
    InspirationMaterialization materialization =
        new InspirationMaterialization(
            "attached",
            List.of(),
            List.of(proposalId + "-obligation-1"),
            proposalId,
            "candidate passed the model referee but still requires deterministic admission",
            "route-1");
    invoke(
        "materializeInspiration",
        InspirationEngine.ExecutionResult.class,
        new InspirationEngine.ExecutionResult(
            proposal, null, materialization, true, false, false, 0, "test draft"));
  }

  void attemptFactPromotion(int round, String statement) {
    typedMemory().addFact(fact("invalid-fact-" + round, statement, round), "independent-referee", round);
  }

  void addWeakDuplicate(int round, String statement) {
    typedMemory().addNegative(temporaryNegative("weak-duplicate-" + round, statement, round, 2));
  }

  void addVerifiedAndTemporaryFixtures() {
    MessageEnvelope verified =
        temporaryNegative(
            "verified-production-counterexample",
            "A separately replayed target is false.",
            0,
            2,
            EvidenceType.COUNTEREXAMPLE,
            "independent-computation-replay",
            List.of("experiment://verified-production-counterexample"),
            "verified-production-result");
    typedMemory()
        .applyVerifiedCounterexample(
            verified,
            io.github.aililuola.mathproofmesh.memory.VerifiedCounterexampleAuthority
                .independentReplay(
                    true,
                    true,
                    io.github.aililuola.mathproofmesh.computation.ComputationEvidenceGate
                        .EvidenceAuthority.REFUTED,
                    "experiment://verified-production-counterexample",
                    verified.normalizedStatement(),
                    "verified-production-result",
                    List.of()));
    typedMemory()
        .addNegative(
            temporaryNegative(
                "temporary-production-rejection",
                "A short-lived route hypothesis is rejected.",
                0,
                2));
  }

  DesktopSolveCheckpoint checkpointRoundTrip() throws Exception {
    persist("negative_knowledge_restore");
    Path state = runDirectory.resolve("structured").resolve("desktop-solve-state.json");
    DesktopSolveCheckpoint checkpoint =
        ContractObjectMapper.read(Files.readString(state), DesktopSolveCheckpoint.class);
    return ContractObjectMapper.read(
        ContractObjectMapper.write(checkpoint), DesktopSolveCheckpoint.class);
  }

  void restore(DesktopSolveCheckpoint checkpoint) throws Exception {
    invoke("restore", DesktopSolveCheckpoint.class, checkpoint);
  }

  ProductionState state() throws ReflectiveOperationException {
    StrategyArchive archive = field("strategyArchive", StrategyArchive.class);
    Object checkpoints = rawField("checkpoints");
    @SuppressWarnings("unchecked")
    List<Object> pending = (List<Object>) rawField("pendingProofTasks");
    int checkpointAudit =
        ((List<?>) invokePublic(checkpoints, "audit")).size();
    return new ProductionState(
        routes().size(),
        admittedStrategies().size(),
        archive.lineage().size(),
        typedMemory().insights().size(),
        proofGraph().obligations().size(),
        pending.size(),
        checkpointAudit,
        typedMemory().facts().size());
  }

  boolean containsInvalidActiveState(int round) throws ReflectiveOperationException {
    String suffix = Integer.toString(round);
    StrategyArchive archive = field("strategyArchive", StrategyArchive.class);
    return admittedStrategies().stream()
            .anyMatch(strategy -> strategy.strategyId().equals("invalid-strategy-" + suffix))
        || routes().stream()
            .map(DesktopNegativeKnowledgeTestHarness::routeStrategyId)
            .anyMatch(strategyId -> strategyId.equals("invalid-revision-" + suffix))
        || archive.lineage().containsKey("invalid-strategy-" + suffix)
        || archive.lineage().containsKey("invalid-revision-" + suffix)
        || proofGraph().obligations().stream()
            .anyMatch(
                obligation ->
                    obligation.obligationId().equals("invalid-obligation-" + suffix)
                        || obligation.obligationId()
                            .equals("invalid-inspiration-" + suffix + "-obligation-1"))
        || typedMemory().insights().stream()
            .anyMatch(message -> message.messageId().contains("invalid-inspiration-" + suffix))
        || typedMemory().facts().stream()
            .anyMatch(message -> message.messageId().equals("invalid-fact-" + suffix));
  }

  TypedMemory typedMemory() {
    try {
      return field("typedMemory", TypedMemory.class);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  ProofGraphStore proofGraph() {
    try {
      return field("proofGraph", ProofGraphStore.class);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  List<StrategyCard> admittedStrategies() throws ReflectiveOperationException {
    @SuppressWarnings("unchecked")
    List<StrategyCard> values = (List<StrategyCard>) rawField("admittedStrategies");
    return List.copyOf(values);
  }

  @Override
  public void close() {
    pool.close();
  }

  static String alias(int index) {
    List<String> aliases =
        GreedyGcdNegativeKnowledgeSeeds.finitePrimeSupport().trustedAliases();
    return aliases.get(Math.floorMod(index, aliases.size()));
  }

  private void setStrategySet(List<StrategyCard> strategies) throws ReflectiveOperationException {
    setField(
        "strategySet",
        new StrategySet(
            "A sound route keeps the campaign live while the bad route is audited.",
            List.of(),
            strategies));
  }

  private void persist(String stage) throws Exception {
    invoke(
        "persist",
        new Class<?>[] {String.class, boolean.class},
        new Object[] {stage, false});
  }

  private List<?> routes() throws ReflectiveOperationException {
    return List.copyOf((List<?>) rawField("routes"));
  }

  private Object rawField(String name) throws ReflectiveOperationException {
    Field field = DesktopSolveCoordinator.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(coordinator);
  }

  private <T> T field(String name, Class<T> type) throws ReflectiveOperationException {
    return type.cast(rawField(name));
  }

  private void setField(String name, Object value) throws ReflectiveOperationException {
    Field field = DesktopSolveCoordinator.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(coordinator, value);
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
    return invokeMethod(method, arguments);
  }

  private Object invokeMethod(Method method, Object... arguments) throws Exception {
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

  private static Object invokePublic(Object target, String name)
      throws ReflectiveOperationException {
    return target.getClass().getMethod(name).invoke(target);
  }

  private static String routeStrategyId(Object route) {
    try {
      Field field = route.getClass().getDeclaredField("strategy");
      field.setAccessible(true);
      return ((StrategyCard) field.get(route)).strategyId();
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static StrategyCard validStrategy() {
    return DesktopStrategyMetadataTestSupport.complete(
        new StrategyCard(
        null,
        "Prove bounded gaps from multiples of Q=rad(a_1).",
        List.of(),
        List.of(),
        List.of(),
        "Use admissible multiples of Q and a separately proved finite-state bridge.",
        List.of(),
        0.2d,
        0.9d,
        List.of("Establish bounded gaps directly from the recurrence."),
        "Test the bridge on the first exact recurrence states.",
        "The recurrence and the bridge are explicit independent obligations.",
        null,
        null,
        List.of(),
        List.of(),
        "valid-production-strategy",
            List.of("bounded_gaps", "finite_state_bridge"),
            "Valid bounded-gap route"));
  }

  private static StrategyCard invalidStrategy(String id, String statement) {
    return DesktopStrategyMetadataTestSupport.complete(
        new StrategyCard(
        null,
        "Derive the target after establishing a load-bearing shortcut.",
        List.of(),
        List.of(),
        List.of(),
        statement,
        List.of(),
        0.2d,
        0.95d,
        List.of("Use the proposed shortcut to derive the translation law."),
        "Search for a counterexample to the shortcut.",
        "The proposal claims a different route mechanism.",
        null,
        null,
        List.of(),
        List.of(),
        id,
            List.of("candidate_shortcut"),
            "Rejected shortcut " + id));
  }

  private static MessageEnvelope fact(String id, String statement, int round) {
    return new MessageEnvelope(
        List.of("artifact://independent-proof/" + id),
        List.of(),
        statement,
        "",
        null,
        List.of(),
        List.of(),
        EvidenceType.NATURAL_PROOF_AUDITED,
        MemoryTier.FACT,
        id,
        MessageType.VERIFIED_LEMMA,
        1.0d,
        statement,
        PROBLEM_HASH,
        List.of(),
        "artifact://independent-proof/" + id,
        round,
        "1",
        GreedyGcdNegativeKnowledgeSeeds.problemScope(),
        "proof-author",
        RouteRole.PROVER,
        "route-1",
        statement,
        List.of(),
        2,
        List.of(),
        1.0d,
        ClaimStatus.VERIFIED);
  }

  private static MessageEnvelope temporaryNegative(
      String id, String statement, int round, int ttl) {
    return temporaryNegative(
        id,
        statement,
        round,
        ttl,
        EvidenceType.UNVERIFIED_IDEA,
        "model-skeptic",
        List.of(),
        null);
  }

  private static MessageEnvelope temporaryNegative(
      String id,
      String statement,
      int round,
      int ttl,
      EvidenceType evidenceType,
      String sourceAgent,
      List<String> artifacts,
      String rawSourceRef) {
    return new MessageEnvelope(
        artifacts,
        List.of(),
        statement,
        "",
        null,
        List.of(),
        List.of(),
        evidenceType,
        MemoryTier.NEGATIVE,
        id,
        evidenceType == EvidenceType.COUNTEREXAMPLE
            ? MessageType.COUNTEREXAMPLE
            : MessageType.FAILURE_RECORD,
        1.0d,
        statement,
        PROBLEM_HASH,
        List.of(),
        rawSourceRef,
        round,
        "1",
        GreedyGcdNegativeKnowledgeSeeds.problemScope(),
        sourceAgent,
        RouteRole.SKEPTIC,
        "route-1",
        statement,
        List.of(),
        ttl,
        List.of(),
        1.0d,
        ClaimStatus.REJECTED);
  }

  private static RunExecutionBackend.ProgressSink noOpProgress() {
    return (type, stage, agentId, status, summary, reference) -> {};
  }

  private static SystemConfig mockConfig(SystemConfig source) {
    List<AgentConfig> agents =
        source.agents().stream().map(DesktopNegativeKnowledgeTestHarness::mockAgent).toList();
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
        source.id(),
        "mock",
        "scripted-model",
        null,
        null,
        null,
        source.roles(),
        source.specialties(),
        source.maxConcurrency(),
        source.requestsPerMinute(),
        source.temperature(),
        source.maxOutputTokens(),
        source.providerMaxOutputTokens(),
        source.timeoutSeconds(),
        source.trustPrior(),
        source.enabled(),
        source.pricing(),
        Map.of(),
        null,
        source.thinkingEnabled(),
        source.reasoningEffort(),
        false,
        null);
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

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  record ProductionState(
      int routeCount,
      int admittedStrategyCount,
      int lineageCount,
      int insightCount,
      int obligationCount,
      int pendingTaskCount,
      int checkpointBranchCount,
      int factCount) {}

  record WideningAttempt(
      boolean widened,
      boolean candidateRouted,
      ProductionState before,
      ProductionState after) {}
}
