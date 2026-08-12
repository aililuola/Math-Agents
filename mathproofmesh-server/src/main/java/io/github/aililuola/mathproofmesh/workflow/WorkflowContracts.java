package io.github.aililuola.mathproofmesh.workflow;

import java.util.List;

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
      int maximumGenerations) {
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
    }

    public SolveRequest nextGeneration() {
      return new SolveRequest(
          runId, profile, routeCount, budget, generation + 1, maximumGenerations);
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
      String routeId, String checkpointId, List<String> verifiedClaimIds, boolean accepted) {
    public RouteResult {
      routeId = required(routeId, "routeId");
      checkpointId = required(checkpointId, "checkpointId");
      verifiedClaimIds =
          verifiedClaimIds == null ? List.of() : List.copyOf(verifiedClaimIds);
    }

    @Override
    public List<String> verifiedClaimIds() {
      return List.copyOf(verifiedClaimIds);
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

  public record BudgetSummary(int availableCalls, int acceptedUpdates) {}

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
      boolean applied) {
    public ActivityResult {
      actionKey = required(actionKey, "actionKey");
      outputRef = required(outputRef, "outputRef");
      checkpointId = clean(checkpointId);
      claimIds = claimIds == null ? List.of() : List.copyOf(claimIds);
    }

    @Override
    public List<String> claimIds() {
      return List.copyOf(claimIds);
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
}
