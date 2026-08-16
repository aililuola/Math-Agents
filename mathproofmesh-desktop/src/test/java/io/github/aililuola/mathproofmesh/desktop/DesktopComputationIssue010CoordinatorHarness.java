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
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimEvidenceSemanticBinding;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.proofgraph.CanonicalObligationRecord;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
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

/** Minimal production coordinator harness for computation persistence and authority boundaries. */
final class DesktopComputationIssue010CoordinatorHarness implements AutoCloseable {
  private final DesktopSolveCoordinator coordinator;
  private final ComputationBroker computation;
  private final AgentPool pool;
  private final Path runDirectory;
  private final String runId;

  private DesktopComputationIssue010CoordinatorHarness(
      DesktopSolveCoordinator coordinator,
      ComputationBroker computation,
      AgentPool pool,
      Path runDirectory,
      String runId) {
    this.coordinator = coordinator;
    this.computation = computation;
    this.pool = pool;
    this.runDirectory = runDirectory;
    this.runId = runId;
  }

  static DesktopComputationIssue010CoordinatorHarness open(
      Path runDirectory, String runId) {
    return open(runDirectory, runId, ComputationHandlerRegistry.javaOnly());
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
            }));
  }

  private static DesktopComputationIssue010CoordinatorHarness open(
      Path runDirectory, String runId, ComputationHandlerRegistry handlers) {
    SystemConfig source =
        new DesktopRuntimeLocator(projectRoot(), null).loadProfile("proof-control-active.yaml");
    SystemConfig config = mockConfig(source);
    Map<String, io.github.aililuola.mathproofmesh.provider.MockResponder> responders =
        new java.util.LinkedHashMap<>();
    config
        .agents()
        .forEach(
            agent ->
                responders.put(
                    agent.id(),
                    request -> {
                      throw new AssertionError(
                          "Issue 010 persistence test attempted a model call: "
                              + request.schemaName());
                    }));
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
            coordinator, computation, pool, runDirectory, runId);
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
    proofGraph()
        .addObligation(
            new ProofObligation(
                List.of("All values use the declared exact finite input."),
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
                List.of(),
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
            List.of(),
            canonical.signature().scopeMarkers(),
            canonical.signature().polarity(),
            List.of(),
            source.domains());
    return new ExperimentSpec(
        source.arguments(),
        source.assumptions(),
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
        open(runDirectory, runId);
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
}
