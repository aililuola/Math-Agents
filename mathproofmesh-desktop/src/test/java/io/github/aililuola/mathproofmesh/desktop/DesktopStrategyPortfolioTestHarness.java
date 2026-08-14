package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.agent.BoundedJsonRepairer;
import io.github.aililuola.mathproofmesh.agent.CallLedger;
import io.github.aililuola.mathproofmesh.agent.PromptFactory;
import io.github.aililuola.mathproofmesh.agent.PromptRedactor;
import io.github.aililuola.mathproofmesh.agent.StructuredAgentRunner;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.api.SolveRequest;
import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationEvidenceGate;
import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.computation.ComputationLimits;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.Difficulty;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.ProblemKind;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.contract.TaskRequirement;
import io.github.aililuola.mathproofmesh.contract.TriageResult;
import io.github.aililuola.mathproofmesh.memory.LemmaMemory;
import io.github.aililuola.mathproofmesh.memory.TypedMemory;
import io.github.aililuola.mathproofmesh.memory.VerifiedCounterexampleAuthority;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlFacade;
import io.github.aililuola.mathproofmesh.proofcontrol.RootGoalContract;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyArchive;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphConvergenceMonitor;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointLedger;
import io.github.aililuola.mathproofmesh.strategydiversity.PortfolioReplenishmentLedger;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCandidateLedger;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyMechanismRegistry;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioRegistry;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPreflightRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class DesktopStrategyPortfolioTestHarness implements AutoCloseable {
  static final String SOURCE =
      "Prove that every finite tree with at least two vertices has at least two leaves.";
  static final String PROBLEM_HASH = sha256(SOURCE);

  private final DesktopSolveCoordinator coordinator;
  private final AgentPool pool;
  private final Path runDirectory;
  private final RecordingResponder responder;

  private DesktopStrategyPortfolioTestHarness(
      DesktopSolveCoordinator coordinator,
      AgentPool pool,
      Path runDirectory,
      RecordingResponder responder) {
    this.coordinator = coordinator;
    this.pool = pool;
    this.runDirectory = runDirectory;
    this.responder = responder;
  }

  static DesktopStrategyPortfolioTestHarness open(Path directory, String runId) {
    return open(directory, runId, List.of());
  }

  static DesktopStrategyPortfolioTestHarness open(
      Path directory, String runId, List<StrategySet> providerStrategySets) {
    return open(directory, runId, SOURCE, providerStrategySets);
  }

  static DesktopStrategyPortfolioTestHarness open(
      Path directory, String runId, String sourceStatement, List<StrategySet> providerStrategySets) {
    SystemConfig source =
        new DesktopRuntimeLocator(projectRoot(), null)
            .loadProfile("proof-control-active.yaml");
    SystemConfig config = mockConfig(source);
    RecordingResponder responder = new RecordingResponder(providerStrategySets);
    Map<String, MockResponder> responders = new LinkedHashMap<>();
    config.agents().forEach(agent -> responders.put(agent.id(), responder));
    ProviderClientRegistry providers =
        new ProviderClientRegistry(
            config,
            responders,
            ignored ->
                request -> {
                  throw new AssertionError("strategy portfolio test attempted network access");
                },
            false,
            ignored -> null);
    AgentPool pool = new AgentPool(config, providers);
    CallLedger ledger = new CallLedger(20_000L, null, null);
    StructuredAgentRunner runner =
        new StructuredAgentRunner(
            pool,
            new ArtifactStore(directory.resolve("runtime-artifacts"), runId),
            new InMemoryProviderCallRepository(),
            ledger,
            new PromptRedactor(List.of()),
            new BoundedJsonRepairer(1_500_000));
    DesktopLiveRuntimeFactory.PreparedRuntime runtime =
        new DesktopLiveRuntimeFactory.PreparedRuntime(
            "strategy-portfolio-production", config, Map.of(), false);
    DesktopSolveCoordinator coordinator =
        new DesktopSolveCoordinator(
            new SolveRequest(sourceStatement, runId, null, "strategy-portfolio-production"),
            runId,
            directory,
            runtime,
            runner,
            new PromptFactory("en-US"),
            pool,
            ledger,
            new ComputationBroker(
                runId,
                ComputationLimits.defaultsEnabled(),
                ComputationHandlerRegistry.javaOnly(),
                new InMemoryComputationCache()),
            false,
            noOpProgress(),
            sha256(sourceStatement));
    return new DesktopStrategyPortfolioTestHarness(coordinator, pool, directory, responder);
  }

  void freeze() throws Exception {
    invoke("freezeProblem");
    setField("triage", deterministicTriage());
  }

  private static TriageResult deterministicTriage() {
    return new TriageResult(
        0.9d,
        Difficulty.HARD,
        List.of("Preserve the immutable root goal."),
        List.of(),
        ProblemKind.PROOF,
        "decomposition",
        "Generate independent, evidence-grounded proof mechanisms.",
        null,
        4,
        3,
        List.of(TaskRequirement.PROOF));
  }

  void setStrategies(List<StrategyCard> strategies) throws ReflectiveOperationException {
    setField(
        "strategySet",
        new StrategySet("Finite-structure mechanism candidates.", List.of(), strategies));
  }

  void generateAndAdmit() throws Exception {
    invoke("generateAndAdmitStrategies");
  }

  boolean widen() throws Exception {
    return (boolean) invoke("widenRoutes");
  }

  void queueWideningCandidate(StrategyCard candidate) throws Exception {
    StrategySet staged =
        new StrategySet("Incremental widening candidate.", List.of(), List.of(candidate));
    Object preparation =
        invoke(
            "prepareStrategyPortfolio",
            new Class<?>[] {String.class, StrategySet.class},
            "widening-test-" + candidate.strategyId(),
            staged);
    Method preparedAccessor = preparation.getClass().getDeclaredMethod("prepared");
    preparedAccessor.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> prepared = (Map<String, Object>) preparedAccessor.invoke(preparation);
    Object item = prepared.get(candidate.strategyId());
    if (item == null) {
      throw new IllegalStateException("widening candidate did not pass candidate preparation");
    }
    Method blueprintAccessor = item.getClass().getDeclaredMethod("blueprint");
    Method goalLinkAccessor = item.getClass().getDeclaredMethod("goalLink");
    blueprintAccessor.setAccessible(true);
    goalLinkAccessor.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> blueprints = (Map<String, Object>) rawField("strategyBlueprints");
    @SuppressWarnings("unchecked")
    Map<String, Object> links = (Map<String, Object>) rawField("goalLinks");
    blueprints.put(candidate.strategyId(), blueprintAccessor.invoke(item));
    links.put(candidate.strategyId(), goalLinkAccessor.invoke(item));
    List<StrategyCard> queued = new ArrayList<>(admittedStrategies());
    queued.add(candidate);
    setField("admittedStrategies", List.copyOf(queued));
    field("nextStrategyIndex", AtomicInteger.class).set(queued.size() - 1);
  }

  void setRound(int round) throws ReflectiveOperationException {
    field("roundIndex", AtomicInteger.class).set(round);
  }

  void setFailurePoint(StrategyPortfolioFailurePoint point) {
    coordinator.setStrategyPortfolioFailurePointForTest(point);
  }

  void setHardCrashPoint(StrategyPortfolioFailurePoint point) {
    coordinator.setStrategyPortfolioHardCrashPointForTest(point);
  }

  void registerVerifiedCounterexample(String statement, String id) {
    String artifact = "experiment://finite-graph/" + id;
    String raw = "artifact://finite-graph/" + id;
    MessageEnvelope envelope =
        new MessageEnvelope(
            List.of(artifact),
            List.of(),
            "A replayed finite structure refutes the exact claim.",
            "",
            null,
            List.of(),
            List.of(),
            EvidenceType.COUNTEREXAMPLE,
            MemoryTier.NEGATIVE,
            id,
            MessageType.COUNTEREXAMPLE,
            1.0d,
            statement,
            PROBLEM_HASH,
            List.of(),
            raw,
            0,
            "1",
            List.of(),
            "independent-computation-replay",
            RouteRole.SKEPTIC,
            "route-counterexample",
            statement,
            List.of(),
            2,
            List.of(),
            1.0d,
            ClaimStatus.REJECTED);
    typedMemory()
        .applyVerifiedCounterexample(
            envelope,
            VerifiedCounterexampleAuthority.independentReplay(
                true,
                true,
                ComputationEvidenceGate.EvidenceAuthority.REFUTED,
                artifact,
                statement,
                raw,
                List.of()));
  }

  void registerVerifiedClaim(String statement, String id) {
    ClaimCard claim =
        new ClaimCard(
            List.of(),
            id,
            statement,
            "",
            "low",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            0.95d,
            "trusted-referee",
            "attempt-verified",
            "delta-verified",
            statement,
            ClaimStatus.VERIFIED,
            List.of("verified-fact"),
            1.0d);
    lemmaMemory().addMany(List.of(claim));
  }

  DesktopSolveCheckpoint checkpointRoundTrip() throws Exception {
    invoke("persist", new Class<?>[] {String.class, boolean.class}, "strategy_portfolio_test", false);
    Path state = runDirectory.resolve("structured").resolve("desktop-solve-state.json");
    DesktopSolveCheckpoint checkpoint =
        ContractObjectMapper.read(Files.readString(state), DesktopSolveCheckpoint.class);
    return ContractObjectMapper.read(
        ContractObjectMapper.write(checkpoint), DesktopSolveCheckpoint.class);
  }

  DesktopSolveCheckpoint readPersistedCheckpoint() throws Exception {
    Path state = runDirectory.resolve("structured").resolve("desktop-solve-state.json");
    DesktopSolveCheckpoint checkpoint =
        ContractObjectMapper.read(Files.readString(state), DesktopSolveCheckpoint.class);
    return ContractObjectMapper.read(
        ContractObjectMapper.write(checkpoint), DesktopSolveCheckpoint.class);
  }

  void restore(DesktopSolveCheckpoint checkpoint) throws Exception {
    invoke("restore", new Class<?>[] {DesktopSolveCheckpoint.class}, checkpoint);
  }

  ProductionState state() throws ReflectiveOperationException {
    @SuppressWarnings("unchecked")
    List<StrategyCard> admitted = (List<StrategyCard>) rawField("admittedStrategies");
    @SuppressWarnings("unchecked")
    List<Object> routes = (List<Object>) rawField("routes");
    @SuppressWarnings("unchecked")
    Map<String, Object> blueprints = (Map<String, Object>) rawField("strategyBlueprints");
    @SuppressWarnings("unchecked")
    Map<String, Object> goalLinks = (Map<String, Object>) rawField("goalLinks");
    @SuppressWarnings("unchecked")
    List<Object> pending = (List<Object>) rawField("pendingProofTasks");
    StrategyArchive archive = field("strategyArchive", StrategyArchive.class);
    return new ProductionState(
        field("strategyCandidates", StrategyCandidateLedger.class).ledgerHash(),
        field("strategyMechanisms", StrategyMechanismRegistry.class).registryHash(),
        field("strategyPreflights", StrategyPreflightRegistry.class).registryHash(),
        field("strategyPortfolios", StrategyPortfolioRegistry.class).registryHash(),
        field("portfolioReplenishments", PortfolioReplenishmentLedger.class).ledgerHash(),
        admitted.stream().map(StrategyCard::strategyId).toList(),
        routes.stream().map(DesktopStrategyPortfolioTestHarness::routeId).toList(),
        archive.lineage().size(),
        blueprints.size(),
        goalLinks.size(),
        proofGraph().obligations().size(),
        pending.size(),
        rootGoal().sourceStatementHash(),
        typedMemory().negativeKnowledgeRegistry().registryHash());
  }

  ProtectedHashes protectedHashes() throws ReflectiveOperationException {
    ProofControlFacade proofControl = field("proofControl", ProofControlFacade.class);
    return new ProtectedHashes(
        rootGoal().sourceStatementHash(),
        typedMemory().negativeKnowledgeRegistry().registryHash(),
        field("attemptArtifacts", AttemptArtifactLedger.class).ledgerHash(),
        CanonicalJson.stableHash(proofControl.claims().snapshot()),
        field("researchCheckpoints", ResearchCheckpointLedger.class).ledgerHash(),
        proofGraph().canonicalizationHash(),
        field("proofGraphConvergence", ProofGraphConvergenceMonitor.class).stableHash(),
        proofControl.semanticPivots().ledger().stableHash());
  }

  int providerStrategyCalls() {
    return responder.strategyCalls();
  }

  long replenishmentProviderCalls() {
    var marker =
        java.util.regex.Pattern.compile(
            "\\\"generation_mode\\\"\\s*:\\s*\\\"portfolio_gap_replenishment\\\"");
    return responder.requests().stream()
        .filter(
            request ->
                request.messages().stream()
                    .anyMatch(message -> marker.matcher(message.content()).find()))
        .count();
  }

  List<ProviderRequest> providerRequests() {
    return responder.requests();
  }

  List<StrategyCard> admittedStrategies() throws ReflectiveOperationException {
    @SuppressWarnings("unchecked")
    List<StrategyCard> admitted = (List<StrategyCard>) rawField("admittedStrategies");
    return List.copyOf(admitted);
  }

  List<String> routeStrategyIds() throws ReflectiveOperationException {
    @SuppressWarnings("unchecked")
    List<Object> routes = (List<Object>) rawField("routes");
    return routes.stream().map(DesktopStrategyPortfolioTestHarness::routeStrategyId).toList();
  }

  StrategyCandidateLedger candidates() throws ReflectiveOperationException {
    return field("strategyCandidates", StrategyCandidateLedger.class);
  }

  StrategyMechanismRegistry mechanisms() throws ReflectiveOperationException {
    return field("strategyMechanisms", StrategyMechanismRegistry.class);
  }

  StrategyPreflightRegistry preflights() throws ReflectiveOperationException {
    return field("strategyPreflights", StrategyPreflightRegistry.class);
  }

  StrategyPortfolioRegistry portfolios() throws ReflectiveOperationException {
    return field("strategyPortfolios", StrategyPortfolioRegistry.class);
  }

  PortfolioReplenishmentLedger replenishments() throws ReflectiveOperationException {
    return field("portfolioReplenishments", PortfolioReplenishmentLedger.class);
  }

  RootGoalContract rootGoal() throws ReflectiveOperationException {
    return field("rootGoal", RootGoalContract.class);
  }

  TypedMemory typedMemory() {
    try {
      return field("typedMemory", TypedMemory.class);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  LemmaMemory lemmaMemory() {
    try {
      return field("lemmaMemory", LemmaMemory.class);
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

  @Override
  public void close() {
    pool.close();
  }

  static StrategyCard strategy(
      String id, String title, String mechanism, String requiredClaim, double prior) {
    return new StrategyCard(
        null,
        "Establish the finite-tree target through " + mechanism + '.',
        List.of(),
        List.of(),
        List.of(),
        mechanism,
        List.of(
            new CriticalClaim(
                id + "-required",
                List.of(),
                "Enumerate the smallest finite counterexamples.",
                "required",
                null,
                requiredClaim,
                "needs_check")),
        0.2d,
        prior,
        List.of("Derive the leaf conclusion using " + mechanism + '.'),
        "Search the smallest finite trees for a counterexample.",
        "The route uses a separately stated finite-structure mechanism.",
        null,
        null,
        List.of(),
        List.of("The tree is finite and has at least two vertices."),
        id,
        List.of("presentation-" + id),
        title);
  }

  static List<StrategyCard> fourIndependent(String prefix) {
    return List.of(
        strategy(
            prefix + "-extremal",
            "Longest path",
            "Choose a longest path and prove both endpoints have degree one",
            "The endpoints of a longest path in a finite tree are leaves.",
            0.62d),
        strategy(
            prefix + "-induction",
            "Leaf deletion induction",
            "Delete a leaf and apply induction before restoring the deleted vertex",
            "Deleting a leaf from a nontrivial finite tree preserves a smaller tree.",
            0.61d),
        strategy(
            prefix + "-counting",
            "Degree sum",
            "Use the degree sum identity and count vertices of degree at least two",
            "The degree sum of a finite tree is twice its edge count.",
            0.60d),
        strategy(
            prefix + "-contradiction",
            "Minimal counterexample",
            "Assume a smallest counterexample and remove an endpoint branch",
            "A smallest finite counterexample admits a removable endpoint branch.",
            0.59d));
  }

  private Object invoke(String name) throws Exception {
    return invoke(name, new Class<?>[0]);
  }

  private Object invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
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

  private static String routeId(Object route) {
    return (String) routeField(route, "routeId");
  }

  private static String routeStrategyId(Object route) {
    return ((StrategyCard) routeField(route, "strategy")).strategyId();
  }

  private static Object routeField(Object route, String name) {
    try {
      Field field = route.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(route);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static RunExecutionBackend.ProgressSink noOpProgress() {
    return (type, stage, agentId, status, summary, reference) -> {};
  }

  private static SystemConfig mockConfig(SystemConfig source) {
    List<AgentConfig> agents = source.agents().stream().map(DesktopStrategyPortfolioTestHarness::mockAgent).toList();
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
      throw new IllegalStateException(exception);
    }
  }

  record ProductionState(
      String candidateHash,
      String mechanismHash,
      String preflightHash,
      String portfolioHash,
      String replenishmentHash,
      List<String> admittedStrategyIds,
      List<String> routeIds,
      int archiveCount,
      int blueprintCount,
      int goalLinkCount,
      int obligationCount,
      int pendingTaskCount,
      String rootHash,
      String negativeRegistryHash) {}

  record ProtectedHashes(
      String root,
      String negative,
      String attempts,
      String claims,
      String research,
      String canonicalization,
      String convergence,
      String pivots) {}

  private static final class RecordingResponder implements MockResponder {
    private final List<StrategySet> strategySets;
    private final List<ProviderRequest> requests = new ArrayList<>();
    private int cursor;

    private RecordingResponder(List<StrategySet> strategySets) {
      this.strategySets = List.copyOf(strategySets);
    }

    @Override
    public LLMResponse respond(ProviderRequest request) {
      requests.add(request);
      if (!"StrategySet".equals(request.schemaName()) || cursor >= strategySets.size()) {
        throw new AssertionError("unexpected provider call: " + request.schemaName());
      }
      StrategySet payload = strategySets.get(cursor++);
      return new LLMResponse(
          ContractObjectMapper.write(payload),
          "scripted-model",
          "mock",
          11,
          17,
          0.0d,
          "strategy-portfolio-request-" + cursor,
          "stop",
          false,
          JsonNodeFactory.instance.objectNode());
    }

    private int strategyCalls() {
      return cursor;
    }

    private List<ProviderRequest> requests() {
      return List.copyOf(requests);
    }
  }
}
