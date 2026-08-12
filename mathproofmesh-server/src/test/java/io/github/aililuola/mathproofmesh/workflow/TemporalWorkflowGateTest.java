package io.github.aililuola.mathproofmesh.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.BudgetUpdate;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.CommandSignal;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.SolveRequest;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.SolveResult;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TemporalWorkflowGateTest {
  @Test
  void testWorkflowEnvironmentRunsBothFixedWorkflowsAndReplaysHistory() throws Exception {
    try (Harness harness = new Harness()) {
      MathProofMeshSolveWorkflow workflow = harness.newWorkflow("phase13-replay");
      SolveResult result =
          workflow.solve(new SolveRequest("run-replay", "mock", 2, 20, 0, 0));
      assertEquals("completed", result.state().status());
      assertEquals(2, result.state().completedRouteIds().size());
      assertTrue(result.verification().blindFinalReviewPassed());

      WorkflowExecution execution = WorkflowStub.fromTyped(workflow).getExecution();
      WorkflowExecutionHistory history =
          harness
              .environment
              .getWorkflowClient()
              .fetchHistory(execution.getWorkflowId(), execution.getRunId());
      assertTrue(history.getEvents().size() > 10);
      assertFalse(history.toJson(false).contains("private reasoning"));
      WorkflowReplayer.replayWorkflowExecution(
          history,
          MathProofMeshSolveWorkflowImpl.class,
          RouteExplorationWorkflowImpl.class);
    }
  }

  @Test
  void continueAsNewAndActivityRetryKeepDomainApplicationsExactlyOnce() {
    try (Harness harness = new Harness()) {
      MathProofMeshSolveWorkflow workflow = harness.newWorkflow("phase13-can");
      SolveResult result =
          workflow.solve(new SolveRequest("run-can", "mock", 1, 10, 0, 1));
      assertEquals("completed", result.state().status());
      assertEquals(1, result.state().generation());
      assertEquals(1, harness.store.applicationCount("run-can:preflight"));
      assertEquals(1, harness.store.applicationCount("run-can:plan"));
      assertEquals(1, harness.activities.providerCalls());
    }
  }

  @Test
  void duplicateSignalAndUpdateIdsAreIdempotentAndUpdatesAreValidated() throws Exception {
    try (Harness harness = new Harness()) {
      MathProofMeshSolveWorkflow workflow = harness.newWorkflow("phase13-controls");
      WorkflowExecution execution =
          WorkflowClient.start(
              workflow::solve, new SolveRequest("run-controls", "mock", 1, 10, 0, 0));
      assertNotNull(execution.getRunId());

      workflow.pause(new CommandSignal("pause-1"));
      workflow.pause(new CommandSignal("pause-1"));
      assertEquals("paused", awaitStatus(workflow, "paused"));
      assertEquals(15, workflow.increaseBudget(new BudgetUpdate("budget-1", 5)));
      assertEquals(15, workflow.increaseBudget(new BudgetUpdate("budget-1", 5)));
      assertEquals(1, workflow.budgetSummary().acceptedUpdates());
      workflow.resume(new CommandSignal("resume-1"));
      workflow.resume(new CommandSignal("resume-1"));
      SolveResult result =
          WorkflowStub.fromTyped(workflow).getResult(10, TimeUnit.SECONDS, SolveResult.class);
      assertEquals("completed", result.state().status());
    }
  }

  @Test
  void onlyTwoWorkflowInterfacesExistAndFinalReviewIsAnActivity() throws Exception {
    assertTrue(MathProofMeshSolveWorkflow.class.isAnnotationPresent(
        io.temporal.workflow.WorkflowInterface.class));
    assertTrue(RouteExplorationWorkflow.class.isAnnotationPresent(
        io.temporal.workflow.WorkflowInterface.class));
    assertTrue(
        MathProofMeshSolveWorkflowImpl.class
            .getDeclaredMethod("validateIncreaseBudget", BudgetUpdate.class)
            .isAnnotationPresent(io.temporal.workflow.UpdateValidatorMethod.class));
    assertNotNull(
        java.util.Arrays.stream(WorkflowActivities.class.getMethods())
            .filter(method -> method.getName().equals("finalReview"))
            .findFirst()
            .orElse(null));
  }

  private static String awaitStatus(MathProofMeshSolveWorkflow workflow, String expected)
      throws InterruptedException {
    for (int attempt = 0; attempt < 100; attempt++) {
      String status = workflow.status().status();
      if (expected.equals(status)) {
        return status;
      }
      Thread.sleep(10L);
    }
    return workflow.status().status();
  }

  private static final class Harness implements AutoCloseable {
    private final TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance();
    private final InMemoryDomainActionStore store = new InMemoryDomainActionStore();
    private final IdempotentWorkflowActivities activities =
        new IdempotentWorkflowActivities(store, 1);

    private Harness() {
      Worker worker = environment.newWorker(WorkflowContracts.TASK_QUEUE);
      worker.registerWorkflowImplementationTypes(
          MathProofMeshSolveWorkflowImpl.class, RouteExplorationWorkflowImpl.class);
      worker.registerActivitiesImplementations(activities);
      environment.start();
    }

    private MathProofMeshSolveWorkflow newWorkflow(String workflowId) {
      return environment
          .getWorkflowClient()
          .newWorkflowStub(
              MathProofMeshSolveWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(WorkflowContracts.TASK_QUEUE)
                  .setWorkflowId(workflowId)
                  .build());
    }

    @Override
    public void close() {
      environment.close();
    }
  }
}
