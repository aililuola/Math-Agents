package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.InspirationAssignmentPlan;
import io.github.aililuola.mathproofmesh.contract.InspirationContextMode;
import io.github.aililuola.mathproofmesh.contract.InspirationProposalAssignment;
import io.github.aililuola.mathproofmesh.contract.InspirationTask;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a bounded, distinct proposer population from observable agent state. */
public final class InspirationAssignmentPlanner {
  private final InspirationPolicy policy;

  public InspirationAssignmentPlanner(InspirationPolicy policy) {
    this.policy = java.util.Objects.requireNonNull(policy, "policy");
  }

  public InspirationAssignmentPlan plan(
      InspirationTask task,
      String proposerRole,
      List<AgentCandidate> pool,
      int roundIndex,
      List<String> specialtyHints,
      boolean allowGeneralists,
      Integer requestedProposals) {
    int requested =
        Math.min(
            requestedProposals == null
                ? policy.limits().maxProposalsPerTask()
                : Math.max(0, requestedProposals),
            Math.min(task.maxProposals(), policy.limits().maxProposalsPerTask()));
    Map<String, AgentCandidate> eligibleById = new LinkedHashMap<>();
    for (AgentCandidate candidate : pool == null ? List.<AgentCandidate>of() : pool) {
      if (candidate.coolingDown()) {
        continue;
      }
      boolean specialist =
          candidate.roles().contains(proposerRole) || candidate.roles().contains("general");
      boolean generalist =
          allowGeneralists
              && candidate.roles().stream()
                  .anyMatch(
                      role ->
                          Set.of("planner", "explorer", "meta_reviewer", "general")
                              .contains(role));
      if (specialist || generalist) {
        eligibleById.putIfAbsent(candidate.id(), candidate);
      }
    }
    List<AgentCandidate> eligible =
        eligibleById.values().stream()
            .sorted(
                Comparator.comparing(
                        (AgentCandidate item) ->
                            !(item.roles().contains(proposerRole)
                                || item.roles().contains("general")))
                    .thenComparing(
                        Comparator.comparingDouble(AgentCandidate::capabilityScore).reversed())
                    .thenComparing(
                        Comparator.comparingDouble(AgentCandidate::specialtyScore).reversed())
                    .thenComparing(
                        Comparator.comparingDouble(AgentCandidate::trustScore).reversed())
                    .thenComparingInt(AgentCandidate::activeCalls)
                    .thenComparingInt(AgentCandidate::totalCalls)
                    .thenComparing(AgentCandidate::id))
            .toList();
    if (requested == 0 || eligible.isEmpty()) {
      return new InspirationAssignmentPlan(
          List.of(),
          requested == 0
              ? "task requested no active proposals"
              : "no available specialist or configured generalist for " + proposerRole,
          eligible.stream().map(AgentCandidate::id).toList(),
          task.mechanism(),
          requested,
          roundIndex,
          task.taskId());
    }
    int assignmentCount =
        eligible.size() == 1
            ? Math.min(requested, policy.limits().maxSingleAgentProposals())
            : Math.min(requested, eligible.size());
    List<AgentCandidate> selected = new ArrayList<>();
    for (int index = 0; index < assignmentCount; index++) {
      selected.add(eligible.get(Math.min(index, eligible.size() - 1)));
    }
    int coldCount =
        assignmentCount > 1
            ? Math.min(policy.limits().coldContextProposals(), assignmentCount - 1)
            : 0;
    int coldStart = assignmentCount - coldCount;
    List<InspirationProposalAssignment> assignments = new ArrayList<>();
    for (int slot = 0; slot < selected.size(); slot++) {
      AgentCandidate candidate = selected.get(slot);
      boolean specialist =
          candidate.roles().contains(proposerRole) || candidate.roles().contains("general");
      assignments.add(
          new InspirationProposalAssignment(
              slot >= coldStart ? InspirationContextMode.COLD : InspirationContextMode.WARM,
              task.mechanism(),
              slot,
              candidate.id(),
              proposerRole,
              specialist,
              task.taskId()));
    }
    return new InspirationAssignmentPlan(
        assignments,
        null,
        eligible.stream().map(AgentCandidate::id).toList(),
        task.mechanism(),
        requested,
        roundIndex,
        task.taskId());
  }

  public record AgentCandidate(
      String id,
      Set<String> roles,
      double capabilityScore,
      double specialtyScore,
      double trustScore,
      int activeCalls,
      int totalCalls,
      boolean coolingDown) {
    public AgentCandidate {
      id = required(id, "id");
      roles = roles == null ? Set.of() : Set.copyOf(roles);
      if (!Double.isFinite(capabilityScore)
          || !Double.isFinite(specialtyScore)
          || !Double.isFinite(trustScore)
          || activeCalls < 0
          || totalCalls < 0) {
        throw new IllegalArgumentException("invalid agent candidate metrics");
      }
    }
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
