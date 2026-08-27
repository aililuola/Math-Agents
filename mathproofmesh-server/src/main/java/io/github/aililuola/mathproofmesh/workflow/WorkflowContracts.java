package io.github.aililuola.mathproofmesh.workflow;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.orchestration.BudgetUsageTotals;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Stable, bounded payloads carried in Temporal history. */
public final class WorkflowContracts {
  public static final String TASK_QUEUE = "mathproofmesh-phase13";

  private WorkflowContracts() {}

  public record SolveRequest(
      String runId,
      String profile,
      int routeCount,
      int budget,
      int generation,
      int maximumGenerations,
      WorkflowBudgetCheckpoint budgetCheckpoint) {
    public SolveRequest {
      runId = required(runId, "runId");
      profile = required(profile, "profile");
      if (routeCount <= 0
          || routeCount > 16
          || budget < 0
          || generation < 0
          || maximumGenerations < 0) {
        throw new IllegalArgumentException("invalid bounded workflow request");
      }
      budgetCheckpoint =
          budgetCheckpoint == null ? WorkflowBudgetCheckpoint.empty() : budgetCheckpoint;
    }

    public SolveRequest(
        String runId,
        String profile,
        int routeCount,
        int budget,
        int generation,
        int maximumGenerations) {
      this(
          runId,
          profile,
          routeCount,
          budget,
          generation,
          maximumGenerations,
          WorkflowBudgetCheckpoint.empty());
    }

    public SolveRequest nextGeneration(WorkflowBudgetCheckpoint checkpoint) {
      return new SolveRequest(
          runId, profile, routeCount, budget, generation + 1, maximumGenerations, checkpoint);
    }
  }

  public record SolveResult(SolveState state, VerificationBundle verification) {}

  public record RouteRequest(
      String runId, String routeId, String strategyId, String checkpointId, int budget) {
    public RouteRequest {
      runId = required(runId, "runId");
      routeId = required(routeId, "routeId");
      strategyId = required(strategyId, "strategyId");
      checkpointId = clean(checkpointId);
      if (budget < 0) {
        throw new IllegalArgumentException("budget must be nonnegative");
      }
    }
  }

  public record RouteResult(
      String routeId,
      String checkpointId,
      List<String> verifiedClaimIds,
      boolean accepted,
      Map<String, BudgetUsageTotals> settledUsage) {
    public RouteResult {
      routeId = required(routeId, "routeId");
      checkpointId = required(checkpointId, "checkpointId");
      verifiedClaimIds =
          verifiedClaimIds == null ? List.of() : List.copyOf(verifiedClaimIds);
      settledUsage = immutableUsage(settledUsage);
    }

    public RouteResult(
        String routeId,
        String checkpointId,
        List<String> verifiedClaimIds,
        boolean accepted) {
      this(routeId, checkpointId, verifiedClaimIds, accepted, Map.of());
    }

    @Override
    public List<String> verifiedClaimIds() {
      return List.copyOf(verifiedClaimIds);
    }

    @Override
    public Map<String, BudgetUsageTotals> settledUsage() {
      return Collections.unmodifiableMap(new LinkedHashMap<>(settledUsage));
    }
  }

  public record CommandSignal(String commandId) {
    public CommandSignal {
      commandId = required(commandId, "commandId");
    }
  }

  public record WakeRouteSignal(String commandId, String routeId) {
    public WakeRouteSignal {
      commandId = required(commandId, "commandId");
      routeId = required(routeId, "routeId");
    }
  }

  public record BudgetUpdate(String updateId, int additionalCalls) {
    public BudgetUpdate {
      updateId = required(updateId, "updateId");
    }
  }

  public record DirectiveUpdate(String updateId, String directiveId, String auditRef) {
    public DirectiveUpdate {
      updateId = required(updateId, "updateId");
      directiveId = required(directiveId, "directiveId");
      auditRef = required(auditRef, "auditRef");
    }
  }

  public record DirectiveResult(String directiveId, boolean accepted, String reason) {}

  public record RouteSummary(List<String> completedRouteIds, List<String> wokenRouteIds) {
    public RouteSummary {
      completedRouteIds =
          completedRouteIds == null ? List.of() : List.copyOf(completedRouteIds);
      wokenRouteIds = wokenRouteIds == null ? List.of() : List.copyOf(wokenRouteIds);
    }

    @Override
    public List<String> completedRouteIds() {
      return List.copyOf(completedRouteIds);
    }

    @Override
    public List<String> wokenRouteIds() {
      return List.copyOf(wokenRouteIds);
    }
  }

  public record BudgetSummary(
      int availableCalls,
      int acceptedUpdates,
      BudgetUsageTotals committedUsage,
      String budgetStateHash,
      String budgetDecisionHash) {
    public BudgetSummary {
      committedUsage = committedUsage == null ? BudgetUsageTotals.zero() : committedUsage;
      budgetStateHash = clean(budgetStateHash);
      budgetDecisionHash = clean(budgetDecisionHash);
    }

    public BudgetSummary(int availableCalls, int acceptedUpdates) {
      this(availableCalls, acceptedUpdates, BudgetUsageTotals.zero(), "", "");
    }
  }

  public record ActivityCommand(
      String runId,
      String routeId,
      String checkpointId,
      String actionKey,
      String inputRef) {
    public ActivityCommand {
      runId = required(runId, "runId");
      routeId = clean(routeId);
      checkpointId = clean(checkpointId);
      actionKey = required(actionKey, "actionKey");
      inputRef = clean(inputRef);
    }
  }

  public record ActivityResult(
      String actionKey,
      String outputRef,
      String checkpointId,
      List<String> claimIds,
      boolean applied,
      BudgetUsageTotals usage) {
    public ActivityResult {
      actionKey = required(actionKey, "actionKey");
      outputRef = required(outputRef, "outputRef");
      checkpointId = clean(checkpointId);
      claimIds = claimIds == null ? List.of() : List.copyOf(claimIds);
      usage = usage == null ? BudgetUsageTotals.zero() : usage;
    }

    public ActivityResult(
        String actionKey,
        String outputRef,
        String checkpointId,
        List<String> claimIds,
        boolean applied) {
      this(actionKey, outputRef, checkpointId, claimIds, applied, BudgetUsageTotals.zero());
    }

    @Override
    public List<String> claimIds() {
      return List.copyOf(claimIds);
    }
  }

  /** Continue-as-new-safe, exactly-once usage sidecar for deterministic workflow replay. */
  public record WorkflowBudgetCheckpoint(
      Map<String, BudgetUsageTotals> settledUsage,
      int zeroGainRounds,
      String stateHash) {
    public WorkflowBudgetCheckpoint {
      settledUsage = immutableUsage(settledUsage);
      if (zeroGainRounds < 0) {
        throw new IllegalArgumentException("zeroGainRounds must not be negative");
      }
      String expected =
          CanonicalJson.stableHash(
              Map.of("settled_usage", settledUsage, "zero_gain_rounds", zeroGainRounds));
      stateHash = clean(stateHash);
      if (stateHash.isEmpty()) {
        stateHash = expected;
      } else if (!sameHash(stateHash, expected)) {
        throw new IllegalArgumentException("workflow budget checkpoint hash mismatch");
      }
    }

    public static WorkflowBudgetCheckpoint empty() {
      return new WorkflowBudgetCheckpoint(Map.of(), 0, "");
    }

    @Override
    public Map<String, BudgetUsageTotals> settledUsage() {
      return Collections.unmodifiableMap(new LinkedHashMap<>(settledUsage));
    }
  }

  public record SafeProgress(String actionKey, String state) {
    public SafeProgress {
      actionKey = required(actionKey, "actionKey");
      state = required(state, "state");
    }
  }

  private static String required(String value, String field) {
    String result = clean(value);
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }

  private static String clean(String value) {
    return value == null ? "" : value.strip();
  }

  private static boolean sameHash(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }

  private static Map<String, BudgetUsageTotals> immutableUsage(
      Map<String, BudgetUsageTotals> values) {
    Map<String, BudgetUsageTotals> ordered = new TreeMap<>();
    if (values != null) {
      values.forEach(
          (key, usage) -> {
            String normalized = required(key, "usage action key");
            if (usage == null) {
              throw new IllegalArgumentException("usage total is required");
            }
            ordered.put(normalized, usage);
          });
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
  }
}
