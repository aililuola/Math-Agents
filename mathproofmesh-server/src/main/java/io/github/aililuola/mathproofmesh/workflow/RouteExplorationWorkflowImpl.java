package io.github.aililuola.mathproofmesh.workflow;

import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.ActivityCommand;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.ActivityResult;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.RouteRequest;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.RouteResult;
import io.temporal.workflow.Workflow;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic continuation/verification scheduler for one route. */
public final class RouteExplorationWorkflowImpl implements RouteExplorationWorkflow {
  private final WorkflowActivities activities =
      Workflow.newActivityStub(WorkflowActivities.class, TemporalOptions.activities());

  @Override
  public RouteResult explore(RouteRequest request) {
    if (request.budget() < 1) {
      String checkpoint =
          request.checkpointId().isBlank()
              ? "checkpoint-" + request.runId() + "-budget-rejected"
              : request.checkpointId();
      return new RouteResult(request.routeId(), checkpoint, java.util.List.of(), false, Map.of());
    }
    ActivityResult agent =
        activities.agentCall(
            command(request, "agent-call", request.checkpointId(), request.strategyId()));
    ActivityResult compute =
        activities.compute(
            command(request, "compute", agent.checkpointId(), agent.outputRef()));
    ActivityResult verified =
        activities.verify(
            command(request, "verify", compute.checkpointId(), compute.outputRef()));
    ActivityResult persisted =
        activities.persist(
            command(request, "persist", verified.checkpointId(), verified.outputRef()));
    Map<String, io.github.aililuola.mathproofmesh.orchestration.BudgetUsageTotals> usage =
        new LinkedHashMap<>();
    for (ActivityResult result : java.util.List.of(agent, compute, verified, persisted)) {
      usage.put(result.actionKey(), result.usage());
    }
    return new RouteResult(
        request.routeId(),
        persisted.checkpointId(),
        verified.claimIds(),
        verified.applied(),
        usage);
  }

  private static ActivityCommand command(
      RouteRequest request, String action, String checkpointId, String inputRef) {
    return new ActivityCommand(
        request.runId(),
        request.routeId(),
        checkpointId,
        request.runId() + ":" + request.routeId() + ":" + action,
        inputRef);
  }
}
