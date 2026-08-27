package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.agent.BoundedJsonRepairer;
import io.github.aililuola.mathproofmesh.agent.CallLedger;
import io.github.aililuola.mathproofmesh.agent.PromptFactory;
import io.github.aililuola.mathproofmesh.agent.PromptRedactor;
import io.github.aililuola.mathproofmesh.agent.StructuredAgentRunner;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceStore;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.api.SolveRequest;
import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ExternalComputationHandler;
import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.computation.ComputationLimits;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionRecord;
import io.github.aililuola.mathproofmesh.computation.HandlerEvidence;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.config.BudgetConfig;
import io.github.aililuola.mathproofmesh.config.PricingConfig;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimEvidenceSemanticBinding;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import io.github.aililuola.mathproofmesh.orchestration.BudgetStateSnapshot;
import io.github.aililuola.mathproofmesh.orchestration.EvidenceAwareBudgetDecision;
import io.github.aililuola.mathproofmesh.proofcontrol.FailureControlService;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels;
import io.github.aililuola.mathproofmesh.proofgraph.CanonicalObligationRecord;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.FrozenClaimSnapshot;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.AgentRuntime;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Minimal production coordinator harness for computation persistence and authority boundaries. */
final class DesktopComputationIssue010CoordinatorHarness implements AutoCloseable {
  private final DesktopSolveCoordinator coordinator;
  private final ComputationBroker computation;
  private final AgentPool pool;
  private final Path runDirectory;
  private final String runId;
  private final boolean concurrencyProviderEnabled;
  private final AtomicReference<CountDownLatch> concurrencyProviderBarrier;

  private DesktopComputationIssue010CoordinatorHarness(
      DesktopSolveCoordinator coordinator,
      ComputationBroker computation,
      AgentPool pool,
      Path runDirectory,
      String runId,
      boolean concurrencyProviderEnabled,
      AtomicReference<CountDownLatch> concurrencyProviderBarrier) {
    this.coordinator = coordinator;
    this.computation = computation;
    this.pool = pool;
    this.runDirectory = runDirectory;
    this.runId = runId;
    this.concurrencyProviderEnabled = concurrencyProviderEnabled;
    this.concurrencyProviderBarrier = concurrencyProviderBarrier;
  }

  static DesktopComputationIssue010CoordinatorHarness open(
      Path runDirectory, String runId) {
    return open(runDirectory, runId, ComputationHandlerRegistry.javaOnly(), false);
  }

  static DesktopComputationIssue010CoordinatorHarness openForConcurrency(
      Path runDirectory, String runId) {
    return open(runDirectory, runId, ComputationHandlerRegistry.javaOnly(), true);
  }

  static DesktopComputationIssue010CoordinatorHarness openWithBudgetLimits(
      Path runDirectory, String runId, int maximumCalls, Integer maximumTokens) {
    SystemConfig source =
        new DesktopRuntimeLocator(projectRoot(), null).loadProfile("proof-control-active.yaml");
    return open(
        runDirectory,
        runId,
        ComputationHandlerRegistry.javaOnly(),
        false,
        withBudget(source, maximumCalls, maximumTokens));
  }

  static DesktopComputationIssue010CoordinatorHarness openForBudgetProduction(
      Path runDirectory, String runId) {
    SystemConfig source =
        new DesktopRuntimeLocator(projectRoot(), null).loadProfile("proof-control-active.yaml");
    return open(
        runDirectory,
        runId,
        ComputationHandlerRegistry.javaOnly(),
        true,
        source);
  }

  static DesktopComputationIssue010CoordinatorHarness openWithFakeFormalKernel(
      Path runDirectory, String runId) {
    return open(
        runDirectory,
        runId,
        new ComputationHandlerRegistry(
            new ExternalComputationHandler() {
              @Override
              public boolean supports(ComputationMethod method) {
                return method == ComputationMethod.LEAN_CHECK;
              }

              @Override
              public String toolIdentity(ComputationMethod method) {
                return "fake-independent-formal-kernel/1";
              }

              @Override
              public HandlerEvidence execute(
                  ExperimentSpec spec,
                  io.github.aililuola.mathproofmesh.contract.ExperimentProgram program) {
                return new HandlerEvidence(
                    ExperimentOutcome.CERTIFIED,
                    EvidenceStrength.FORMAL_CERTIFICATE,
                    JsonNodeFactory.instance.objectNode().put("kernel", "fake-independent"),
                    null,
                    JsonNodeFactory.instance.objectNode().put("kernel_verified", true),
                    true,
                    1,
                    true,
                    List.of("verified by the test-only independent formal kernel"),
                    null);
              }
        }),
        false);
  }

  private static DesktopComputationIssue010CoordinatorHarness open(
      Path runDirectory,
      String runId,
      ComputationHandlerRegistry handlers,
      boolean concurrencyProviderEnabled) {
    return open(runDirectory, runId, handlers, concurrencyProviderEnabled, null);
  }

  private static DesktopComputationIssue010CoordinatorHarness open(
      Path runDirectory,
      String runId,
      ComputationHandlerRegistry handlers,
      boolean concurrencyProviderEnabled,
      SystemConfig overrideConfig) {
    SystemConfig source =
        overrideConfig == null
            ? new DesktopRuntimeLocator(projectRoot(), null)
                .loadProfile("proof-control-active.yaml")
            : overrideConfig;
    SystemConfig config =
        concurrencyProviderEnabled
            ? overrideConfig == null ? concurrencyConfig(source) : mockConfig(source)
            : mockConfig(source);
    Map<String, io.github.aililuola.mathproofmesh.provider.MockResponder> responders =
        new java.util.LinkedHashMap<>();
    AtomicReference<CountDownLatch> concurrencyProviderBarrier = new AtomicReference<>();
    config
        .agents()
        .forEach(
            agent ->
                responders.put(
                    agent.id(),
                    request ->
                        concurrencyProviderEnabled
                            ? concurrencyResponse(request, concurrencyProviderBarrier)
                            : rejectedProviderCall(request.schemaName())));
    ProviderClientRegistry providers =
        new ProviderClientRegistry(
            config,
            responders,
            ignored ->
                request -> {
                  throw new AssertionError("Issue 010 persistence test attempted network access");
                },
            false,
            ignored -> null);
    AgentPool pool = new AgentPool(config, providers);
    CallLedger ledger = new CallLedger(20_000L, null, null);
    ReasoningTraceStore traces = new ReasoningTraceStore(runDirectory, runId);
    StructuredAgentRunner runner =
        new StructuredAgentRunner(
            pool,
            new ArtifactStore(runDirectory.resolve("runtime-artifacts"), runId),
            new InMemoryProviderCallRepository(),
            ledger,
            new PromptRedactor(List.of()),
            new BoundedJsonRepairer(1_500_000),
            traces);
    DesktopLiveRuntimeFactory.PreparedRuntime runtime =
        new DesktopLiveRuntimeFactory.PreparedRuntime(
            "issue-010-computation", config, Map.of(), false);
    ComputationBroker computation =
        new ComputationBroker(
            runId,
            ComputationLimits.defaultsEnabled(),
            handlers,
            new InMemoryComputationCache(),
            new ArtifactStoreComputationArtifactStore(
                new ArtifactStore(runDirectory.resolve("runtime-artifacts"), runId)));
    DesktopSolveCoordinator coordinator =
        new DesktopSolveCoordinator(
            new SolveRequest(
                DesktopNegativeKnowledgeTestHarness.SOURCE,
                runId,
                null,
                "issue-010-computation"),
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
    DesktopComputationIssue010CoordinatorHarness harness =
        new DesktopComputationIssue010CoordinatorHarness(
            coordinator,
            computation,
            pool,
            runDirectory,
            runId,
            concurrencyProviderEnabled,
            concurrencyProviderBarrier);
    computation.setStatePersister(
        (reason, state) -> {
          try {
            harness.persist(reason);
          } catch (Exception exception) {
            throw new IllegalStateException("computation checkpoint persistence failed", exception);
          }
        });
    return harness;
  }

  ComputationBroker computation() {
    return computation;
  }

  void initializeRoute() throws Exception {
    invoke(coordinator, "freezeProblem", new Class<?>[0], new Object[0]);
    setField(
        "strategySet",
        new StrategySet(
            "A deterministic route is used only to exercise the computation production path.",
            List.of(),
            List.of(validStrategy())));
    invoke(coordinator, "generateAndAdmitStrategies", new Class<?>[0], new Object[0]);
    invoke(coordinator, "ensureInitialRoutes", new Class<?>[0], new Object[0]);
    persist("issue_010_route_initialized");
  }

  String routeId() throws ReflectiveOperationException {
    return String.valueOf(routeField("routeId"));
  }

  void addObligation(String obligationId, String statement) throws Exception {
    addObligation(
        obligationId,
        statement,
        List.of("All values use the declared exact finite input."),
        List.of());
  }

  void addObligation(
      String obligationId,
      String statement,
      List<String> assumptions,
      List<QuantifierSpec> quantifiers)
      throws Exception {
    proofGraph()
        .addObligation(
            new ProofObligation(
                assumptions,
                0.7d,
                "",
                List.of(),
                List.of(),
                List.of(),
                null,
                ObligationKind.LEMMA,
                statement,
                obligationId,
                0.8d,
                DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH,
                quantifiers,
                List.of(routeId()),
                statement,
                "open"));
  }

  void focus(String obligationId) throws ReflectiveOperationException {
    Object route = route();
    Field focus = route.getClass().getDeclaredField("focusObligationId");
    focus.setAccessible(true);
    focus.set(route, obligationId);
  }

  ExperimentSpec exactBound(ExperimentSpec source, String obligationId) throws Exception {
    return exactBound(source, obligationId, List.of(), List.of());
  }

  ExperimentSpec exactBound(
      ExperimentSpec source,
      String obligationId,
      List<VariableBinding> variableBindings,
      List<String> dependencyClaimIds)
      throws Exception {
    ProofObligation obligation = obligation(obligationId);
    CanonicalObligationRecord canonical =
        proofGraph().canonicalTargetForObligation(obligationId).orElseThrow();
    ClaimEvidenceSemanticBinding binding =
        new ClaimEvidenceSemanticBinding(
            DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH,
            obligationId,
            CanonicalJson.stableHash(obligation.statement()),
            canonical.signature().signatureHash(),
            obligation.statement(),
            obligation.statement(),
            obligation.assumptions(),
            obligation.quantifiers(),
            variableBindings,
            canonical.signature().scopeMarkers(),
            canonical.signature().polarity(),
            dependencyClaimIds,
            source.domains());
    return new ExperimentSpec(
        source.arguments(),
        obligation.assumptions(),
        source.broadSearch(),
        source.decisionIfConfirmed(),
        source.decisionIfRefuted(),
        source.domains(),
        source.exactArithmetic(),
        null,
        source.experimentId(),
        source.maxCases(),
        source.method(),
        source.noncomputationalAlternative(),
        source.parentCheckpointId(),
        source.pathId(),
        source.purpose(),
        source.reasoningBasis(),
        null,
        source.requestedBy(),
        source.runtimeFingerprint(),
        source.seed(),
        obligation.statement(),
        source.typedToolGap(),
        source.whyComputationIsNeeded(),
        obligationId,
        binding);
  }

  void setRound(int round) throws ReflectiveOperationException {
    ((java.util.concurrent.atomic.AtomicInteger) field("roundIndex")).set(round);
  }

  boolean exactVerifiedFact(
      MessageEnvelope fact, ClaimEvidenceSemanticBinding binding) throws Exception {
    DesktopSolveCheckpoint checkpoint = checkpointRoundTrip();
    FrozenClaimSnapshot frozen =
        new FrozenClaimSnapshot(
            "court-case-computation-context",
            binding.problemHash(),
            checkpoint.problem().goalHash(),
            binding.claimId(),
            binding.claimStatementHash(),
            binding.claimSemanticHash(),
            binding.statement(),
            binding.conclusion(),
            binding.assumptions(),
            binding.quantifiers(),
            binding.variableBindings(),
            binding.scopeLimitations(),
            binding.polarity(),
            binding.dependencyClaimIds(),
            CanonicalJson.stableHash(binding.dependencyClaimIds()),
            "proof-revision-computation-context",
            "attempt-computation-context",
            routeId(),
            "author-computation-context");
    Method method =
        DesktopSolveCoordinator.class.getDeclaredMethod(
            "exactVerifiedFactForFrozenClaim", MessageEnvelope.class, FrozenClaimSnapshot.class);
    method.setAccessible(true);
    return (boolean) invokeMethod(method, fact, frozen);
  }

  DesktopSolveCheckpoint.ComputationCheckpoint runComputation(ExperimentSpec spec)
      throws Exception {
    Method method =
        java.util.Arrays.stream(DesktopSolveCoordinator.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals("runComputation"))
            .findFirst()
            .orElseThrow();
    method.setAccessible(true);
    invokeMethod(method, route(), spec, 0);
    return checkpointRoundTrip().computations().stream()
        .filter(value -> value.spec().experimentId().equals(spec.experimentId()))
        .findFirst()
        .orElseThrow();
  }

  ProofGraphStore proofGraph() throws ReflectiveOperationException {
    return (ProofGraphStore) field("proofGraph");
  }

  io.github.aililuola.mathproofmesh.memory.TypedMemory typedMemory()
      throws ReflectiveOperationException {
    return (io.github.aililuola.mathproofmesh.memory.TypedMemory) field("typedMemory");
  }

  ProofObligation obligation(String obligationId) throws ReflectiveOperationException {
    return proofGraph().obligations().stream()
        .filter(value -> value.obligationId().equals(obligationId))
        .findFirst()
        .orElseThrow();
  }

  ComputationExecutionRecord execution(String experimentId) throws Exception {
    String requestHash =
        checkpointRoundTrip().computations().stream()
            .filter(value -> value.spec().experimentId().equals(experimentId))
            .map(value -> value.spec().requestHash())
            .findFirst()
            .orElseThrow();
    return computation.executionService().executions().records().stream()
        .filter(value -> value.requestHash().equals(requestHash))
        .findFirst()
        .orElseThrow();
  }

  DesktopSolveCheckpoint checkpointRoundTrip() throws Exception {
    persist("issue_010_test_checkpoint");
    return readCheckpoint();
  }

  DesktopSolveCoordinator coordinator() {
    return coordinator;
  }

  boolean reserveInitialExplorationBudget() throws Exception {
    return (boolean)
        invoke(
            coordinator,
            "reserveInitialExplorationBudget",
            new Class<?>[0],
            new Object[0]);
  }

  void finishBudgetEnvelope() throws Exception {
    invoke(
        coordinator,
        "finishActiveSchedulerBudgetEnvelope",
        new Class<?>[0],
        new Object[0]);
  }

  void persistTerminalCheckpoint() throws Exception {
    setField("workflowCursor", "terminal");
    invoke(
        coordinator,
        "persist",
        new Class<?>[] {String.class, boolean.class},
        new Object[] {"issue_013_terminal", true});
  }

  void markRouteAsCompletedStructuralFailure() throws ReflectiveOperationException {
    Object route = route();
    setRouteField(route, "status", "unverified");
    setRouteField(route, "reviewComplete", true);
    setRouteField(route, "integrated", true);
    setRouteField(
        route,
        "failure",
        new FailureControlService.Failure(
            "failure-budget-policy-v2",
            routeId(),
            "legacy-reviewed-target",
            ProofControlModels.FailureClass.BRIDGE,
            "legacy-reviewed-fingerprint",
            List.of("the completed review found a bridge failure"),
            "create_minimal_bridge",
            0.95d));
  }

  String workflowCursor() throws ReflectiveOperationException {
    return String.valueOf(field("workflowCursor"));
  }

  DesktopSolveCheckpoint.SchedulerStop schedulerStop() throws ReflectiveOperationException {
    return (DesktopSolveCheckpoint.SchedulerStop) field("schedulerStop");
  }

  RunExecutionBackend.RunExecutionResult resumeExecution() throws Exception {
    return coordinator.execute(true);
  }

  long providerCallCount() throws ReflectiveOperationException {
    return ((CallLedger) field("ledger")).totals().calls();
  }

  BudgetStateSnapshot budgetState() throws Exception {
    return (BudgetStateSnapshot)
        invoke(coordinator, "schedulerBudgetState", new Class<?>[0], new Object[0]);
  }

  EvidenceAwareBudgetDecision decideBudget() throws Exception {
    DesktopBudgetRuntime runtime = (DesktopBudgetRuntime) field("budgetRuntime");
    return runtime.manager().decide(budgetState());
  }

  AtomicStageRun runAtomicOrdinaryStageCalls(int callCount) throws Exception {
    if (!concurrencyProviderEnabled) {
      throw new IllegalStateException("atomic stage calls require the concurrency provider fixture");
    }
    if (callCount < 1) {
      throw new IllegalArgumentException("callCount must be positive");
    }
    AgentRuntime preferred = pool.agents().getFirst();
    var ready = new java.util.concurrent.CountDownLatch(callCount);
    var start = new java.util.concurrent.CountDownLatch(1);
    CountDownLatch providerBarrier = new CountDownLatch(callCount);
    if (!concurrencyProviderBarrier.compareAndSet(null, providerBarrier)) {
      throw new IllegalStateException("a concurrency provider barrier is already active");
    }
    List<String> agents = new java.util.ArrayList<>();
    try {
      try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
        for (int ordinal = 0; ordinal < callCount; ordinal++) {
          int stableOrdinal = ordinal;
          futures.add(
              executor.submit(
                  () -> {
                    ready.countDown();
                    start.await();
                    @SuppressWarnings("unchecked")
                    io.github.aililuola.mathproofmesh.agent.StructuredCallResult<Map<String, Object>>
                        result =
                            (io.github.aililuola.mathproofmesh.agent.StructuredCallResult<
                                    Map<String, Object>>)
                                invoke(
                                    coordinator,
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
                                      "atomic-stage-" + stableOrdinal,
                                      "triage",
                                      Map.class,
                                      Map.of("ordinal", stableOrdinal),
                                      preferred,
                                      "breadth",
                                      "Atomic credential selection " + stableOrdinal
                                    });
                    return result.agentId();
                  }));
        }
        if (!ready.await(5L, TimeUnit.SECONDS)) {
          throw new IllegalStateException("concurrent stage calls did not reach the start barrier");
        }
        start.countDown();
        for (var future : futures) {
          agents.add(future.get());
        }
      }
    } finally {
      concurrencyProviderBarrier.compareAndSet(providerBarrier, null);
    }
    return new AtomicStageRun(
        agents,
        pool.concurrencyMetrics().maxActiveProviderCalls(),
        pool.leaseSnapshot());
  }

  DesktopSolveCheckpoint readCheckpoint() throws Exception {
    Path state = runDirectory.resolve("structured/desktop-solve-state.json");
    DesktopSolveCheckpoint checkpoint =
        ContractObjectMapper.read(
            Files.readString(state, StandardCharsets.UTF_8), DesktopSolveCheckpoint.class);
    return ContractObjectMapper.read(
        ContractObjectMapper.write(checkpoint), DesktopSolveCheckpoint.class);
  }

  void restore(DesktopSolveCheckpoint checkpoint) throws Exception {
    invoke(
        coordinator,
        "restore",
        new Class<?>[] {DesktopSolveCheckpoint.class},
        new Object[] {checkpoint});
  }

  DesktopComputationIssue010CoordinatorHarness restored(DesktopSolveCheckpoint checkpoint)
      throws Exception {
    DesktopComputationIssue010CoordinatorHarness restored =
        concurrencyProviderEnabled
            ? openForConcurrency(runDirectory, runId)
            : open(runDirectory, runId);
    restored.restore(checkpoint);
    return restored;
  }

  ProtectedState protectedState() throws Exception {
    return ProtectedState.from(checkpointRoundTrip());
  }

  @Override
  public void close() {
    pool.close();
  }

  private void persist(String reason) throws Exception {
    invoke(
        coordinator,
        "persist",
        new Class<?>[] {String.class, boolean.class},
        new Object[] {reason, false});
  }

  private static Object invoke(
      Object target, String name, Class<?>[] types, Object[] arguments) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name, types);
    method.setAccessible(true);
    try {
      return method.invoke(target, arguments);
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

  private Object route() throws ReflectiveOperationException {
    @SuppressWarnings("unchecked")
    List<Object> routes = (List<Object>) field("routes");
    return routes.getFirst();
  }

  private Object routeField(String name) throws ReflectiveOperationException {
    Object route = route();
    Field value = route.getClass().getDeclaredField(name);
    value.setAccessible(true);
    return value.get(route);
  }

  private static void setRouteField(Object route, String name, Object replacement)
      throws ReflectiveOperationException {
    Field value = route.getClass().getDeclaredField(name);
    value.setAccessible(true);
    value.set(route, replacement);
  }

  private Object field(String name) throws ReflectiveOperationException {
    Field value = DesktopSolveCoordinator.class.getDeclaredField(name);
    value.setAccessible(true);
    return value.get(coordinator);
  }

  private void setField(String name, Object replacement) throws ReflectiveOperationException {
    Field value = DesktopSolveCoordinator.class.getDeclaredField(name);
    value.setAccessible(true);
    value.set(coordinator, replacement);
  }

  private static StrategyCard validStrategy() {
    return DesktopStrategyMetadataTestSupport.complete(
        new StrategyCard(
            null,
            "Prove the root goal through a deterministic exact local lemma.",
            List.of(),
            List.of(),
            List.of(),
            "Use a direct proof and isolate every computational question.",
            List.of(),
            0.2d,
            0.9d,
            List.of("Establish the exact local lemma."),
            "Check only the explicitly bound local target.",
            "All authority targets are server-owned.",
            null,
            null,
            List.of(),
            List.of(),
            "issue-010-production-route",
            List.of("direct_exact_local_lemma"),
            "Issue 010 production route"));
  }

  private static RunExecutionBackend.ProgressSink noOpProgress() {
    return (type, stage, agentId, status, summary, reference) -> {};
  }

  private static SystemConfig mockConfig(SystemConfig source) {
    List<AgentConfig> agents =
        source.agents().stream()
            .map(DesktopComputationIssue010CoordinatorHarness::mockAgent)
            .toList();
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

  private static SystemConfig withBudget(
      SystemConfig source, int maximumCalls, Integer maximumTokens) {
    BudgetConfig budget = source.budget();
    BudgetConfig replacement =
        new BudgetConfig(
            maximumCalls,
            budget.maxRounds(),
            budget.initialPaths(),
            budget.maxPaths(),
            budget.strategiesToGenerate(),
            budget.candidatesToVerify(),
            budget.maxRevisions(),
            budget.baseVerifierReplicas(),
            budget.highRiskVerifierReplicas(),
            budget.highRiskThreshold(),
            budget.verificationPassThreshold(),
            budget.synthesisThreshold(),
            maximumTokens,
            budget.maxCostUsd(),
            budget.breadthShare(),
            budget.depthShare(),
            budget.verificationShare(),
            budget.synthesisShare(),
            budget.scaleBudgetWithDifficulty(),
            budget.hardProblemCallMultiplier(),
            budget.hardProblemExtraRounds());
    return new SystemConfig(
        source.systemName(),
        source.agents(),
        replacement,
        source.scheduler(),
        source.topology(),
        source.verification(),
        source.continuation(),
        source.deepExplorationPolicy(),
        source.computation().withSandboxedPythonEnabled(false),
        source.concurrency(),
        source.runtime());
  }

  private static SystemConfig concurrencyConfig(SystemConfig source) {
    SystemConfig concurrency = DesktopResearchConcurrencyTestSupport.config();
    return new SystemConfig(
        source.systemName(),
        concurrency.agents(),
        source.budget(),
        source.scheduler(),
        source.topology(),
        source.verification(),
        source.continuation(),
        source.deepExplorationPolicy(),
        source.computation().withSandboxedPythonEnabled(false),
        concurrency.concurrency(),
        concurrency.runtime());
  }

  private static io.github.aililuola.mathproofmesh.provider.LLMResponse concurrencyResponse(
      io.github.aililuola.mathproofmesh.provider.ProviderRequest request,
      AtomicReference<CountDownLatch> concurrencyProviderBarrier) {
    CountDownLatch providerBarrier = concurrencyProviderBarrier.get();
    if (providerBarrier != null) {
      providerBarrier.countDown();
      try {
        if (!providerBarrier.await(5L, TimeUnit.SECONDS)) {
          throw new IllegalStateException("provider calls did not enter the concurrency barrier");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("provider concurrency barrier was interrupted", exception);
      }
    }
    java.util.concurrent.locks.LockSupport.parkNanos(
        TimeUnit.MILLISECONDS.toNanos(20L));
    return new io.github.aililuola.mathproofmesh.provider.LLMResponse(
        "{}",
        "mock",
        "issue-012-model",
        1,
        1,
        20.0d,
        "request-" + request.messages().getLast().content(),
        "stop",
        false,
        null);
  }

  private static io.github.aililuola.mathproofmesh.provider.LLMResponse rejectedProviderCall(
      String schemaName) {
    throw new AssertionError(
        "Issue 010 persistence test attempted a model call: " + schemaName);
  }

  private static AgentConfig mockAgent(AgentConfig source) {
    return new AgentConfig(
        source.id(),
        "mock",
        "issue-010-model",
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
        PricingConfig.defaults(),
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

  record ProtectedState(
      String rootHash,
      String negativeRegistryHash,
      String claimLifecycleHash,
      String researchCheckpointHash,
      String canonicalizationHash,
      String convergenceHash,
      String semanticPivotHash,
      String strategyPortfolioHash,
      String claimCourtHash,
      String brokerHash,
      long directFacts,
      int directClaims,
      long permanentNegatives,
      long mainGoalClosures) {
    static ProtectedState from(DesktopSolveCheckpoint checkpoint) {
      long facts =
          checkpoint.typedMemory().tiers().values().stream()
              .filter(tier -> tier == MemoryTier.FACT)
              .count();
      long permanent =
          checkpoint.typedMemory().negativeKnowledge().records().stream()
              .filter(record -> record.permanent())
              .count();
      long mainClosures =
          checkpoint.proofGraph().obligations().values().stream()
              .filter(obligation -> obligation.kind().name().equals("MAIN_GOAL"))
              .filter(obligation -> obligation.status().equals("closed"))
              .count();
      return new ProtectedState(
          checkpoint.problemHash(),
          CanonicalJson.stableHash(checkpoint.typedMemory().negativeKnowledge()),
          CanonicalJson.stableHash(checkpoint.claimLifecycle()),
          CanonicalJson.stableHash(checkpoint.researchCheckpoints()),
          CanonicalJson.stableHash(checkpoint.proofGraph().canonicalization()),
          CanonicalJson.stableHash(checkpoint.proofGraphConvergence()),
          CanonicalJson.stableHash(checkpoint.semanticPivots()),
          CanonicalJson.stableHash(checkpoint.strategyPortfolios()),
          CanonicalJson.stableHash(checkpoint.claimCourt()),
          CanonicalJson.stableHash(
              List.of(
                  checkpoint.brokerArtifactRegistry(),
                  checkpoint.brokerArtifactPublications(),
                  checkpoint.brokerArtifactDeliveries(),
                  checkpoint.brokerArtifactReceipts(),
                  checkpoint.brokerArtifactUses(),
                  checkpoint.brokerArtifactUtilities(),
                  checkpoint.brokerArtifactInvalidations())),
          facts,
          checkpoint.claimLifecycle().entries().size(),
          permanent,
          mainClosures);
    }
  }

  record AtomicStageRun(
      List<String> agentIds,
      int maximumActiveProviderCalls,
      io.github.aililuola.mathproofmesh.concurrency.AgentLeaseSnapshot leases) {
    AtomicStageRun {
      agentIds = List.copyOf(agentIds);
    }

    @Override
    public List<String> agentIds() {
      return List.copyOf(agentIds);
    }
  }
}
