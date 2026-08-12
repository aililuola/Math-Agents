package io.github.aililuola.mathproofmesh.workflow;

import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.RouteRequest;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.RouteResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** The only route-level child workflow in v0.8.0. */
@WorkflowInterface
public interface RouteExplorationWorkflow {
  @WorkflowMethod
  RouteResult explore(RouteRequest request);
}
