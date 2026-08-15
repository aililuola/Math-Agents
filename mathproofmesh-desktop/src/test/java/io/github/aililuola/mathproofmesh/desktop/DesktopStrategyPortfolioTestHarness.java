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
import io.github.aililuola.mathproofmesh.contract.MechanismOperationDeclaration;
import io.github.aililuola.mathproofmesh.contract.MechanismOperationKind;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.ProblemKind;
import io.github.aililuola.mathproofmesh.contract.ProblemContract;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategyPreflightPlan;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.contract.TaskRequirement;
import io.github.aililuola.mathproofmesh.contract.TriageResult;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import io.github.aililuola.mathproofmesh.memory.LemmaMemory;
import io.github.aililuola.mathproofmesh.memory.TypedMemory;
import io.github.aililuola.mathproofmesh.memory.VerifiedCounterexampleAuthority;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlFacade;
import io.github.aililuola.mathproofmesh.proofcontrol.RootGoalContract;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels;
import io.github.aililuola.mathproofmesh.proofcontrol.ScopeGuard;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyArchive;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyBlueprintCompiler;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphConvergenceMonitor;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointLedger;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimContext;
import io.github.aililuola.mathproofmesh.strategydiversity.PortfolioReplenishmentLedger;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCandidateLedger;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyDiversityConfig;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyMechanismRegistry;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioRegistry;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPreflightRegistry;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPreflightExecutionRecord;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPreflightPlanCompiler;
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
import java.util.LinkedHashSet;
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
    RecordingResponder responder =
        new RecordingResponder(providerStrategySets, sha256(sourceStatement));
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
    responder.registerStrategies(strategies);
    setField(
        "strategySet",
        new StrategySet("Finite-structure mechanism candidates.", List.of(), strategies));
  }

  void setDiversityConfig(StrategyDiversityConfig config)
      throws ReflectiveOperationException {
    setField("strategyDiversityConfig", config);
  }

  void generateAndAdmit() throws Exception {
    invoke("generateAndAdmitStrategies");
  }

  void prepareAgain(String episodeId, StrategyCard strategy) throws Exception {
    prepareAgainSelection(episodeId, strategy);
  }

  List<String> prepareAgainSelection(String episodeId, StrategyCard strategy) throws Exception {
    Object preparation =
        invoke(
        "prepareStrategyPortfolio",
        new Class<?>[] {String.class, StrategySet.class},
        episodeId,
        new StrategySet("Replay captured candidate.", List.of(), List.of(strategy)));
    Method decisionAccessor = preparation.getClass().getDeclaredMethod("decision");
    decisionAccessor.setAccessible(true);
    io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioDecision decision =
        (io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioDecision)
            decisionAccessor.invoke(preparation);
    return decision.selectedStrategyIds();
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

  void setPreflightHardCrashPoint(StrategyPreflightFailurePoint point) {
    coordinator.setStrategyPreflightHardCrashPointForTest(point);
  }

  void registerVerifiedCounterexample(StrategyCard strategy, String id) {
    CriticalClaim claim =
        strategy.criticalClaims().stream()
            .filter(value -> "required".equals(value.necessity()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("strategy has no required claim"));
    CriticalClaimContext context = productionClaimContext(strategy);
    String statement = claim.statement();
    String artifact = "experiment://finite-graph/" + id;
    String raw = "artifact://finite-graph/" + id;
    MessageEnvelope envelope =
        new MessageEnvelope(
            List.of(artifact),
            context.assumptions(),
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
            context.quantifiers(),
            raw,
            0,
            "1",
            context.scopeLimitations(),
            "independent-computation-replay",
            RouteRole.SKEPTIC,
            "route-counterexample",
            statement,
            List.of(),
            2,
            context.variableBindings(),
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

  private CriticalClaimContext productionClaimContext(StrategyCard strategy) {
    try {
      ProblemContract problem = field("frozenProblem", ProblemContract.class);
      LinkedHashSet<String> assumptions = new LinkedHashSet<>(problem.hardConstraints());
      assumptions.addAll(strategy.prerequisites());
      List<QuantifierSpec> quantifiers = new ArrayList<>();
      List<VariableBinding> bindings = new ArrayList<>();
      int order = 0;
      for (var atom : rootGoal().signature().quantifierSkeleton()) {
        for (String variable : atom.variables()) {
          String variableId = "root-q" + order;
          quantifiers.add(
              new QuantifierSpec(
                  variable,
                  "root-goal quantified domain",
                  atom.kind(),
                  order,
                  List.of(),
                  variableId));
          bindings.add(
              new VariableBinding(
                  List.of(variable),
                  variable,
                  "root-goal quantified domain",
                  "root-goal",
                  variableId));
          order++;
        }
      }
      var scope =
          new ScopeGuard()
              .extract("test-root", rootGoal().sourceStatement(), List.of(), 1.0d);
      List<String> limitations = new ArrayList<>();
      if (scope.indexScope() != ProofControlModels.IndexScope.UNKNOWN) {
        limitations.add(
            "index_scope=" + scope.indexScope().name().toLowerCase(java.util.Locale.ROOT));
      }
      if (scope.uniformity() != ProofControlModels.UniformityScope.UNKNOWN) {
        limitations.add(
            "uniformity=" + scope.uniformity().name().toLowerCase(java.util.Locale.ROOT));
      }
      if (scope.objectScope() != ProofControlModels.ObjectScope.UNKNOWN) {
        limitations.add(
            "object_scope=" + scope.objectScope().name().toLowerCase(java.util.Locale.ROOT));
      }
      limitations.addAll(scope.domainConstraints());
      limitations.addAll(scope.exceptionalCases());
      return new CriticalClaimContext(
          List.copyOf(assumptions), quantifiers, limitations, bindings, "positive");
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  void registerVerifiedClaim(String statement, String id) {
    registerVerifiedClaim(statement, id, List.of(), List.of());
  }

  void registerVerifiedClaim(
      String statement,
      String id,
      List<String> assumptions,
      List<String> scopeLimitations) {
    ClaimCard claim =
        new ClaimCard(
            assumptions,
            id,
            statement,
            "",
            "low",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            scopeLimitations,
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

  void registerVerifiedClaimForStrategy(StrategyCard strategy, String id) {
    try {
      ProblemContract problem = field("frozenProblem", ProblemContract.class);
      LinkedHashSet<String> assumptions = new LinkedHashSet<>(problem.hardConstraints());
      assumptions.addAll(strategy.prerequisites());
      var scope =
          new ScopeGuard()
              .extract("test-root", rootGoal().sourceStatement(), List.of(), 1.0d);
      List<String> limitations = new ArrayList<>();
      if (scope.indexScope() != ProofControlModels.IndexScope.UNKNOWN) {
        limitations.add(
            "index_scope=" + scope.indexScope().name().toLowerCase(java.util.Locale.ROOT));
      }
      if (scope.uniformity() != ProofControlModels.UniformityScope.UNKNOWN) {
        limitations.add(
            "uniformity=" + scope.uniformity().name().toLowerCase(java.util.Locale.ROOT));
      }
      if (scope.objectScope() != ProofControlModels.ObjectScope.UNKNOWN) {
        limitations.add(
            "object_scope=" + scope.objectScope().name().toLowerCase(java.util.Locale.ROOT));
      }
      limitations.addAll(scope.domainConstraints());
      limitations.addAll(scope.exceptionalCases());
      registerVerifiedClaim(
          strategy.criticalClaims().getFirst().statement(),
          id,
          List.copyOf(assumptions),
          limitations);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
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

  Map<String, CriticalClaimContext> productionClaimContexts(StrategyCard strategy)
      throws Exception {
    ProofControlFacade facade = field("proofControl", ProofControlFacade.class);
    StrategyBlueprintCompiler.Compilation blueprint = productionBlueprint(strategy);
    ProofControlModels.ScopeSignature scope =
        facade
            .scopeGuard()
            .extract("claim-context-production-test", rootGoal().sourceStatement(), List.of(), 1.0d);
    @SuppressWarnings("unchecked")
    Map<String, CriticalClaimContext> contexts =
        (Map<String, CriticalClaimContext>)
            invoke(
                "criticalClaimContexts",
                new Class<?>[] {
                  StrategyCard.class,
                  StrategyBlueprintCompiler.Compilation.class,
                  ProofControlModels.ScopeSignature.class
                },
                strategy,
                blueprint,
                scope);
    return Map.copyOf(contexts);
  }

  StrategyBlueprintCompiler.Compilation productionBlueprint(StrategyCard strategy)
      throws Exception {
    String boundProblemHash = (String) rawField("problemHash");
    ProofControlModels.Strategy control =
        (ProofControlModels.Strategy)
            invoke(
                "controlStrategy",
                new Class<?>[] {StrategyCard.class, String.class},
                strategy,
                "claim-context-production-test");
    ProofControlModels.Obligation goal =
        (ProofControlModels.Obligation) invoke("controlGoal");
    ProofControlFacade facade = field("proofControl", ProofControlFacade.class);
    return facade.blueprintCompiler().compile(boundProblemHash, control, goal);
  }

  int preflightExecutionCount() throws ReflectiveOperationException {
    return preflights().executionCount();
  }

  StrategyPreflightExecutionRecord onlyPreflightExecution()
      throws ReflectiveOperationException {
    return preflights().snapshot().executions().values().stream().findFirst().orElseThrow();
  }

  int preflightPlanCount() throws ReflectiveOperationException {
    return preflights().snapshot().plans().size();
  }

  long preflightPlanProviderCalls() {
    return responder.requests().stream()
        .filter(request -> "StrategyPreflightPlan".equals(request.schemaName()))
        .count();
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
        title,
        List.of(
            new MechanismOperationDeclaration(
                "declared-mechanism",
                operationKind(mechanism),
                List.of("@roots"),
                List.of("@direct_targets"))),
        List.of());
  }

  static StrategyCard withOperation(
      StrategyCard source, MechanismOperationKind kind) {
    return new StrategyCard(
        source.assignedAgentId(),
        source.bottleneck(),
        source.calculationChecks(),
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
        List.of(
            new MechanismOperationDeclaration(
                "declared-mechanism", kind, List.of("@roots"), List.of("@direct_targets"))),
        source.criticalClaimContextBindings());
  }

  private static MechanismOperationKind operationKind(String mechanism) {
    String normalized = mechanism.toLowerCase(java.util.Locale.ROOT);
    if (normalized.contains("longest")
        || normalized.contains("geodesic")
        || normalized.contains("extremal")) {
      return MechanismOperationKind.EXTREMAL_SELECTION;
    }
    if (normalized.contains("count") || normalized.contains("degree sum")) {
      return MechanismOperationKind.COUNTING;
    }
    if (normalized.contains("smallest counterexample")
        || normalized.contains("minimal counterexample")) {
      return MechanismOperationKind.MINIMAL_COUNTEREXAMPLE;
    }
    if (normalized.contains("induct")
        || normalized.contains("recursive")
        || normalized.contains("leaf")
        || normalized.contains("pendant")
        || normalized.contains("endpoint")) {
      return MechanismOperationKind.REDUCTION;
    }
    return MechanismOperationKind.DIRECT;
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
    private final String problemHash;
    private final Map<String, StrategyCard> strategies = new LinkedHashMap<>();
    private final List<ProviderRequest> requests = new ArrayList<>();
    private int cursor;

    private RecordingResponder(List<StrategySet> strategySets, String problemHash) {
      this.strategySets = List.copyOf(strategySets);
      this.problemHash = problemHash;
      strategySets.forEach(value -> registerStrategies(value.strategies()));
    }

    private void registerStrategies(List<StrategyCard> values) {
      values.forEach(value -> strategies.put(value.strategyId(), value));
    }

    @Override
    public LLMResponse respond(ProviderRequest request) {
      requests.add(request);
      if ("StrategyPreflightPlan".equals(request.schemaName())) {
        StrategyCard strategy =
            strategies.values().stream()
                .filter(
                    value ->
                        request.messages().stream()
                            .anyMatch(message -> message.content().contains(value.strategyId())))
                .findFirst()
                .orElseThrow(
                    () -> new AssertionError("preflight request omitted its strategy id"));
        StrategyPreflightPlan payload =
            new StrategyPreflightPlanCompiler().compile(problemHash, strategy);
        return response(payload, "strategy-preflight-plan-" + strategy.strategyId());
      }
      if (!"StrategySet".equals(request.schemaName()) || cursor >= strategySets.size()) {
        throw new AssertionError("unexpected provider call: " + request.schemaName());
      }
      StrategySet payload = strategySets.get(cursor++);
      registerStrategies(payload.strategies());
      return response(payload, "strategy-portfolio-request-" + cursor);
    }

    private static LLMResponse response(Object payload, String requestId) {
      return new LLMResponse(
          ContractObjectMapper.write(payload),
          "scripted-model",
          "mock",
          11,
          17,
          0.0d,
          requestId,
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
