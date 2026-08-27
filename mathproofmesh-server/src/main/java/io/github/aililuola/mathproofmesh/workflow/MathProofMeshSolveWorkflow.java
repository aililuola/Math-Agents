package io.github.aililuola.mathproofmesh.workflow;

import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.BudgetSummary;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.BudgetUpdate;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.CommandSignal;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.DirectiveResult;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.DirectiveUpdate;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.RouteSummary;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.SolveRequest;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.SolveResult;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.WakeRouteSignal;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** The only run-level workflow in v0.8.0. */
@WorkflowInterface
public interface MathProofMeshSolveWorkflow {
  @WorkflowMethod
  SolveResult solve(SolveRequest request);

  @SignalMethod
  void pause(CommandSignal signal);

  @SignalMethod
  void resume(CommandSignal signal);

  @SignalMethod
  void cancel(CommandSignal signal);

  @SignalMethod
  void wakeRoute(WakeRouteSignal signal);

  @UpdateMethod
  int increaseBudget(BudgetUpdate update);

  @UpdateMethod
  DirectiveResult submitAuditedDirective(DirectiveUpdate update);

  @QueryMethod
  SolveState status();

  @QueryMethod
  String currentStage();

  @QueryMethod
  RouteSummary routeSummary();

  @QueryMethod
  BudgetSummary budgetSummary();
}
