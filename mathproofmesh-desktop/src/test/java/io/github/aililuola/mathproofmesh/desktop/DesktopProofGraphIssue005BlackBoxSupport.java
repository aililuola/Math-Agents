package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.memory.LemmaMemory;
import io.github.aililuola.mathproofmesh.proofgraph.DeferredExpansionLedger;
import io.github.aililuola.mathproofmesh.proofgraph.FocusedRecoveryActionType;
import io.github.aililuola.mathproofmesh.proofgraph.ObligationCreationContext;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphConvergenceMonitor;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class DesktopProofGraphIssue005BlackBoxSupport {
  private DesktopProofGraphIssue005BlackBoxSupport() {}

  static ProofGraphStore graph(DesktopResearchCheckpointBlackBoxHarness harness) throws Exception {
    return (ProofGraphStore) field(coordinator(harness), "proofGraph");
  }

  static boolean enqueue(
      DesktopResearchCheckpointBlackBoxHarness harness,
      String source,
      String routeId,
      String obligationId,
      String action)
      throws Exception {
    Method method =
        DesktopSolveCoordinator.class.getDeclaredMethod(
            "enqueueProofTask", String.class, String.class, String.class, String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(coordinator(harness), source, routeId, obligationId, action);
  }

  static void sampleSchedulerRound(
      DesktopResearchCheckpointBlackBoxHarness harness, int round) throws Exception {
    Object coordinator = coordinator(harness);
    ((AtomicInteger) field(coordinator, "roundIndex")).set(round);
    Method method =
        DesktopSolveCoordinator.class.getDeclaredMethod("inspirationSnapshot", boolean.class);
    method.setAccessible(true);
    method.invoke(coordinator, false);
  }

  static int pendingTaskCount(DesktopResearchCheckpointBlackBoxHarness harness) throws Exception {
    return ((List<?>) field(coordinator(harness), "pendingProofTasks")).size();
  }

  static List<?> pendingTasks(DesktopResearchCheckpointBlackBoxHarness harness) throws Exception {
    return List.copyOf((List<?>) field(coordinator(harness), "pendingProofTasks"));
  }

  static void replaceGraph(
      DesktopResearchCheckpointBlackBoxHarness harness, ProofGraphStore graph) throws Exception {
    Field field = coordinator(harness).getClass().getDeclaredField("proofGraph");
    field.setAccessible(true);
    field.set(coordinator(harness), graph);
  }

  static boolean schedulePendingTask(DesktopResearchCheckpointBlackBoxHarness harness)
      throws Exception {
    Method method = DesktopSolveCoordinator.class.getDeclaredMethod("schedulePendingProofTask");
    method.setAccessible(true);
    return (boolean) method.invoke(coordinator(harness));
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> firstRouteContext(
      DesktopResearchCheckpointBlackBoxHarness harness) throws Exception {
    Object coordinator = coordinator(harness);
    List<Object> routes = (List<Object>) field(coordinator, "routes");
    Method method = DesktopSolveCoordinator.class.getDeclaredMethod("baseRouteContext", routes.getFirst().getClass());
    method.setAccessible(true);
    return java.util.Collections.unmodifiableMap(
        new LinkedHashMap<>((Map<String, Object>) method.invoke(coordinator, routes.getFirst())));
  }

  @SuppressWarnings("unchecked")
  static Map<String, String> firstRouteFocus(
      DesktopResearchCheckpointBlackBoxHarness harness) throws Exception {
    Object coordinator = coordinator(harness);
    List<Object> routes = (List<Object>) field(coordinator, "routes");
    Object route = routes.getFirst();
    Map<String, String> result = new LinkedHashMap<>();
    result.put("raw", String.valueOf(field(route, "focusObligationId")));
    result.put("canonical", String.valueOf(field(route, "focusedCanonicalTargetId")));
    result.put("family", String.valueOf(field(route, "focusedBottleneckFamilyId")));
    return Map.copyOf(result);
  }

  static int schedulableWorkItems(ProofGraphStore graph) throws Exception {
    try {
      Method method = ProofGraphStore.class.getMethod("coreOpenWorkItems");
      return ((List<?>) method.invoke(graph)).size();
    } catch (NoSuchMethodException ignored) {
      return (int)
          graph.obligations().stream()
              .filter(obligation -> "open".equals(obligation.status()))
              .count();
    }
  }

  static int focusedRecoveryEntries(DesktopResearchCheckpointBlackBoxHarness harness)
      throws Exception {
    Object monitor = optionalField(coordinator(harness), "proofGraphConvergence");
    if (monitor == null) {
      return 0;
    }
    Method method = monitor.getClass().getMethod("focusedRecoveryEntries");
    return (int) method.invoke(monitor);
  }

  static ProofGraphConvergenceMonitor convergence(
      DesktopResearchCheckpointBlackBoxHarness harness) throws Exception {
    return (ProofGraphConvergenceMonitor) field(coordinator(harness), "proofGraphConvergence");
  }

  static DeferredExpansionLedger deferredExpansions(
      DesktopResearchCheckpointBlackBoxHarness harness) throws Exception {
    return (DeferredExpansionLedger) field(coordinator(harness), "deferredExpansions");
  }

  static int routeCount(DesktopResearchCheckpointBlackBoxHarness harness) throws Exception {
    return ((List<?>) field(coordinator(harness), "routes")).size();
  }

  static int admittedStrategyCount(DesktopResearchCheckpointBlackBoxHarness harness)
      throws Exception {
    return ((List<?>) field(coordinator(harness), "admittedStrategies")).size();
  }

  static boolean widenRoutes(DesktopResearchCheckpointBlackBoxHarness harness)
      throws Exception {
    Method method = DesktopSolveCoordinator.class.getDeclaredMethod("widenRoutes");
    method.setAccessible(true);
    return (boolean) method.invoke(coordinator(harness));
  }

  static void addVerifiedLocalClaim(
      DesktopResearchCheckpointBlackBoxHarness harness, String claimId) throws Exception {
    LemmaMemory memory = (LemmaMemory) field(coordinator(harness), "lemmaMemory");
    memory.addMany(
        List.of(
            new ClaimCard(
                List.of(),
                claimId,
                "A locally reviewed obstruction is valid.",
                "",
                "bounded",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1.0d,
                null,
                null,
                null,
                "A locally reviewed obstruction is valid.",
                ClaimStatus.VERIFIED,
                List.of("legacy_verified_fact"),
                1.0d)));
  }

  static void setRound(DesktopResearchCheckpointBlackBoxHarness harness, int round)
      throws Exception {
    ((AtomicInteger) field(coordinator(harness), "roundIndex")).set(round);
  }

  static int reconsiderDeferredExpansions(DesktopResearchCheckpointBlackBoxHarness harness)
      throws Exception {
    Method method =
        DesktopSolveCoordinator.class.getDeclaredMethod("reconsiderDeferredExpansions");
    method.setAccessible(true);
    return (int) method.invoke(coordinator(harness));
  }

  static void injectDeferredReactivationFailure(
      DesktopResearchCheckpointBlackBoxHarness harness,
      DeferredReactivationFailurePoint point)
      throws Exception {
    Method method =
        DesktopSolveCoordinator.class.getDeclaredMethod(
            "setDeferredReactivationFailurePointForTest",
            DeferredReactivationFailurePoint.class);
    method.setAccessible(true);
    method.invoke(coordinator(harness), point);
  }

  static int canonicalTaskLeaseCount(DesktopResearchCheckpointBlackBoxHarness harness)
      throws Exception {
    return graph(harness).canonicalizationSnapshot().taskLeaseKeys().size();
  }

  static boolean addControlledObligation(
      DesktopResearchCheckpointBlackBoxHarness harness,
      ProofObligation obligation,
      ObligationCreationContext context,
      FocusedRecoveryActionType actionType)
      throws Exception {
    Method method =
        DesktopSolveCoordinator.class.getDeclaredMethod(
            "addControlledObligation",
            ProofObligation.class,
            ObligationCreationContext.class,
            FocusedRecoveryActionType.class);
    method.setAccessible(true);
    Object write = method.invoke(coordinator(harness), obligation, context, actionType);
    Method decisionMethod = write.getClass().getDeclaredMethod("decision");
    decisionMethod.setAccessible(true);
    Object decision = decisionMethod.invoke(write);
    Method allowedMethod = decision.getClass().getMethod("allowed");
    return (boolean) allowedMethod.invoke(decision);
  }

  static void refuteFirstCanonicalTarget(DesktopResearchCheckpointBlackBoxHarness harness)
      throws Exception {
    ProofGraphStore graph = graph(harness);
    String canonicalId =
        graph.canonicalOpenTargets().stream()
            .filter(target -> !"MAIN_GOAL".equals(target.signature().kind().name()))
            .findFirst()
            .orElseThrow()
            .canonicalTargetId();
    Map<String, String> obligationByOccurrence = new LinkedHashMap<>();
    graph.rawObligationOccurrences().forEach(
        occurrence ->
            obligationByOccurrence.put(
                occurrence.occurrenceId(), occurrence.obligationId()));
    graph.allCanonicalTargets().stream()
        .filter(target -> target.canonicalTargetId().equals(canonicalId))
        .findFirst()
        .orElseThrow()
        .occurrenceIds()
        .stream()
        .map(obligationByOccurrence::get)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .forEach(obligationId -> graph.refuteObligation(obligationId, null));
  }

  static void closeFirstCanonicalTarget(DesktopResearchCheckpointBlackBoxHarness harness)
      throws Exception {
    ProofGraphStore graph = graph(harness);
    var target =
        graph.canonicalOpenTargets().stream()
            .filter(item -> !"MAIN_GOAL".equals(item.signature().kind().name()))
            .filter(
                item ->
                    item.occurrenceIds().stream()
                        .map(
                            occurrenceId ->
                                graph.rawObligationOccurrences().stream()
                                    .filter(occurrence -> occurrence.occurrenceId().equals(occurrenceId))
                                    .findFirst()
                                    .map(occurrence -> graph.getObligation(occurrence.obligationId()))
                                    .orElse(null))
                        .filter(java.util.Objects::nonNull)
                        .allMatch(obligation -> "open".equals(obligation.status())))
            .findFirst()
            .orElseThrow();
    String factId = "focused-recovery-closing-fact";
    if (graph.claimNodes().stream().noneMatch(message -> message.messageId().equals(factId))) {
      graph.addClaimNode(verifiedFact(factId, target.signature().normalizedStatement()));
    }
    Map<String, String> obligationByOccurrence = new LinkedHashMap<>();
    graph.rawObligationOccurrences().forEach(
        occurrence ->
            obligationByOccurrence.put(occurrence.occurrenceId(), occurrence.obligationId()));
    target.occurrenceIds().stream()
        .map(obligationByOccurrence::get)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .forEach(obligationId -> graph.closeObligation(obligationId, factId, 1.0d));
  }

  static void closeFirstActiveCanonicalTargetExcept(
      DesktopResearchCheckpointBlackBoxHarness harness, String excludedCanonicalTargetId)
      throws Exception {
    ProofGraphStore graph = graph(harness);
    var target =
        graph.activeCanonicalOpenTargets().stream()
            .filter(item -> !"MAIN_GOAL".equals(item.signature().kind().name()))
            .filter(item -> !item.canonicalTargetId().equals(excludedCanonicalTargetId))
            .findFirst()
            .orElseThrow();
    String factId = "capacity-release-" + target.canonicalTargetId();
    if (graph.claimNodes().stream().noneMatch(message -> message.messageId().equals(factId))) {
      graph.addClaimNode(verifiedFact(factId, target.signature().normalizedStatement()));
    }
    Map<String, String> obligationByOccurrence = new LinkedHashMap<>();
    graph.rawObligationOccurrences().forEach(
        occurrence ->
            obligationByOccurrence.put(occurrence.occurrenceId(), occurrence.obligationId()));
    target.occurrenceIds().stream()
        .map(obligationByOccurrence::get)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .forEach(obligationId -> graph.closeObligation(obligationId, factId, 1.0d));
  }

  static String canonicalizationHash(DesktopResearchCheckpointBlackBoxHarness harness)
      throws Exception {
    return graph(harness).canonicalizationHash();
  }

  static String convergenceHash(DesktopResearchCheckpointBlackBoxHarness harness)
      throws Exception {
    return convergence(harness).stableHash();
  }

  static String deferredHash(DesktopResearchCheckpointBlackBoxHarness harness)
      throws Exception {
    return deferredExpansions(harness).stableHash();
  }

  static void enterFocusedRecovery(DesktopResearchCheckpointBlackBoxHarness harness)
      throws Exception {
    ProofGraphStore graph = graph(harness);
    for (int round = 0; round < 2; round++) {
      graph.addObligation(
          obligation(
              "focused-entry-" + round,
              "route-1",
              "Derive the same focused recovery obstruction.",
              "derive the same focused recovery obstruction",
              "focused-entry-family",
              "focused-entry-plan-" + round));
      sampleSchedulerRound(harness, round);
    }
  }

  static String controlMode(DesktopResearchCheckpointBlackBoxHarness harness) throws Exception {
    Object monitor = optionalField(coordinator(harness), "proofGraphConvergence");
    if (monitor == null) {
      return "NORMAL_EXPANSION";
    }
    Method method = monitor.getClass().getMethod("controlMode");
    return String.valueOf(method.invoke(monitor));
  }

  static ProofObligation obligation(
      String id,
      String routeId,
      String statement,
      String normalizedStatement,
      String firstErrorFingerprint,
      String dependencyPlanLabel) {
    return new ProofObligation(
        List.of(),
        0.7d,
        "",
        List.of(),
        List.of(ContractObjectMapper.toTree(Map.of("dependency_plan", dependencyPlanLabel))),
        List.of(),
        firstErrorFingerprint,
        ObligationKind.LEMMA,
        normalizedStatement,
        id,
        0.8d,
        DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH,
        List.of(),
        List.of(routeId),
        statement,
        "open");
  }

  private static MessageEnvelope verifiedFact(String id, String statement) {
    return new MessageEnvelope(
        List.of(),
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
        DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH,
        List.of(),
        null,
        0,
        "1",
        List.of(),
        "issue-005-test-authority",
        RouteRole.PROVER,
        "route-1",
        statement,
        List.of(),
        2,
        List.of(),
        0.99d,
        ClaimStatus.VERIFIED);
  }

  private static Object coordinator(DesktopResearchCheckpointBlackBoxHarness harness)
      throws Exception {
    return field(harness, "coordinator");
  }

  private static Object optionalField(Object target, String name) throws IllegalAccessException {
    try {
      return field(target, name);
    } catch (NoSuchFieldException ignored) {
      return null;
    }
  }

  private static Object field(Object target, String name)
      throws NoSuchFieldException, IllegalAccessException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }
}
