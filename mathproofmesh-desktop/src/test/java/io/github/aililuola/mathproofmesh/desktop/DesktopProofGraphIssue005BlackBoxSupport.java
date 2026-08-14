package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
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
    return Map.copyOf((Map<String, Object>) method.invoke(coordinator, routes.getFirst()));
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
