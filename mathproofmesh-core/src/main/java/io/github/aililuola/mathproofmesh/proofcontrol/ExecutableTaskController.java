package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Persistent task lifecycle with explicit executability and wake conditions. */
public final class ExecutableTaskController {
  public enum TaskKind {
    COUNTERMODEL,
    FALSIFICATION,
    ROUTE_UPDATE,
    INSPIRATION_REVIEW,
    META_PIVOT_STEP,
    NORMALIZE_NODES,
    BLUEPRINT_REVIEW,
    REPAIR_DIRECT_TARGET,
    EDGE_REVIEW,
    GENERATE_PLAN,
    BATCH_REPAIR,
    ASSUMPTION_CHALLENGER
  }

  public record Snapshot(
      String id,
      TaskKind kind,
      ProofControlModels.TaskStatus status,
      List<String> targetClaimIds,
      List<String> targetObligationIds,
      List<String> routeIds,
      String assignedAgentId,
      String registeredHandler,
      String typedContractRef,
      List<ProofControlModels.WakeCondition> wakeConditions,
      int createdRound,
      int lastTransitionRound,
      List<String> resultRefs,
      String terminalReason,
      boolean verifiesTargetClaim,
      List<String> transitionHistory) {
    public Snapshot {
      targetClaimIds = List.copyOf(targetClaimIds);
      targetObligationIds = List.copyOf(targetObligationIds);
      routeIds = List.copyOf(routeIds);
      wakeConditions = List.copyOf(wakeConditions);
      resultRefs = List.copyOf(resultRefs);
      transitionHistory = List.copyOf(transitionHistory);
    }

    public boolean terminal() {
      return switch (status) {
        case COMPLETED, INCONCLUSIVE, FAILED, EXPIRED -> true;
        default -> false;
      };
    }

    public boolean executable() {
      return registeredHandler != null || assignedAgentId != null;
    }
  }

  private final Map<String, MutableTask> tasks = new LinkedHashMap<>();

  public Snapshot create(
      TaskKind kind,
      List<String> targetClaimIds,
      List<String> targetObligationIds,
      List<String> routeIds,
      String registeredHandler,
      String assignedAgentId,
      String typedContractRef,
      boolean contractNeedsRewrite,
      int currentRound) {
    String id =
        "task_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "kind", kind.name(),
                        "claims", sorted(targetClaimIds),
                        "obligations", sorted(targetObligationIds),
                        "routes", sorted(routeIds),
                        "contract", typedContractRef == null ? "" : typedContractRef))
                .substring(0, 20);
    MutableTask existing = tasks.get(id);
    if (existing != null) {
      return existing.snapshot();
    }
    ProofControlModels.TaskStatus status;
    List<ProofControlModels.WakeCondition> wake = new ArrayList<>();
    if (contractNeedsRewrite) {
      status = ProofControlModels.TaskStatus.NEEDS_REWRITE;
      wake.add(
          wake(id, ProofControlModels.WakeConditionKind.TASK_RECOMPILED,
              typedContractRef, currentRound));
    } else if (registeredHandler != null && !registeredHandler.isBlank()) {
      status = ProofControlModels.TaskStatus.READY;
    } else if (assignedAgentId != null && !assignedAgentId.isBlank()) {
      status = ProofControlModels.TaskStatus.ASSIGNED;
    } else {
      status = ProofControlModels.TaskStatus.DEFERRED;
      wake.add(
          wake(id, ProofControlModels.WakeConditionKind.PROVIDER_AVAILABLE,
              kind.name().toLowerCase(java.util.Locale.ROOT), currentRound));
    }
    MutableTask task =
        new MutableTask(
            id,
            kind,
            status,
            sorted(targetClaimIds),
            sorted(targetObligationIds),
            sorted(routeIds),
            blank(assignedAgentId),
            blank(registeredHandler),
            blank(typedContractRef),
            wake,
            currentRound);
    tasks.put(id, task);
    return task.snapshot();
  }

  public Snapshot markRunning(String taskId, int round) {
    MutableTask task = task(taskId);
    if (task.status == ProofControlModels.TaskStatus.RUNNING
        || task.status == ProofControlModels.TaskStatus.COMPLETED) {
      return task.snapshot();
    }
    if (!(task.status == ProofControlModels.TaskStatus.READY
        || task.status == ProofControlModels.TaskStatus.ASSIGNED)) {
      throw new IllegalStateException("task is not executable");
    }
    task.transition(ProofControlModels.TaskStatus.RUNNING, round, "execution started");
    return task.snapshot();
  }

  public Snapshot complete(
      String taskId,
      List<String> resultRefs,
      boolean counterexampleFound,
      int round) {
    MutableTask task = task(taskId);
    if (task.status == ProofControlModels.TaskStatus.COMPLETED) {
      return task.snapshot();
    }
    if (task.status != ProofControlModels.TaskStatus.RUNNING) {
      throw new IllegalStateException("only a running task can complete");
    }
    task.resultRefs = sorted(resultRefs);
    task.verifiesTargetClaim = false;
    task.terminalReason =
        counterexampleFound
            ? "counterexample independently reviewable"
            : "bounded non-refutation does not verify the target";
    task.transition(
        counterexampleFound
            ? ProofControlModels.TaskStatus.COMPLETED
            : ProofControlModels.TaskStatus.INCONCLUSIVE,
        round,
        task.terminalReason);
    return task.snapshot();
  }

  public Snapshot defer(
      String taskId,
      ProofControlModels.WakeCondition condition,
      String reason,
      int round) {
    MutableTask task = task(taskId);
    if (task.snapshot().terminal()) {
      return task.snapshot();
    }
    task.wakeConditions.add(Objects.requireNonNull(condition, "condition"));
    task.terminalReason = ProofControlModels.required(reason, "reason");
    task.transition(ProofControlModels.TaskStatus.DEFERRED, round, reason);
    return task.snapshot();
  }

  public List<Snapshot> evaluateWakeConditions(
      ProofControlModels.WakeConditionKind kind, String targetId, int round) {
    List<Snapshot> awakened = new ArrayList<>();
    for (MutableTask task : tasks.values()) {
      if (task.status != ProofControlModels.TaskStatus.DEFERRED
          && task.status != ProofControlModels.TaskStatus.NEEDS_REWRITE
          && task.status != ProofControlModels.TaskStatus.BLOCKED) {
        continue;
      }
      boolean matched = false;
      List<ProofControlModels.WakeCondition> updated = new ArrayList<>();
      for (ProofControlModels.WakeCondition condition : task.wakeConditions) {
        boolean satisfies =
            !condition.satisfied()
                && condition.kind() == kind
                && condition.earliestRound() <= round
                && (condition.targetId().isEmpty()
                    || condition.targetId().equals(targetId));
        updated.add(
            satisfies
                ? new ProofControlModels.WakeCondition(
                    condition.id(),
                    condition.kind(),
                    condition.targetId(),
                    condition.earliestRound(),
                    true)
                : condition);
        matched |= satisfies;
      }
      task.wakeConditions.clear();
      task.wakeConditions.addAll(updated);
      if (matched) {
        task.transition(
            task.registeredHandler != null
                ? ProofControlModels.TaskStatus.READY
                : task.assignedAgentId != null
                    ? ProofControlModels.TaskStatus.ASSIGNED
                    : ProofControlModels.TaskStatus.CREATED,
            round,
            "wake condition satisfied");
        awakened.add(task.snapshot());
      }
    }
    return List.copyOf(awakened);
  }

  public boolean lifecycleClosed() {
    return tasks.values().stream()
        .allMatch(
            task ->
                task.snapshot().terminal()
                    || task.snapshot().executable()
                    || task.wakeConditions.stream().anyMatch(value -> !value.satisfied()));
  }

  public Snapshot get(String taskId) {
    return task(taskId).snapshot();
  }

  public List<Snapshot> snapshots() {
    return tasks.values().stream()
        .map(MutableTask::snapshot)
        .sorted(Comparator.comparing(Snapshot::id))
        .toList();
  }

  private MutableTask task(String id) {
    MutableTask task = tasks.get(id);
    if (task == null) {
      throw new IllegalArgumentException("unknown executable task: " + id);
    }
    return task;
  }

  private static ProofControlModels.WakeCondition wake(
      String taskId,
      ProofControlModels.WakeConditionKind kind,
      String target,
      int round) {
    return new ProofControlModels.WakeCondition(
        "wake_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "task", taskId,
                        "kind", kind.name(),
                        "target", target == null ? "" : target))
                .substring(0, 20),
        kind,
        target,
        round,
        false);
  }

  private static List<String> sorted(List<String> values) {
    return values == null
        ? List.of()
        : values.stream().filter(Objects::nonNull).map(String::strip)
            .filter(value -> !value.isEmpty()).distinct().sorted().toList();
  }

  private static String blank(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static final class MutableTask {
    private final String id;
    private final TaskKind kind;
    private ProofControlModels.TaskStatus status;
    private final List<String> targetClaimIds;
    private final List<String> targetObligationIds;
    private final List<String> routeIds;
    private final String assignedAgentId;
    private final String registeredHandler;
    private final String typedContractRef;
    private final List<ProofControlModels.WakeCondition> wakeConditions;
    private final int createdRound;
    private int lastTransitionRound;
    private List<String> resultRefs = List.of();
    private String terminalReason;
    private boolean verifiesTargetClaim;
    private final List<String> history = new ArrayList<>();

    private MutableTask(
        String id,
        TaskKind kind,
        ProofControlModels.TaskStatus status,
        List<String> targetClaimIds,
        List<String> targetObligationIds,
        List<String> routeIds,
        String assignedAgentId,
        String registeredHandler,
        String typedContractRef,
        List<ProofControlModels.WakeCondition> wakeConditions,
        int round) {
      this.id = id;
      this.kind = kind;
      this.status = status;
      this.targetClaimIds = targetClaimIds;
      this.targetObligationIds = targetObligationIds;
      this.routeIds = routeIds;
      this.assignedAgentId = assignedAgentId;
      this.registeredHandler = registeredHandler;
      this.typedContractRef = typedContractRef;
      this.wakeConditions = new ArrayList<>(wakeConditions);
      this.createdRound = round;
      this.lastTransitionRound = round;
      this.history.add("round=" + round + ":created->" + status.name().toLowerCase(java.util.Locale.ROOT));
    }

    private void transition(
        ProofControlModels.TaskStatus next, int round, String reason) {
      history.add(
          "round="
              + round
              + ":"
              + status.name().toLowerCase(java.util.Locale.ROOT)
              + "->"
              + next.name().toLowerCase(java.util.Locale.ROOT)
              + ":"
              + ProofIdentity.normalizeText(reason));
      status = next;
      lastTransitionRound = round;
    }

    private Snapshot snapshot() {
      return new Snapshot(
          id,
          kind,
          status,
          targetClaimIds,
          targetObligationIds,
          routeIds,
          assignedAgentId,
          registeredHandler,
          typedContractRef,
          wakeConditions,
          createdRound,
          lastTransitionRound,
          resultRefs,
          terminalReason,
          verifiesTargetClaim,
          history);
    }
  }
}
