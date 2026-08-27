package io.github.aililuola.mathproofmesh.proofgraph;

import io.github.aililuola.mathproofmesh.contract.BridgeTask;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BridgeBroker {
  private final BridgePolicy policy;
  private final ProofGraphStore graph;
  private final Set<List<String>> createdKeys = new LinkedHashSet<>();
  private final Set<String> completedTaskIds = new LinkedHashSet<>();
  private final List<BridgeTask> tasks = new ArrayList<>();

  public BridgeBroker(BridgePolicy policy, ProofGraphStore graph) {
    this.policy = java.util.Objects.requireNonNull(policy, "policy");
    this.graph = java.util.Objects.requireNonNull(graph, "graph");
  }

  public synchronized List<BridgeTask> detect(
      Collection<String> allowedFactIds,
      Collection<String> forbiddenNegativeIds,
      boolean budgetAvailable) {
    if (!policy.enabled() || !budgetAvailable || policy.maxTasksPerRound() == 0) {
      return List.of();
    }
    List<BridgeTask> created = new ArrayList<>();
    for (List<ProofObligation> group :
        graph.findSharedBottlenecks(policy.minRoutes())) {
      List<String> obligationIds =
          group.stream()
              .map(ProofObligation::obligationId)
              .sorted()
              .toList();
      if (obligationIds.size() < 2 || !createdKeys.add(obligationIds)) {
        continue;
      }
      Set<String> routes = new LinkedHashSet<>();
      group.forEach(item -> routes.addAll(item.routeIds()));
      if (routes.size() < policy.minRoutes()) {
        continue;
      }
      ProofObligation representative =
          group.stream()
              .max(
                  Comparator.comparingDouble(
                      item -> item.centrality() + item.priority()))
              .orElseThrow();
      String taskId =
          "bridge-"
              + CanonicalJson.stableHash(
                      Map.of(
                          "obligations", obligationIds,
                          "routes", routes.stream().sorted().toList()))
                  .substring(0, 24);
      BridgeTask task =
          new BridgeTask(
              List.copyOf(allowedFactIds),
              List.copyOf(forbiddenNegativeIds),
              representative.normalizedStatement(),
              obligationIds,
              Math.min(
                  1.0,
                  group.stream()
                          .mapToDouble(ProofObligation::priority)
                          .max()
                          .orElse(0.5)
                      + 0.1 * (routes.size() - 1)),
              routes.stream().sorted().toList(),
              taskId);
      tasks.add(task);
      created.add(task);
      graph.recordExternal(
          "bridge_task_created",
          taskId,
          Map.of("obligation_ids", String.join(",", obligationIds)));
      if (created.size() >= policy.maxTasksPerRound()) {
        break;
      }
    }
    return List.copyOf(created);
  }

  public synchronized List<String> acceptVerifiedResult(
      String taskId, MessageEnvelope message) {
    BridgeTask task =
        tasks.stream()
            .filter(item -> item.taskId().equals(taskId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown bridge task"));
    if (completedTaskIds.contains(taskId)) {
      return List.of();
    }
    if (message.verificationStatus() != ClaimStatus.VERIFIED
        || message.memoryTier() != MemoryTier.FACT) {
      throw new IllegalArgumentException(
          "bridge results require an independently verified fact");
    }
    graph.addClaimNode(message);
    List<String> closed = new ArrayList<>();
    String evidencedCanonicalTargetId =
        task.obligationIds().stream()
            .map(graph::canonicalTargetForObligation)
            .flatMap(java.util.Optional::stream)
            .filter(
                target ->
                    target.signature().normalizedStatement().equals(
                        MathTextSimilarity.normalize(message.normalizedStatement())))
            .map(CanonicalObligationRecord::canonicalTargetId)
            .findFirst()
            .orElse("");
    for (String obligationId : task.obligationIds()) {
      if (evidencedCanonicalTargetId.isBlank()
          || graph.canonicalTargetForObligation(obligationId).stream()
              .noneMatch(
                  target ->
                      target.canonicalTargetId().equals(evidencedCanonicalTargetId))) {
        continue;
      }
      ProofObligation obligation = graph.getObligation(obligationId);
      if ("closed".equals(obligation.status())) {
        continue;
      }
      graph.closeObligation(
          obligationId, message.messageId(), message.verificationConfidence());
      closed.add(obligationId);
    }
    completedTaskIds.add(taskId);
    graph.recordExternal(
        "bridge_task_completed",
        taskId,
        Map.of(
            "message_id", message.messageId(),
            "closed_obligation_ids", String.join(",", closed)));
    return List.copyOf(closed);
  }

  public synchronized List<BridgeTask> tasks() {
    return List.copyOf(tasks);
  }
}
