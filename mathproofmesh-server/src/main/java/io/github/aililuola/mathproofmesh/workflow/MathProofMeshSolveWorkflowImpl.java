package io.github.aililuola.mathproofmesh.workflow;

import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.ActivityCommand;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.ActivityResult;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.BudgetSummary;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.BudgetUpdate;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.CommandSignal;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.DirectiveResult;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.DirectiveUpdate;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.RouteRequest;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.RouteResult;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.RouteSummary;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.SolveRequest;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.SolveResult;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.WakeRouteSignal;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.Workflow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Run-level deterministic scheduler. All side effects cross an Activity boundary. */
public final class MathProofMeshSolveWorkflowImpl implements MathProofMeshSolveWorkflow {
  private final WorkflowActivities activities =
      Workflow.newActivityStub(WorkflowActivities.class, TemporalOptions.activities());
  private final Set<String> commandIds = new LinkedHashSet<>();
  private final Set<String> updateIds = new LinkedHashSet<>();
  private final List<String> completedRoutes = new ArrayList<>();
  private final List<String> wokenRoutes = new ArrayList<>();
  private final Map<String, DirectiveResult> directives = new LinkedHashMap<>();

  private String runId = "";
  private String status = "created";
  private String stage = RunStageMachine.Stage.PREFLIGHT.name();
  private String checkpointId = "";
  private int budget;
  private int generation;
  private int acceptedBudgetUpdates;
  private boolean paused;
  private boolean cancelled;

  @Override
  public SolveResult solve(SolveRequest request) {
    Workflow.getVersion("phase-13-workflow-shape", Workflow.DEFAULT_VERSION, 1);
    runId = request.runId();
    budget = request.budget();
    generation = request.generation();
    status = "running";

    waitIfPaused();
    ActivityResult preflight =
        activities.preflight(command("preflight", "", "", request.profile()));
    checkpointId = preflight.checkpointId();
    advance(RunStageMachine.Stage.PLAN);
    ActivityResult plan =
        activities.plan(command("plan", "", checkpointId, preflight.outputRef()));
    checkpointId = plan.checkpointId();

    if (request.generation() < request.maximumGenerations()) {
      Workflow.continueAsNew(request.nextGeneration());
    }

    advance(RunStageMachine.Stage.ROUTE_EXPLORATION);
    ArrayList<String> claimIds = new ArrayList<>();
    for (int index = 0; index < request.routeCount(); index++) {
      waitIfPaused();
      if (cancelled) {
        status = "cancelled";
        return new SolveResult(snapshot(), null);
      }
      String routeId = "route-" + index;
      RouteExplorationWorkflow child =
          Workflow.newChildWorkflowStub(
              RouteExplorationWorkflow.class,
              ChildWorkflowOptions.newBuilder()
                  .setWorkflowId(request.runId() + "-" + routeId)
                  .setTaskQueue(WorkflowContracts.TASK_QUEUE)
                  .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL)
                  .build());
      RouteResult route =
          child.explore(
              new RouteRequest(
                  request.runId(),
                  routeId,
                  "strategy-" + index,
                  checkpointId,
                  Math.max(0, budget / request.routeCount())));
      if (route.accepted()) {
        completedRoutes.add(route.routeId());
        claimIds.addAll(route.verifiedClaimIds());
        checkpointId = route.checkpointId();
      }
    }

    ActivityResult current = applyMain("broker", RunStageMachine.Stage.BROKER, checkpointId);
    current = applyMain("memory", RunStageMachine.Stage.MEMORY, current.checkpointId());
    current = applyMain("proof-graph", RunStageMachine.Stage.PROOF_GRAPH, current.checkpointId());
    current = applyMain("verify", RunStageMachine.Stage.VERIFY, current.checkpointId());
    claimIds.addAll(current.claimIds());
    current = applyMain("synthesize", RunStageMachine.Stage.SYNTHESIZE, current.checkpointId());
    ActivityResult finalReview =
        applyMain("final-review", RunStageMachine.Stage.FINAL_REVIEW, current.checkpointId());
    current = applyMain("persist", RunStageMachine.Stage.PERSIST, finalReview.checkpointId());
    current = applyMain("report", RunStageMachine.Stage.REPORT, current.checkpointId());

    checkpointId = current.checkpointId();
    advance(RunStageMachine.Stage.COMPLETED);
    status = "completed";
    VerificationBundle verification =
        new VerificationBundle(runId, claimIds.stream().distinct().toList(), current.outputRef(), true);
    return new SolveResult(snapshot(), verification);
  }

  @Override
  public void pause(CommandSignal signal) {
    if (commandIds.add(signal.commandId())) {
      paused = true;
      status = "paused";
    }
  }

  @Override
  public void resume(CommandSignal signal) {
    if (commandIds.add(signal.commandId())) {
      paused = false;
      if (!cancelled) {
        status = "running";
      }
    }
  }

  @Override
  public void cancel(CommandSignal signal) {
    if (commandIds.add(signal.commandId())) {
      cancelled = true;
      paused = false;
      status = "cancelled";
    }
  }

  @Override
  public void wakeRoute(WakeRouteSignal signal) {
    if (commandIds.add(signal.commandId()) && !wokenRoutes.contains(signal.routeId())) {
      wokenRoutes.add(signal.routeId());
    }
  }

  @Override
  public int increaseBudget(BudgetUpdate update) {
    validateIncreaseBudget(update);
    if (updateIds.add(update.updateId())) {
      budget = Math.addExact(budget, update.additionalCalls());
      acceptedBudgetUpdates++;
    }
    return budget;
  }

  @UpdateValidatorMethod(updateName = "increaseBudget")
  public void validateIncreaseBudget(BudgetUpdate update) {
    if (update == null || update.additionalCalls() <= 0 || update.additionalCalls() > 10_000) {
      throw new IllegalArgumentException("additionalCalls must be in [1,10000]");
    }
  }

  @Override
  public DirectiveResult submitAuditedDirective(DirectiveUpdate update) {
    validateAuditedDirective(update);
    DirectiveResult prior = directives.get(update.updateId());
    if (prior != null) {
      return prior;
    }
    DirectiveResult result =
        new DirectiveResult(update.directiveId(), true, "audited directive accepted");
    directives.put(update.updateId(), result);
    updateIds.add(update.updateId());
    return result;
  }

  @UpdateValidatorMethod(updateName = "submitAuditedDirective")
  public void validateAuditedDirective(DirectiveUpdate update) {
    if (update == null || update.auditRef().isBlank()) {
      throw new IllegalArgumentException("directive requires an audit reference");
    }
  }

  @Override
  public SolveState status() {
    return snapshot();
  }

  @Override
  public String currentStage() {
    return stage;
  }

  @Override
  public RouteSummary routeSummary() {
    return new RouteSummary(completedRoutes, wokenRoutes);
  }

  @Override
  public BudgetSummary budgetSummary() {
    return new BudgetSummary(budget, acceptedBudgetUpdates);
  }

  private ActivityResult applyMain(
      String action, RunStageMachine.Stage nextStage, String currentCheckpoint) {
    waitIfPaused();
    advance(nextStage);
    ActivityCommand command =
        command(action, "", currentCheckpoint, "artifact://" + runId + "/" + action + "/input");
    return switch (nextStage) {
      case BROKER -> activities.broker(command);
      case MEMORY -> activities.memory(command);
      case PROOF_GRAPH -> activities.proofGraph(command);
      case VERIFY -> activities.verify(command);
      case SYNTHESIZE -> activities.synthesize(command);
      case FINAL_REVIEW -> activities.finalReview(command);
      case PERSIST -> activities.persist(command);
      case REPORT -> activities.report(command);
      default -> throw new IllegalArgumentException("not an activity stage: " + nextStage);
    };
  }

  private ActivityCommand command(
      String action, String routeId, String currentCheckpoint, String inputRef) {
    return new ActivityCommand(
        runId, routeId, currentCheckpoint, runId + ":" + action, inputRef);
  }

  private void waitIfPaused() {
    Workflow.await(() -> !paused || cancelled);
  }

  private void advance(RunStageMachine.Stage next) {
    stage = next.name();
  }

  private SolveState snapshot() {
    return new SolveState(
        runId, status, stage, budget, generation, completedRoutes, checkpointId);
  }
}
