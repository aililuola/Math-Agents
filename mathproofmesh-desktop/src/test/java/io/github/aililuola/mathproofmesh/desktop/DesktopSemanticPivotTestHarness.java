package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewBatch;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewDecision;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import io.github.aililuola.mathproofmesh.proofcontrol.MathematicalObjectChange;
import io.github.aililuola.mathproofmesh.proofcontrol.DependencyResolver;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUseChange;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUsageAction;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotDelta;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotDirectionChange;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotObjectDisposition;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotObligationAction;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotObligationChange;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotObstructionRef;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotProposedClaimDraft;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotTransformationType;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlFacade;
import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotController;
import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotRecord;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyArchive;
import io.github.aililuola.mathproofmesh.orchestration.ContinuationFunctions;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphConvergenceMonitor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DesktopSemanticPivotTestHarness implements AutoCloseable {
  private final DesktopNegativeKnowledgeTestHarness base;
  private final DesktopSolveCoordinator coordinator;

  private DesktopSemanticPivotTestHarness(
      DesktopNegativeKnowledgeTestHarness base, DesktopSolveCoordinator coordinator) {
    this.base = base;
    this.coordinator = coordinator;
  }

  static DesktopSemanticPivotTestHarness open(Path directory, String runId) throws Exception {
    DesktopNegativeKnowledgeTestHarness base =
        DesktopNegativeKnowledgeTestHarness.open(directory, runId);
    base.freezeAndCreateValidRoute();
    DesktopSemanticPivotTestHarness harness =
        new DesktopSemanticPivotTestHarness(base, coordinator(base));
    Object route = harness.route();
    harness.invoke("ensureSeedCheckpoint", new Class<?>[] {route.getClass()}, route);
    return harness;
  }

  static DesktopSemanticPivotTestHarness restore(
      Path directory, String runId, DesktopSolveCheckpoint checkpoint) throws Exception {
    DesktopNegativeKnowledgeTestHarness base =
        DesktopNegativeKnowledgeTestHarness.open(directory, runId);
    base.restore(checkpoint);
    return new DesktopSemanticPivotTestHarness(base, coordinator(base));
  }

  PivotDelta validDelta(int ordinal) throws Exception {
    return validDelta(ordinal, List.of());
  }

  PivotDelta validDelta(int ordinal, List<PivotClaimUseChange> claimChanges) throws Exception {
    Object route = route();
    StrategyCard source = field(route, "strategy", StrategyCard.class);
    @SuppressWarnings("unchecked")
    Set<String> activeObjects =
        new LinkedHashSet<>((Set<String>) rawField(route, "activeMathematicalObjectIds"));
    String oldObject = activeObjects.stream().findFirst().orElseThrow();
    String oldDirection = field(route, "activeDirectionSignature", String.class);
    String routeId = field(route, "routeId", String.class);
    ProofObligation oldFocus =
        proofGraph().obligations().stream()
            .filter(obligation -> obligation.routeIds().contains(routeId))
            .filter(obligation -> "open".equals(obligation.status()))
            .filter(obligation -> obligation.kind() != ObligationKind.MAIN_GOAL)
            .findFirst()
            .orElseGet(
                () ->
                    proofGraph().obligations().stream()
                        .filter(obligation -> obligation.routeIds().contains(routeId))
                        .filter(obligation -> "open".equals(obligation.status()))
                        .findFirst()
                        .orElseThrow());
    String oldTarget =
        proofGraph()
            .canonicalTargetForObligation(oldFocus.obligationId())
            .orElseThrow()
            .canonicalTargetId();
    PivotObstructionRef obstruction = obstructionRefs(route).values().stream().findFirst().orElseThrow();
    String suffix = Integer.toString(ordinal);
    StrategyCard proposed = proposedStrategy(source, suffix);
    List<PivotObligationChange> obligations = new ArrayList<>();
    obligations.add(
        new PivotObligationChange(
            oldFocus.obligationId(),
            oldTarget,
            PivotObligationAction.RETIRE_FROM_STRATEGY_FOCUS,
            null,
            null,
            List.of(),
            List.of(),
            "The obstruction retires this target only from the current strategy focus."));
    obligations.add(
        new PivotObligationChange(
            "semantic-pivot-obligation-" + suffix,
            null,
            PivotObligationAction.ADD_NEW_OBLIGATION,
            "For the global support family, reduce a prime larger than the initial term, case "
                + suffix
                + ".",
            ObligationKind.SUBGOAL,
            List.of("the prime exceeds the initial term"),
            List.of(),
            "This reduction is load-bearing for the global target."));
    return PivotDelta.create(
        DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH,
        rootHash(),
        routeId,
        source.strategyId(),
        List.of(
            PivotTransformationType.OBJECT_REPLACEMENT,
            PivotTransformationType.TARGET_REFORMULATION,
            PivotTransformationType.REPRESENTATION_CHANGE),
        List.of(obstruction),
        List.of(
            new MathematicalObjectChange(
                oldObject,
                "current route object",
                PivotObjectDisposition.REPLACE,
                "global-support-object-" + suffix,
                "global inclusion-minimal support family " + suffix,
                "The replacement retains the support formulation while abandoning the obstructed local direction.",
                List.of(obstruction.obstructionId()))),
        List.of(
            new PivotDirectionChange(
                oldDirection,
                "global-large-prime-reduction-" + suffix,
                "The trusted obstruction requires a global support reduction.",
                List.of(obstruction.obstructionId()))),
        List.of(),
        claimChanges,
        obligations,
        proposed,
        "Apply an explicit object, target, direction, and obligation transition.");
  }

  PivotDelta textOnlyDelta(int ordinal) throws Exception {
    Object route = route();
    StrategyCard source = field(route, "strategy", StrategyCard.class);
    PivotObstructionRef obstruction = obstructionRefs(route).values().stream().findFirst().orElseThrow();
    StrategyCard prose =
        new StrategyCard(
            null,
            source.bottleneck(),
            source.calculationChecks(),
            source.calculationEvidenceRefs(),
            source.computationHints(),
            source.coreIdea() + " Restated without changing mathematical state.",
            source.criticalClaims(),
            source.estimatedCost(),
            source.estimatedSuccess(),
            source.expectedLemmas(),
            source.falsificationTest(),
            source.independenceBasis(),
            source.inspirationProposalId(),
            source.keyOriginalStep(),
            List.of(source.strategyId()),
            source.prerequisites(),
            "semantic-text-only-" + ordinal,
            source.tags(),
            source.title() + " restated");
    return PivotDelta.create(
        DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH,
        rootHash(),
        field(route, "routeId", String.class),
        source.strategyId(),
        List.of(),
        List.of(obstruction),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        prose,
        "Only title and core prose changed.");
  }

  boolean localRepair() throws Exception {
    Object route = route();
    Method revisionMethod =
        DesktopSolveCoordinator.class.getDeclaredMethod("revisionStrategy", route.getClass(), String.class);
    revisionMethod.setAccessible(true);
    StrategyCard revision = (StrategyCard) revisionMethod.invoke(coordinator, route, "revise");
    Method prepare =
        DesktopSolveCoordinator.class.getDeclaredMethod(
            "prepareRouteRevision",
            route.getClass(),
            StrategyCard.class,
            String.class,
            StrategyArchive.RevisionReason.class);
    prepare.setAccessible(true);
    return (boolean)
        prepare.invoke(
            coordinator,
            route,
            revision,
            "REVISE",
            StrategyArchive.RevisionReason.PLAN_FAILURE);
  }

  String registerVerifiedFact(int ordinal) throws Exception {
    String claimId = "invalid-fact-" + ordinal;
    String statement = "The admissibility condition is equivalent to a support hitting condition.";
    base.attemptFactPromotion(ordinal, statement);
    ProofControlFacade facade = coordinatorField("proofControl", ProofControlFacade.class);
    facade.claims().register(claimId, "attempt-verified", "delta-verified", List.of(), false);
    facade.claims().recordLocalVerification(claimId, "local-review");
    facade
        .claims()
        .recordIndependentVerification(
            claimId, "independent-reviewer", "proof-author", "independent-review");
    facade
        .claims()
        .recordRefereeAcceptance(
            claimId,
            "referee",
            "proof-author",
            "referee-review",
            true,
            true,
            true,
            true);
    facade
        .claims()
        .proposePromotion(
            claimId,
            new DependencyResolver.Resolution(true, List.of(), List.of(), List.of(), List.of()),
            true,
            List.of());
    facade.claims().observeExternalAdmission(claimId, "typed-memory://" + claimId);
    @SuppressWarnings("unchecked")
    List<String> claimIds = (List<String>) rawField(route(), "claimIds");
    claimIds.add(claimId);
    return claimId;
  }

  boolean verifiedFactVisible(String claimId) {
    return coordinatorField(
            "typedMemory", io.github.aililuola.mathproofmesh.memory.TypedMemory.class)
        .factsForRoute("route-1")
        .stream()
        .anyMatch(message -> message.messageId().equals(claimId));
  }

  String authoritativeClaimStatementHash(String claimId) {
    return coordinatorField(
            "typedMemory", io.github.aililuola.mathproofmesh.memory.TypedMemory.class)
        .facts()
        .stream()
        .filter(fact -> fact.messageId().equals(claimId))
        .map(fact -> PivotProposedClaimDraft.statementHash(fact.statement()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown authoritative Claim: " + claimId));
  }

  boolean proposedClaimExists(String claimId) {
    return proposedClaimCount(claimId) == 1L;
  }

  long proposedClaimCount(String claimId) {
    return coordinatorField(
            "lemmaMemory", io.github.aililuola.mathproofmesh.memory.LemmaMemory.class)
        .claims()
        .stream()
        .filter(claim -> claim.claimId().equals(claimId))
        .count();
  }

  long proposedClaimLifecycleCount(String claimId) {
    return coordinatorField("proofControl", ProofControlFacade.class).claims().entries().stream()
        .filter(entry -> entry.claimId().equals(claimId))
        .filter(
            entry ->
                entry.state()
                    == io.github.aililuola.mathproofmesh.proofcontrol.ClaimLifecycleController.State.PROPOSED)
        .count();
  }

  long pendingPivotProposedClaimCount(String claimId) throws Exception {
    @SuppressWarnings("unchecked")
    List<io.github.aililuola.mathproofmesh.contract.ClaimCard> pending =
        (List<io.github.aililuola.mathproofmesh.contract.ClaimCard>)
            rawField(route(), "pendingPivotProposedClaims");
    return pending.stream().filter(claim -> claim.claimId().equals(claimId)).count();
  }

  boolean proposedClaimDirectlyVerified(String claimId) {
    ProofControlFacade facade = coordinatorField("proofControl", ProofControlFacade.class);
    return facade.claims().entries().stream()
        .filter(entry -> entry.claimId().equals(claimId))
        .anyMatch(
            entry ->
                entry.state()
                    != io.github.aililuola.mathproofmesh.proofcontrol.ClaimLifecycleController.State.PROPOSED);
  }

  boolean proposedClaimDirectlyPromoted(String claimId) {
    return coordinatorField(
            "typedMemory", io.github.aililuola.mathproofmesh.memory.TypedMemory.class)
        .facts()
        .stream()
        .anyMatch(fact -> fact.messageId().equals(claimId));
  }

  void enterFocusedRecovery() {
    ProofGraphConvergenceMonitor monitor =
        coordinatorField("proofGraphConvergence", ProofGraphConvergenceMonitor.class);
    for (int round = 0; round < 5; round++) {
      monitor.sample(round, proofGraph(), 0, 0, 0, rootHash());
    }
  }

  void bindRouteToUnrelatedFocus() throws Exception {
    Object route = route();
    Field canonical = route.getClass().getDeclaredField("focusedCanonicalTargetId");
    canonical.setAccessible(true);
    canonical.set(route, "unrelated-canonical-target");
    Field family = route.getClass().getDeclaredField("focusedBottleneckFamilyId");
    family.setAccessible(true);
    family.set(route, "unrelated-bottleneck-family");
  }

  SemanticPivotRecord apply(PivotDelta delta) {
    return coordinator.applySemanticPivot(delta, acceptedReview(delta));
  }

  SemanticPivotRecord apply(PivotDelta delta, SemanticPivotReviewBatch review) {
    return coordinator.applySemanticPivot(delta, review);
  }

  SemanticPivotReviewBatch acceptedReview(PivotDelta delta) {
    return new SemanticPivotReviewBatch(
        "review-" + delta.pivotId(),
        "independent-reviewer",
        "pivot-proposer",
        List.of(
            new SemanticPivotReviewDecision(
                delta.pivotId(),
                VerificationVerdict.PASS,
                0.99d,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                List.of(),
                "The bounded delta is coherent and does not escalate mathematical authority.")),
        "artifact://review/" + delta.pivotId(),
        new UsageRecord());
  }

  DesktopSolveCheckpoint checkpoint() throws Exception {
    return base.checkpointRoundTrip();
  }

  State state() throws Exception {
    Object route = route();
    io.github.aililuola.mathproofmesh.memory.TypedMemory memory =
        coordinatorField(
            "typedMemory", io.github.aililuola.mathproofmesh.memory.TypedMemory.class);
    ProofControlFacade facade = coordinatorField("proofControl", ProofControlFacade.class);
    return new State(
        semanticPivots().ledger().records().size(),
        strategyArchive().lineage().size(),
        field(route, "strategy", StrategyCard.class).strategyId(),
        field(route, "activeSemanticPivotId", String.class),
        Set.copyOf(castSet(rawField(route, "activeMathematicalObjectIds"))),
        proofGraph().obligations().size(),
        proofGraph().rawObligationOccurrences().size(),
        pendingTasks().size(),
        checkpointAuditSize(),
        proofGraph().globalCanonicalProofDebt(),
        semanticPivots().ledger().stableHash(),
        rootHash(),
        authorityHash("negativeKnowledgeRegistry", "registryHash"),
        authorityHash("attemptArtifacts", "ledgerHash"),
        claimHash(),
        authorityHash("researchCheckpoints", "ledgerHash"),
        memory.facts().size(),
        memory.negativeKnowledgeRegistry().records().size(),
        (int)
            facade.claims().entries().stream()
                .filter(
                    entry ->
                        entry.state()
                            == io.github.aililuola.mathproofmesh.proofcontrol.ClaimLifecycleController.State.EXTERNALLY_ADMITTED_FACT)
                .count(),
        proofGraph().getObligation("main-goal").status(),
        convergenceAuthorityHash());
  }

  Map<String, String> canonicalTargetHashes() {
    return proofGraph().canonicalizationSnapshot().canonicalTargets().entrySet().stream()
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> CanonicalJson.stableHash(entry.getValue())));
  }

  String obligationStatus(String obligationId) {
    return proofGraph().getObligation(obligationId).status();
  }

  String firstRetiredObligation(PivotDelta delta) {
    return delta.obligationChanges().stream()
        .filter(change -> change.action() == PivotObligationAction.RETIRE_FROM_STRATEGY_FOCUS)
        .map(PivotObligationChange::obligationId)
        .findFirst()
        .orElseThrow();
  }

  boolean routeContainsObject(String objectId) throws Exception {
    return castSet(rawField(route(), "activeMathematicalObjectIds")).contains(objectId);
  }

  StrategyCard activeStrategy() throws Exception {
    return field(route(), "strategy", StrategyCard.class);
  }

  List<?> revisionHistory() throws Exception {
    return List.copyOf((List<?>) rawField(route(), "revisionHistory"));
  }

  Map<String, Object> routePromptContext() throws Exception {
    Object route = route();
    @SuppressWarnings("unchecked")
    Map<String, Object> context =
        (Map<String, Object>) invoke("baseRouteContext", new Class<?>[] {route.getClass()}, route);
    return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(context));
  }

  StrategyArchive strategyArchive() {
    return coordinatorField("strategyArchive", StrategyArchive.class);
  }

  ProofGraphStore proofGraph() {
    return coordinatorField("proofGraph", ProofGraphStore.class);
  }

  SemanticPivotController semanticPivots() {
    return coordinatorField("semanticPivots", SemanticPivotController.class);
  }

  long materializedPivotRecords() {
    return semanticPivots().ledger().records().stream()
        .filter(
            record ->
                record.status()
                        == io.github.aililuola.mathproofmesh.proofcontrol.PivotDeltaStatus.APPLYING
                    || record.status()
                        == io.github.aililuola.mathproofmesh.proofcontrol.PivotDeltaStatus.APPLIED
                    || record.status()
                        == io.github.aililuola.mathproofmesh.proofcontrol.PivotDeltaStatus.EVALUATED)
        .count();
  }

  long pivotApplyReceiptCount() {
    return semanticPivots().ledger().records().stream()
        .filter(record -> record.applyReceipt() != null)
        .count();
  }

  ContinuationFunctions.CheckpointLedgerSnapshot checkpointLedgerSnapshot() {
    return coordinatorField("checkpoints", ContinuationFunctions.CheckpointLedger.class)
        .snapshot();
  }

  int taskLeaseCount() {
    return coordinatorField("proofGraphConvergence", ProofGraphConvergenceMonitor.class)
        .snapshot()
        .focusedTaskLeases()
        .size();
  }

  int pendingTaskCount() throws Exception {
    return pendingTasks().size();
  }

  DesktopSolveCheckpoint persistedCheckpoint() throws Exception {
    Path runDirectory = (Path) rawField(coordinator, "runDirectory");
    Path state = runDirectory.resolve("structured").resolve("desktop-solve-state.json");
    return ContractObjectMapper.read(Files.readString(state), DesktopSolveCheckpoint.class);
  }

  void failurePoint(SemanticPivotFailurePoint point) {
    coordinator.setSemanticPivotFailurePointForTest(point);
  }

  DesktopSolveCoordinator coordinator() {
    return coordinator;
  }

  String rootHash() {
    try {
      Object root = rawField(coordinator, "rootGoal");
      return (String) root.getClass().getMethod("sourceStatementHash").invoke(root);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @Override
  public void close() {
    base.close();
  }

  private Map<String, PivotObstructionRef> obstructionRefs(Object route) throws Exception {
    @SuppressWarnings("unchecked")
    Map<String, PivotObstructionRef> values =
        (Map<String, PivotObstructionRef>) invoke("pivotObstructionReferences", new Class<?>[] {route.getClass()}, route);
    return values;
  }

  private Object route() throws Exception {
    @SuppressWarnings("unchecked")
    List<Object> routes = (List<Object>) rawField(coordinator, "routes");
    return routes.getFirst();
  }

  private List<?> pendingTasks() throws ReflectiveOperationException {
    return (List<?>) rawField(coordinator, "pendingProofTasks");
  }

  private int checkpointAuditSize() throws Exception {
    Object checkpoints = rawField(coordinator, "checkpoints");
    return ((List<?>) checkpoints.getClass().getMethod("audit").invoke(checkpoints)).size();
  }

  private String authorityHash(String fieldName, String methodName) {
    try {
      Object value = rawField(coordinator, fieldName);
      return (String) value.getClass().getMethod(methodName).invoke(value);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private String claimHash() {
    try {
      Object proofControl = rawField(coordinator, "proofControl");
      Object claims = proofControl.getClass().getMethod("claims").invoke(proofControl);
      return CanonicalJson.stableHash(claims.getClass().getMethod("snapshot").invoke(claims));
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private String convergenceAuthorityHash() {
    ProofGraphConvergenceMonitor monitor =
        coordinatorField("proofGraphConvergence", ProofGraphConvergenceMonitor.class);
    var snapshot = monitor.snapshot();
    return CanonicalJson.stableHash(
        Map.of(
            "control_mode", snapshot.controlMode(),
            "focused_plan",
                snapshot.focusedRecoveryPlan() == null ? "" : snapshot.focusedRecoveryPlan(),
            "generic_expansion_leaks", snapshot.genericExpansionLeaks()));
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

  private static DesktopSolveCoordinator coordinator(DesktopNegativeKnowledgeTestHarness base)
      throws ReflectiveOperationException {
    return (DesktopSolveCoordinator) rawField(base, "coordinator");
  }

  private <T> T coordinatorField(String name, Class<T> type) {
    try {
      return type.cast(rawField(coordinator, name));
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static Object rawField(Object target, String name) throws ReflectiveOperationException {
    Class<?> type = target.getClass();
    while (type != null) {
      try {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
      } catch (NoSuchFieldException ignored) {
        type = type.getSuperclass();
      }
    }
    throw new NoSuchFieldException(name);
  }

  private static <T> T field(Object target, String name, Class<T> type)
      throws ReflectiveOperationException {
    return type.cast(rawField(target, name));
  }

  @SuppressWarnings("unchecked")
  private static Set<String> castSet(Object value) {
    return (Set<String>) value;
  }

  private static StrategyCard proposedStrategy(StrategyCard source, String suffix) {
    return new StrategyCard(
        null,
        "Reduce large primes in the global support family, case " + suffix,
        source.calculationChecks(),
        source.calculationEvidenceRefs(),
        source.computationHints(),
        "Study inclusion-minimal hitting sets for the global support family.",
        source.criticalClaims(),
        source.estimatedCost(),
        source.estimatedSuccess(),
        List.of("Prove the global large-prime support reduction, case " + suffix),
        "Search for a global minimal support containing a forbidden large prime.",
        "A trusted obstruction replaces the local object with a global one.",
        source.inspirationProposalId(),
        source.keyOriginalStep(),
        List.of(source.strategyId()),
        source.prerequisites(),
        "semantic-pivot-strategy-" + suffix,
        source.tags(),
        "Global support pivot " + suffix);
  }

  record State(
      int pivotRecords,
      int strategyEpochs,
      String activeStrategyId,
      String activePivotId,
      Set<String> activeObjects,
      int obligations,
      int canonicalOccurrences,
      int pendingTasks,
      int checkpointBranches,
      double globalDebt,
      String pivotHash,
      String rootHash,
      String negativeHash,
      String attemptHash,
      String claimHash,
      String researchHash,
      int facts,
      int negativeRecords,
      int externallyAdmittedFacts,
      String mainGoalStatus,
      String convergenceHash) {}
}
