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
import io.github.aililuola.mathproofmesh.config.ConcurrencyConfig;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimBlindAdjudicationBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimBlindAdjudicationDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimBlindAdjudicationVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimCounterexampleWitnessReviewBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimCounterexampleWitnessReviewDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimMinimalRepairBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimMinimalRepairDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimMinimalRepairDisposition;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatch;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperation;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperationType;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimSemanticContextBinding;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementFalsificationBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementFalsificationDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.CriticalClaimContextBinding;
import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import io.github.aililuola.mathproofmesh.contract.InitialExplorationAction;
import io.github.aililuola.mathproofmesh.contract.InitialExplorationTurn;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofAttempt;
import io.github.aililuola.mathproofmesh.contract.ProofAuditIssue;
import io.github.aililuola.mathproofmesh.contract.ProofIssueKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.contract.ProofRepairability;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import io.github.aililuola.mathproofmesh.contract.StatementCounterexampleCandidate;
import io.github.aililuola.mathproofmesh.contract.StatementFalsificationDisposition;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import io.github.aililuola.mathproofmesh.memory.LemmaMemory;
import io.github.aililuola.mathproofmesh.memory.TypedMemory;
import io.github.aililuola.mathproofmesh.communication.MessageStoreSnapshot;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactPromptBatch;
import io.github.aililuola.mathproofmesh.communication.artifact.MathematicalArtifactBroker;
import io.github.aililuola.mathproofmesh.concurrency.ResearchAuthorityAnchor;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseManifest;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.ClaimLifecycleController;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlFacade;
import io.github.aililuola.mathproofmesh.proofcontrol.RootGoalContract;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtRecord;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtSnapshot;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtStageExecutionLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtStageExecutionSnapshot;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimProofRevisionLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimProofRevisionRecord;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimProofRevisionSnapshot;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.FrozenClaimSnapshot;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphConvergenceMonitor;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.AgentRuntime;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointLedger;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

final class DesktopClaimSalvageTestHarness implements AutoCloseable {
  static final String SOURCE = DesktopNegativeKnowledgeTestHarness.SOURCE;
  static final String PROBLEM_HASH = DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH;

  private final DesktopSolveCoordinator coordinator;
  private final AgentPool pool;
  private final Path runDirectory;
  private final String runId;
  private final ClaimReviewResponder responder;
  private final InMemoryProviderCallRepository providerCalls;

  private DesktopClaimSalvageTestHarness(
      DesktopSolveCoordinator coordinator,
      AgentPool pool,
      Path runDirectory,
      String runId,
      ClaimReviewResponder responder,
      InMemoryProviderCallRepository providerCalls) {
    this.coordinator = coordinator;
    this.pool = pool;
    this.runDirectory = runDirectory;
    this.runId = runId;
    this.responder = responder;
    this.providerCalls = providerCalls;
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
    InMemoryProviderCallRepository providerCalls = new InMemoryProviderCallRepository();
    StructuredAgentRunner runner =
        new StructuredAgentRunner(
            pool,
            new ArtifactStore(runDirectory.resolve("runtime-artifacts"), runId),
            providerCalls,
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
    return new DesktopClaimSalvageTestHarness(
        coordinator, pool, runDirectory, runId, responder, providerCalls);
  }

  void freezeAndCreateRoute() throws Exception {
    freezeAndCreateRoute(validStrategy());
  }

  void prepareIndependentClaimCourtBatch(int routeCount) throws Exception {
    if (routeCount < 2) {
      throw new IllegalArgumentException("routeCount must be at least two");
    }
    freezeAndCreateRoute();
    @SuppressWarnings("unchecked")
    Map<String, Object> blueprints =
        (Map<String, Object>) rawField(coordinator, "strategyBlueprints");
    Object baseBlueprint = blueprints.get(validStrategy().strategyId());
    while (routes(coordinator).size() < routeCount) {
      StrategyCard strategy = validStrategy("claim-court-batch-" + routes(coordinator).size());
      blueprints.put(strategy.strategyId(), baseBlueprint);
      invoke(
          "addRoute",
          new Class<?>[] {StrategyCard.class, int.class},
          new Object[] {strategy, 0});
    }
    setRound(1);
    List<Object> activeRoutes = routes(coordinator);
    for (int index = 0; index < routeCount; index++) {
      installFailedAttempt(
          activeRoutes.get(index),
          100 + index,
          List.of(
              claim(
                  "parallel-claim-" + index,
                  "PARALLEL_VALID_LOCAL_" + index + ": every even square is divisible by four.",
                  List.of("local_lemma"))),
          List.of(),
          null);
    }
  }

  void prepareEmptyProofClaimCourtBatch(int routeCount) throws Exception {
    if (routeCount < 2) {
      throw new IllegalArgumentException("routeCount must be at least two");
    }
    freezeAndCreateRoute();
    @SuppressWarnings("unchecked")
    Map<String, Object> blueprints =
        (Map<String, Object>) rawField(coordinator, "strategyBlueprints");
    Object baseBlueprint = blueprints.get(validStrategy().strategyId());
    while (routes(coordinator).size() < routeCount) {
      StrategyCard strategy = validStrategy("empty-proof-batch-" + routes(coordinator).size());
      blueprints.put(strategy.strategyId(), baseBlueprint);
      invoke(
          "addRoute",
          new Class<?>[] {StrategyCard.class, int.class},
          new Object[] {strategy, 0});
    }
    setRound(1);
    List<Object> activeRoutes = routes(coordinator);
    for (int index = 0; index < routeCount; index++) {
      installFailedAttempt(
          activeRoutes.get(index),
          300 + index,
          List.of(emptyProofClaim(index)),
          List.of(),
          null);
    }
  }

  void prepareMixedClaimCourtBatch() throws Exception {
    freezeAndCreateRoute();
    @SuppressWarnings("unchecked")
    Map<String, Object> blueprints =
        (Map<String, Object>) rawField(coordinator, "strategyBlueprints");
    Object baseBlueprint = blueprints.get(validStrategy().strategyId());
    while (routes(coordinator).size() < 3) {
      StrategyCard strategy = validStrategy("claim-court-crash-" + routes(coordinator).size());
      blueprints.put(strategy.strategyId(), baseBlueprint);
      invoke(
          "addRoute",
          new Class<?>[] {StrategyCard.class, int.class},
          new Object[] {strategy, 0});
    }
    setRound(1);
    List<String> statements =
        List.of(
            "PARALLEL_VALID_LOCAL: every even square is divisible by four.",
            "REFUTED_LOCAL: every integer is even.",
            "BAD_PROOF_LOCAL: the statement remains open although this proof is invalid.");
    List<Object> activeRoutes = routes(coordinator);
    for (int index = 0; index < statements.size(); index++) {
      installFailedAttempt(
          activeRoutes.get(index),
          200 + index,
          List.of(
              claim(
                  "crash-claim-" + index,
                  statements.get(index),
                  List.of("local_lemma"))),
          List.of(),
          null);
    }
  }

  int maximumConcurrentClaimCourtCalls() {
    return responder.maximumConcurrentCourtCalls();
  }

  void prepareFocusedExploration() throws Exception {
    freezeAndCreateRoute();
    Object focusedRoute = route();
    setField(focusedRoute, "focusObligationId", "main-goal");
    setField(focusedRoute, "focusedCanonicalTargetId", "canonical-focused-test");
  }

  void runFocusedExploration(long delayMillis) throws Exception {
    responder.setExplorationDelayMillis(delayMillis);
    invoke(
        "exploreUnstartedRoutes",
        new Class<?>[] {boolean.class},
        new Object[] {false});
  }

  int maximumConcurrentExplorationCalls() {
    return responder.maximumConcurrentExplorationCalls();
  }

  void setClaimCourtDelays(Map<String, Long> delaysMillis) {
    responder.setClaimCourtDelays(delaysMillis);
  }

  List<String> claimCourtCompletionOrder() {
    return responder.claimCourtCompletionOrder();
  }

  int providerCallCount() {
    return providerCalls.findByRun(runId).size();
  }

  void setAuthoritativeConcurrencyFailurePoint(
      DesktopSolveCoordinator.AuthoritativeConcurrencyFailurePoint point) {
    coordinator.setAuthoritativeConcurrencyFailurePointForTest(point);
  }

  DesktopSolveCoordinator.AuthoritativeConcurrencyDiagnostics concurrencyDiagnostics() {
    return coordinator.authoritativeConcurrencyDiagnosticsForTest();
  }

  ResearchAuthorityAnchor researchAuthorityAnchor() {
    return coordinator.currentResearchAuthorityAnchorForTest();
  }

  void freezeAndCreateRoute(StrategyCard strategy) throws Exception {
    invoke("freezeProblem");
    setField(
        coordinator,
        "strategySet",
        new StrategySet("A bounded production route.", List.of(), List.of(strategy)));
    invoke("generateAndAdmitStrategies");
    invoke("ensureInitialRoutes");
  }

  void freezeAndCreateRouteForClaim(String strategyId, String claimId, String statement)
      throws Exception {
    freezeAndCreateRoute(boundClaimStrategy(strategyId, claimId, statement));
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

  void installSingleClaimRound(int round, String claimId, String statement) throws Exception {
    installFailedAttempt(round, List.of(claim(claimId, statement, List.of("local_lemma"))));
  }

  void installSingleClaimProofRound(
      int round, String claimId, String statement, String proofJustification) throws Exception {
    installFailedAttempt(
        round,
        List.of(
            claim(
                claimId,
                statement,
                List.of("local_lemma"),
                proofJustification)));
  }

  void installModernLocalClaimRound(
      int round,
      String claimId,
      String statement,
      ClaimSemanticContextBinding binding)
      throws Exception {
    List<ClaimSemanticContextBinding> bindings =
        binding == null ? List.of() : List.of(binding);
    installFailedAttempt(
        round,
        List.of(claim(claimId, statement, List.of("local_lemma"))),
        bindings,
        1);
  }

  void integrateInstalledRound() throws Exception {
    invoke("integrateCommittedRoutes");
  }

  void distributeBrokerArtifacts() throws Exception {
    invoke("distributeVerifiedClaims");
  }

  void addRelatedRouteForClaim(String claimId, String statement) throws Exception {
    invoke(
        "addRoute",
        new Class<?>[] {StrategyCard.class, int.class},
        new Object[] {boundClaimStrategy("claim-salvage-target", claimId, statement), 0});
  }

  MathematicalArtifactBroker mathematicalArtifactBroker() {
    return uncheckedField(
        coordinator, "mathematicalArtifactBroker", MathematicalArtifactBroker.class);
  }

  MessageStoreSnapshot legacyMessageStore() {
    Object repository = rawFieldUnchecked(coordinator, "messageRepository");
    try {
      return (MessageStoreSnapshot) repository.getClass().getMethod("snapshot").invoke(repository);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
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

  List<String> exploreUnstartedRoutesAndCaptureBrokerArtifactIds() throws Exception {
    invoke(
        "exploreUnstartedRoutes",
        new Class<?>[] {boolean.class},
        new Object[] {false});
    return responder.lastExplorationBrokerArtifactIds();
  }

  private void installFailedAttempt(int round, List<ClaimCard> claims)
      throws ReflectiveOperationException {
    installFailedAttempt(round, claims, List.of(), null);
  }

  private void installFailedAttempt(
      int round,
      List<ClaimCard> claims,
      List<ClaimSemanticContextBinding> contextBindings,
      Integer contextManifestVersion)
      throws ReflectiveOperationException {
    setRound(round);
    installFailedAttempt(route(), round, claims, contextBindings, contextManifestVersion);
  }

  private static void installFailedAttempt(
      Object route,
      int round,
      List<ClaimCard> claims,
      List<ClaimSemanticContextBinding> contextBindings,
      Integer contextManifestVersion)
      throws ReflectiveOperationException {
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
            new UsageRecord(),
            contextBindings,
            contextManifestVersion);
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
    return claim(
        id,
        statement,
        tags,
        "The supplied proof justifies the asserted local conclusion.");
  }

  private static ClaimCard claim(
      String id, String statement, List<String> tags, String proofJustification) {
    return new ClaimCard(
        List.of(), id, statement, "", "bounded", List.of(), List.of(), List.of(),
        List.of(proofStep(id, statement, proofJustification)),
        List.of(), 0.9d, null, null, null, statement, ClaimStatus.PROPOSED, tags, null);
  }

  private static ClaimCard emptyProofClaim(int index) {
    String id = "empty-proof-claim-" + index;
    String statement =
        "EMPTY_PROOF_LOCAL_" + index + ": every multiple of " + (index + 2) + " is integral.";
    return new ClaimCard(
        List.of(), id, statement, "", "bounded", List.of(), List.of(), List.of(), List.of(),
        List.of(), 0.4d, null, null, null, statement, ClaimStatus.PROPOSED,
        List.of("local_lemma"), null);
  }

  private static ProofStep proofStep(String claimId, String statement) {
    return proofStep(
        claimId,
        statement,
        "The supplied proof justifies the asserted local conclusion.");
  }

  private static ProofStep proofStep(
      String claimId, String statement, String proofJustification) {
    return new ProofStep(
        null,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0.7d,
        List.of(),
        List.of(),
        true,
        proofJustification,
        statement,
        "step-" + claimId,
        "derivation");
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

  DesktopSolveCheckpoint readPersistedCheckpoint() throws Exception {
    Path state = runDirectory.resolve("structured").resolve("desktop-solve-state.json");
    DesktopSolveCheckpoint checkpoint =
        ContractObjectMapper.read(Files.readString(state), DesktopSolveCheckpoint.class);
    return ContractObjectMapper.read(
        ContractObjectMapper.write(checkpoint), DesktopSolveCheckpoint.class);
  }

  DesktopClaimSalvageTestHarness restored(DesktopSolveCheckpoint checkpoint) throws Exception {
    DesktopClaimSalvageTestHarness restored = open(runDirectory, runId);
    restored.restore(checkpoint);
    return restored;
  }

  ConcurrencyAuthorityHashes concurrencyAuthorityHashes() {
    ProofControlFacade facade =
        uncheckedField(coordinator, "proofControl", ProofControlFacade.class);
    return new ConcurrencyAuthorityHashes(
        CanonicalJson.stableHash(facade.claims().snapshot()),
        coordinator.typedMemoryAuthorityHash(),
        coordinator.proofGraphAuthorityHash(),
        attemptArtifacts().ledgerHash(),
        epochCommitAuthorityHash());
  }

  private String epochCommitAuthorityHash() {
    var epochs =
        uncheckedField(
                coordinator,
                "researchEpochs",
                io.github.aililuola.mathproofmesh.concurrency.ResearchEpochLedger.class)
            .snapshot();
    var results =
        uncheckedField(
                coordinator,
                "researchResults",
                io.github.aililuola.mathproofmesh.concurrency.ResearchResultLedger.class)
            .snapshot();
    Map<String, String> statusByWorkItem = new java.util.TreeMap<>();
    results
        .artifacts()
        .forEach(
            artifact ->
                statusByWorkItem.put(
                    artifact.envelope().workItemId(), artifact.envelope().status().name()));
    return CanonicalJson.stableHash(
        epochs.epochs().stream()
            .map(
                epoch ->
                    Map.of(
                        "epoch_id", epoch.epochId(),
                        "snapshot_hash", epoch.snapshotHash(),
                        "status", epoch.status().name(),
                        "work_item_ids", epoch.workItemIds(),
                        "work_result_statuses",
                            epoch.workItemIds().stream()
                                .collect(
                                    java.util.stream.Collectors.toMap(
                                        java.util.function.Function.identity(),
                                        statusByWorkItem::get,
                                        (left, right) -> left,
                                        java.util.TreeMap::new)),
                        "authority", Objects.requireNonNullElse(epoch.authority(), ""),
                        "version", epoch.version()))
            .toList());
  }

  Set<String> factMessageIds() {
    return typedMemory().facts().stream()
        .map(MessageEnvelope::messageId)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  Set<String> graphClaimMessageIds() {
    return proofGraph().claimNodes().stream()
        .map(MessageEnvelope::messageId)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  Set<String> lifecycleClaimIds() {
    ProofControlFacade facade =
        uncheckedField(coordinator, "proofControl", ProofControlFacade.class);
    return facade.claims().entries().stream()
        .map(entry -> entry.claimId())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  void restore(DesktopSolveCheckpoint checkpoint) throws Exception {
    invoke("restore", DesktopSolveCheckpoint.class, checkpoint);
  }

  void acknowledgeLegacyConsumedMessages() throws Exception {
    Object route = route();
    invoke(
        "acknowledgeConsumedMessages",
        new Class<?>[] {route.getClass()},
        new Object[] {route});
  }

  void addRejectedClaimToRoute(String claimId) {
    Object route = routeUnchecked();
    addDistinct(mutableStringListField(route, "claimIds"), claimId);
    addDistinct(mutableStringListField(route, "rejectedClaimIds"), claimId);
  }

  Set<String> rejectedClaimIds() {
    return Set.copyOf(mutableStringListField(routeUnchecked(), "rejectedClaimIds"));
  }

  void setRoundForBrokerTest(int round) throws ReflectiveOperationException {
    setRound(round);
  }

  BrokerArtifactPromptBatch consumeBrokerContext() throws Exception {
    Object route = route();
    return (BrokerArtifactPromptBatch)
        invoke(
            "consumeBrokerContext",
            new Class<?>[] {route.getClass()},
            new Object[] {route});
  }

  void installBrokerUseAttempt(int round) throws ReflectiveOperationException {
    setRound(round);
    Object route = route();
    AgentRuntime author = field(route, "author", AgentRuntime.class);
    StrategyCard strategy = field(route, "strategy", StrategyCard.class);
    ProofAttempt attempt =
        new ProofAttempt(
            author.id(),
            "broker-use-attempt-" + round,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("checked exact counterexample target"),
            null,
            null,
            null,
            PROBLEM_HASH,
            "The delivered counterexample is cited only for its exact Claim target.",
            List.of(proofStep("use", "The exact target Claim was already refuted.")),
            List.of(),
            "artifact://broker-use/" + round,
            null,
            round,
            1,
            0.5d,
            AttemptStatus.FAILED,
            strategy.strategyId(),
            List.of("the main route remains open"),
            new UsageRecord());
    setField(route, "attempt", attempt);
  }

  void stageAndAcknowledgeBrokerUse(BrokerArtifactUseManifest manifest) throws Exception {
    mathematicalArtifactBroker().stageUseManifest(manifest);
    Object route = route();
    invoke(
        "acknowledgeConsumedMessages",
        new Class<?>[] {route.getClass()},
        new Object[] {route});
  }

  void verifyConsumedArtifactEffects() throws Exception {
    Object route = route();
    invoke(
        "verifyConsumedArtifactEffects",
        new Class<?>[] {route.getClass()},
        new Object[] {route});
  }

  @SuppressWarnings("unchecked")
  double schedulerBrokerUtility(String routeId) throws Exception {
    Map<String, Double> utility = (Map<String, Double>) invoke("brokerUtility");
    return utility.getOrDefault(routeId, 0.0d);
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

  ClaimCourtLedger claimCourt() {
    return uncheckedField(coordinator, "claimCourt", ClaimCourtLedger.class);
  }

  FrozenClaimSnapshot frozenClaim(String claimId) {
    return claimCourt().records().stream()
        .map(record -> record.frozenClaim())
        .filter(frozen -> frozen.claimId().equals(claimId))
        .findFirst()
        .orElseThrow();
  }

  boolean factMatchesFrozenClaim(MessageEnvelope fact, FrozenClaimSnapshot frozen)
      throws Exception {
    return (boolean)
        invoke(
            "exactVerifiedFactForFrozenClaim",
            new Class<?>[] {MessageEnvelope.class, FrozenClaimSnapshot.class},
            new Object[] {fact, frozen});
  }

  List<io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.TrustedClaimEvidence>
      trustedComputationEvidence(
          io.github.aililuola.mathproofmesh.contract.ExperimentSpec spec,
          io.github.aililuola.mathproofmesh.contract.ExperimentResult result,
          FrozenClaimSnapshot frozen) {
    return coordinator.trustedComputationEvidenceForTest(
        spec,
        result,
        frozen,
        io.github.aililuola.mathproofmesh.computation.ComputationEvidenceGate.EvidenceAuthority
            .VERIFIED,
        true);
  }

  ClaimProofRevisionLedger claimProofRevisions() {
    return uncheckedField(coordinator, "claimProofRevisions", ClaimProofRevisionLedger.class);
  }

  ClaimCourtStageExecutionLedger claimCourtExecutions() {
    return uncheckedField(
        coordinator, "claimCourtExecutions", ClaimCourtStageExecutionLedger.class);
  }

  void mergeClaimCourtWorkerDraft(
      ClaimCourtRecord record,
      ClaimProofRevisionRecord revision,
      ClaimCourtSnapshot court,
      ClaimProofRevisionSnapshot revisions,
      ClaimCourtStageExecutionSnapshot executions)
      throws Exception {
    Class<?> draftType =
        java.util.Arrays.stream(DesktopSolveCoordinator.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("ClaimCourtCaseDraft"))
            .findFirst()
            .orElseThrow();
    var constructor =
        draftType.getDeclaredConstructor(
            ClaimCourtRecord.class,
            ClaimProofRevisionRecord.class,
            String.class,
            double.class,
            ClaimCourtSnapshot.class,
            ClaimProofRevisionSnapshot.class,
            ClaimCourtStageExecutionSnapshot.class);
    constructor.setAccessible(true);
    Object draft =
        constructor.newInstance(
            record, revision, "merge-authority", 0.5d, court, revisions, executions);
    Method method =
        DesktopSolveCoordinator.class.getDeclaredMethod("mergeClaimCourtWorkerDraft", draftType);
    method.setAccessible(true);
    try {
      method.invoke(coordinator, draft);
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

  void setFailurePoint(ClaimCourtFailurePoint point) {
    coordinator.setClaimCourtFailurePointForTest(point);
  }

  void setHardCrashPoint(ClaimCourtFailurePoint point) {
    coordinator.setClaimCourtHardCrashPointForTest(point);
  }

  long callsForSchema(String schemaName) {
    return claimReviewRequests().stream()
        .filter(request -> request.schemaName().equals(schemaName))
        .count();
  }

  RootGoalContract rootGoal() {
    return uncheckedField(coordinator, "rootGoal", RootGoalContract.class);
  }

  ProtectedHashes protectedHashes() {
    ProofControlFacade facade =
        uncheckedField(coordinator, "proofControl", ProofControlFacade.class);
    return new ProtectedHashes(
        rootGoal().sourceStatementHash(),
        typedMemory().negativeKnowledgeRegistry().registryHash(),
        attemptArtifacts().ledgerHash(),
        CanonicalJson.stableHash(facade.claims().snapshot()),
        uncheckedField(coordinator, "researchCheckpoints", ResearchCheckpointLedger.class)
            .ledgerHash(),
        proofGraph().canonicalizationHash(),
        uncheckedField(
                coordinator, "proofGraphConvergence", ProofGraphConvergenceMonitor.class)
            .stableHash(),
        facade.semanticPivots().ledger().stableHash(),
        uncheckedField(coordinator, "strategyPortfolios", StrategyPortfolioRegistry.class)
            .registryHash(),
        claimCourt().stableHash());
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

  @SuppressWarnings("unchecked")
  private static List<String> mutableStringListField(Object target, String name) {
    return (List<String>) rawFieldUnchecked(target, name);
  }

  private static void addDistinct(List<String> values, String value) {
    if (!values.contains(value)) {
      values.add(value);
    }
  }

  private static StrategyCard validStrategy() {
    return validStrategy("claim-salvage-strategy");
  }

  private static StrategyCard validStrategy(String strategyId) {
    return DesktopStrategyMetadataTestSupport.complete(
        new StrategyCard(
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
            "Claim salvage route"));
  }

  static StrategyCard strategyWithCriticalClaim(
      String strategyId,
      CriticalClaim criticalClaim,
      CriticalClaimContextBinding contextBinding) {
    return DesktopStrategyMetadataTestSupport.complete(
        new StrategyCard(
            null,
            criticalClaim.statement(),
            List.of(),
            List.of(),
            List.of(),
            "Use the explicitly bound critical Claim context.",
            List.of(criticalClaim),
            0.2d,
            0.9d,
            List.of(criticalClaim.statement()),
            criticalClaim.falsificationTest(),
            "The route uses a distinct typed context.",
            null,
            null,
            List.of(),
            List.of(),
            strategyId,
            List.of("claim_context_test"),
            "Bound critical Claim",
            List.of(),
            List.of(contextBinding)));
  }

  private static StrategyCard boundClaimStrategy(
      String strategyId, String claimId, String statement) {
    CriticalClaim claim =
        new CriticalClaim(
            claimId,
            List.of(),
            "Check the exact reusable local claim.",
            "required",
            null,
            statement,
            "needs_check");
    CriticalClaimContextBinding binding =
        new CriticalClaimContextBinding(
            claimId,
            "@claim",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("global"),
            "positive");
    return strategyWithCriticalClaim(strategyId, claim, binding);
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
        new ConcurrencyConfig(true, 4, 1, 5, 4, true, false, 25, 2),
        source.runtime());
  }

  private static AgentConfig mockAgent(AgentConfig source) {
    LinkedHashSet<String> roles = new LinkedHashSet<>(source.roles());
    roles.addAll(
        List.of(
            "counterexample_hunter",
            "route_skeptic",
            "detailed_verifier",
            "route_referee",
            "route_prover",
            "explorer",
            "bridge_prover",
            "final_verifier"));
    return new AgentConfig(
        source.id(), "mock", "claim-review-model", null, null, null, List.copyOf(roles),
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

  record ProtectedHashes(
      String root,
      String negative,
      String attempts,
      String claims,
      String research,
      String canonicalization,
      String convergence,
      String pivots,
      String portfolio,
      String court) {}

  record ConcurrencyAuthorityHashes(
      String claimLifecycle,
      String typedMemory,
      String proofGraph,
      String attemptArtifacts,
      String epochCommit) {}

  private static final class ClaimReviewResponder implements MockResponder {
    private final List<ProviderRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> reviewCalls = new ConcurrentHashMap<>();
    private final List<List<String>> explorationVerifiedFacts = new ArrayList<>();
    private final List<List<String>> explorationBrokerArtifactIds = new ArrayList<>();
    private final AtomicInteger activeCourtCalls = new AtomicInteger();
    private final AtomicInteger maximumCourtCalls = new AtomicInteger();
    private final AtomicInteger activeExplorationCalls = new AtomicInteger();
    private final AtomicInteger maximumExplorationCalls = new AtomicInteger();
    private final List<String> completedCourtCalls = new CopyOnWriteArrayList<>();
    private volatile Map<String, Long> courtDelaysMillis = Map.of();
    private volatile long explorationDelayMillis;

    @Override
    public LLMResponse respond(ProviderRequest request) {
      boolean courtCall = request.schemaName().startsWith("Claim");
      boolean explorationCall = "InitialExplorationTurn".equals(request.schemaName());
      String courtClaim = courtCall ? claimKey(request) : "";
      if (courtCall) {
        int active = activeCourtCalls.incrementAndGet();
        maximumCourtCalls.accumulateAndGet(active, Math::max);
      }
      if (explorationCall) {
        int active = activeExplorationCalls.incrementAndGet();
        maximumExplorationCalls.accumulateAndGet(active, Math::max);
      }
      try {
        try {
          if (courtCall) {
            Thread.sleep(courtDelaysMillis.getOrDefault(courtClaim, 500L));
          }
          if (explorationCall) {
            Thread.sleep(explorationDelayMillis);
          }
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("concurrency test call interrupted", exception);
        }
        if ("InitialExplorationTurn".equals(request.schemaName())) {
          JsonNode context = sanitizedContext(request);
          List<String> facts = new ArrayList<>();
          context
              .path("verified_facts")
              .forEach(fact -> facts.add(fact.path("statement").asText()));
          explorationVerifiedFacts.add(List.copyOf(facts));
          List<String> brokerArtifactIds = new ArrayList<>();
          context
              .path("broker_artifacts")
              .forEach(
                  artifact ->
                      brokerArtifactIds.add(text(artifact, "artifactId", "artifact_id")));
          explorationBrokerArtifactIds.add(List.copyOf(brokerArtifactIds));
          InitialExplorationTurn turn =
              new InitialExplorationTurn(
                  InitialExplorationAction.ABANDON,
                  null,
                  null,
                  null,
                  "verified facts captured for the next route");
          return response(request, ContractObjectMapper.write(turn));
        }
        if ("ClaimStatementFalsificationBatch".equals(request.schemaName())) {
          return statementFalsification(request);
        }
        if ("ClaimCounterexampleWitnessReviewBatch".equals(request.schemaName())) {
          return counterexampleWitnessReview(request);
        }
        if ("ClaimProofAuditBatch".equals(request.schemaName())) {
          return proofAudit(request);
        }
        if ("ClaimMinimalRepairBatch".equals(request.schemaName())) {
          return minimalRepair(request);
        }
        if ("ClaimBlindAdjudicationBatch".equals(request.schemaName())) {
          return blindAdjudication(request);
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
      } finally {
        if (courtCall) {
          completedCourtCalls.add(request.schemaName() + ":" + courtClaim);
          activeCourtCalls.decrementAndGet();
        }
        if (explorationCall) {
          activeExplorationCalls.decrementAndGet();
        }
      }
    }

    int maximumConcurrentCourtCalls() {
      return maximumCourtCalls.get();
    }

    int maximumConcurrentExplorationCalls() {
      return maximumExplorationCalls.get();
    }

    void setExplorationDelayMillis(long delayMillis) {
      if (delayMillis < 0L) {
        throw new IllegalArgumentException("delayMillis must be nonnegative");
      }
      explorationDelayMillis = delayMillis;
    }

    void setClaimCourtDelays(Map<String, Long> delaysMillis) {
      Map<String, Long> checked = new LinkedHashMap<>();
      Objects.requireNonNull(delaysMillis, "delaysMillis")
          .forEach(
              (claim, delay) -> {
                if (claim == null || claim.isBlank() || delay == null || delay < 0L) {
                  throw new IllegalArgumentException("claim delays must be named and nonnegative");
                }
                checked.put(claim.strip(), delay);
              });
      courtDelaysMillis = Map.copyOf(checked);
    }

    List<String> claimCourtCompletionOrder() {
      return List.copyOf(completedCourtCalls);
    }

    private static String claimKey(ProviderRequest request) {
      String prompt = request.messages().getLast().content();
      java.util.regex.Matcher matcher =
          java.util.regex.Pattern.compile("parallel-claim-\\d+").matcher(prompt);
      return matcher.find() ? matcher.group() : "unbound-claim";
    }

    private LLMResponse statementFalsification(ProviderRequest request) {
      requests.add(request);
      JsonNode context = sanitizedContext(request);
      FrozenClaimSnapshot frozen =
          ContractObjectMapper.read(context.path("frozen_claim"), FrozenClaimSnapshot.class);
      String statement = frozen.statement();
      boolean falseStatement =
          statement.contains("REFUTED")
              || (statement.contains("FALSE_LOCAL_R")
                  && !statement.contains("REPAIRABLE")
                  && !statement.contains("BAD_PROOF"));
      List<StatementCounterexampleCandidate> candidates =
          falseStatement
              ? List.of(
                  new StatementCounterexampleCandidate(
                      "candidate-" + frozen.claimId(),
                      frozen.claimId(),
                      frozen.claimStatementHash(),
                      "An exact finite witness refutes this frozen statement.",
                      frozen.assumptions(),
                      frozen.quantifiers(),
                      frozen.scopeLimitations(),
                      frozen.polarity(),
                      List.of("artifact://exact-witness/" + frozen.claimId())))
              : List.of();
      ClaimStatementFalsificationDecision decision =
          new ClaimStatementFalsificationDecision(
              frozen.claimId(),
              statement.contains("UNSUPPORTED_LOCAL")
                  ? StatementFalsificationDisposition.INCONCLUSIVE
                  : falseStatement
                      ? StatementFalsificationDisposition.COUNTEREXAMPLE_CANDIDATE
                      : StatementFalsificationDisposition.NO_COUNTEREXAMPLE_FOUND,
              candidates,
              falseStatement ? "exact counterexample candidate" : "no counterexample found");
      String attemptId = context.path("proof_revision").path("revisionId").asText();
      reviewCalls.putIfAbsent(frozen.sourceAttemptId(), 1);
      ClaimStatementFalsificationBatch batch =
          new ClaimStatementFalsificationBatch(
              "statement-batch-" + frozen.claimId(),
              "model-falsifier",
              frozen.sourceRouteId(),
              frozen.sourceAttemptId(),
              List.of(decision),
              "artifact://statement/" + attemptId,
              new UsageRecord());
      return response(request, ContractObjectMapper.write(batch));
    }

    private LLMResponse counterexampleWitnessReview(ProviderRequest request) {
      requests.add(request);
      JsonNode context = sanitizedContext(request);
      List<ClaimCounterexampleWitnessReviewDecision> decisions = new ArrayList<>();
      for (JsonNode node : context.path("counterexample_candidates")) {
        decisions.add(
            new ClaimCounterexampleWitnessReviewDecision(
                text(node, "candidateId", "candidate_id"),
                text(node, "claimId", "claim_id"),
                text(node, "statementHash", "statement_hash"),
                VerificationVerdict.PASS,
                true,
                true,
                true,
                true,
                true,
                true,
                "exact witness independently replayed"));
      }
      ClaimCounterexampleWitnessReviewBatch batch =
          new ClaimCounterexampleWitnessReviewBatch(
              null,
              "model-witness-reviewer",
              decisions,
              "artifact://witness-review",
              new UsageRecord());
      return response(request, ContractObjectMapper.write(batch));
    }

    private LLMResponse proofAudit(ProviderRequest request) {
      requests.add(request);
      JsonNode context = sanitizedContext(request);
      FrozenClaimSnapshot frozen =
          ContractObjectMapper.read(context.path("frozen_claim"), FrozenClaimSnapshot.class);
      JsonNode revision = context.path("proof_revision");
      String statement = frozen.statement();
      String proofText =
          revision.path("proofSteps").findValuesAsText("justification").stream()
              .collect(java.util.stream.Collectors.joining(" "));
      ClaimProofAuditVerdict verdict =
          revision.path("proofSteps").isEmpty()
              ? ClaimProofAuditVerdict.INVALID_UNREPAIRABLE
              : proofText.contains("UNREPAIRABLE_PROOF")
                  || statement.contains("BAD_PROOF")
                  || statement.contains("UNREPAIRABLE")
              ? ClaimProofAuditVerdict.INVALID_UNREPAIRABLE
              : proofText.contains("CORRECTED_PROOF")
                  ? ClaimProofAuditVerdict.VALID
                  : statement.contains("REPAIRABLE")
                  ? ClaimProofAuditVerdict.INVALID_REPAIRABLE
                  : ClaimProofAuditVerdict.VALID;
      List<ProofAuditIssue> issues =
          verdict == ClaimProofAuditVerdict.VALID
              ? List.of()
              : List.of(
                  new ProofAuditIssue(
                      "issue-" + frozen.claimId(),
                      frozen.claimId(),
                      revision.path("proofSteps").isEmpty()
                          ? "PROOF_LEVEL"
                          : text(revision.path("proofSteps").get(0), "stepId", "step_id"),
                      "The supplied premise.",
                      "The asserted local conclusion.",
                      ProofIssueKind.MISSING_JUSTIFICATION,
                      verdict == ClaimProofAuditVerdict.INVALID_REPAIRABLE
                          ? ProofRepairability.LOCAL_PATCH
                          : ProofRepairability.NONLOCAL_REWRITE_REQUIRED,
                      List.of(),
                      false,
                      "The proof bridge is incomplete."));
      ClaimProofAuditBatch batch =
          new ClaimProofAuditBatch(
              "audit-batch-" + frozen.claimId(),
              "model-auditor",
              frozen.sourceRouteId(),
              frozen.sourceAttemptId(),
              List.of(
                  new ClaimProofAuditDecision(
                      frozen.claimId(), verdict, issues, "proof validity classified")),
              "artifact://proof-audit/" + frozen.claimId(),
              new UsageRecord());
      return response(request, ContractObjectMapper.write(batch));
    }

    private LLMResponse minimalRepair(ProviderRequest request) {
      requests.add(request);
      JsonNode context = sanitizedContext(request);
      FrozenClaimSnapshot frozen =
          ContractObjectMapper.read(context.path("frozen_claim"), FrozenClaimSnapshot.class);
      JsonNode base = context.path("base_proof_revision");
      JsonNode audit = context.path("proof_audit");
      JsonNode issue = audit.path("issues").get(0);
      String issueId = text(issue, "issueId", "issue_id");
      String stepId = text(issue, "stepId", "step_id");
      boolean injectEvidence = frozen.statement().contains("EVIDENCE_INJECTION");
      ClaimProofPatchOperation operation =
          injectEvidence
              ? new ClaimProofPatchOperation(
                  "patch-operation-" + frozen.claimId(),
                  ClaimProofPatchOperationType.ADD_VERIFIED_EVIDENCE_REF,
                  stepId,
                  null,
                  null,
                  null,
                  null,
                  new EvidenceRef(
                      "artifact://repairer-invented",
                      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "certificate",
                      "Repairer claims this artifact is verified."))
              : new ClaimProofPatchOperation(
                  "patch-operation-" + frozen.claimId(),
                  ClaimProofPatchOperationType.REPLACE_STEP_JUSTIFICATION,
                  stepId,
                  null,
                  "A bounded counting or kernel bridge supplies the missing implication.",
                  null,
                  null,
                  null);
      ClaimProofPatch patch =
          new ClaimProofPatch(
              "patch-" + frozen.claimId(),
              frozen.claimId(),
              frozen.claimSemanticHash(),
              base.path("revisionId").asText(),
              base.path("proofHash").asText(),
              List.of(issueId),
              List.of(stepId),
              List.of(operation),
              List.of(issueId));
      ClaimMinimalRepairBatch batch =
          new ClaimMinimalRepairBatch(
              "repair-batch-" + frozen.claimId(),
              "model-repairer",
              frozen.sourceRouteId(),
              frozen.sourceAttemptId(),
              List.of(
                  new ClaimMinimalRepairDecision(
                      frozen.claimId(),
                      ClaimMinimalRepairDisposition.PATCH_PROPOSED,
                      patch,
                      "one local proof step repaired")),
              "artifact://minimal-repair/" + frozen.claimId(),
              new UsageRecord());
      return response(request, ContractObjectMapper.write(batch));
    }

    private LLMResponse blindAdjudication(ProviderRequest request) {
      requests.add(request);
      JsonNode context = sanitizedContext(request);
      String claimId = context.path("blind_claim_packet").path("claimId").asText();
      ClaimBlindAdjudicationBatch batch =
          new ClaimBlindAdjudicationBatch(
              "blind-batch-" + claimId,
              "model-blind-adjudicator",
              context.path("blind_claim_packet").path("sourceRouteId").asText("route-1"),
              attemptIdFromClaim(claimId),
              List.of(
                  new ClaimBlindAdjudicationDecision(
                      claimId,
                      ClaimBlindAdjudicationVerdict.PASS,
                      List.of(),
                      "identity-scrubbed proof passed")),
              "artifact://blind/" + claimId,
              new UsageRecord());
      return response(request, ContractObjectMapper.write(batch));
    }

    private static String attemptIdFromClaim(String claimId) {
      int marker = claimId.lastIndexOf('-');
      if (marker >= 0 && marker + 1 < claimId.length()) {
        try {
          return attemptId(Integer.parseInt(claimId.substring(marker + 1)));
        } catch (NumberFormatException ignored) {
          // Stable non-round Claim IDs use the current synthetic attempt below.
        }
      }
      return "claim-court-attempt";
    }

    private static String text(JsonNode node, String canonicalName, String contractName) {
      String canonical = node.path(canonicalName).asText();
      return canonical.isBlank() ? node.path(contractName).asText() : canonical;
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

    List<String> lastExplorationBrokerArtifactIds() {
      return explorationBrokerArtifactIds.isEmpty()
          ? List.of()
          : explorationBrokerArtifactIds.getLast();
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
