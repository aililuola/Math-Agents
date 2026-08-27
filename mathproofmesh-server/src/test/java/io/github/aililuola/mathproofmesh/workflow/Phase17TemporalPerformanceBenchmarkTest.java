package io.github.aililuola.mathproofmesh.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.SolveRequest;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.SolveResult;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Phase17TemporalPerformanceBenchmarkTest {
  private static final int ROUTES = 8;
  private static final int GENERATIONS = 2;

  @Test
  void multiRouteReplayAndContinueAsNewAreMeasured() throws Exception {
    try (Harness harness = new Harness()) {
      SolveResult warmupResult =
          harness
              .newWorkflow("phase17-temporal-warmup")
              .solve(new SolveRequest("phase17-temporal-warmup-run", "mock", 1, 20, 0, 0));
      assertThat(warmupResult.state().status()).isEqualTo("completed");

      MathProofMeshSolveWorkflow multiRoute =
          harness.newWorkflow("phase17-temporal-multiroute");
      long multiRouteStarted = System.nanoTime();
      SolveResult multiRouteResult =
          multiRoute.solve(
              new SolveRequest(
                  "phase17-temporal-multiroute-run",
                  "mock",
                  ROUTES,
                  160,
                  0,
                  0));
      long multiRouteNanos = System.nanoTime() - multiRouteStarted;
      assertThat(multiRouteResult.state().status()).isEqualTo("completed");
      assertThat(multiRouteResult.state().completedRouteIds()).hasSize(ROUTES);

      WorkflowExecution execution =
          WorkflowStub.fromTyped(multiRoute).getExecution();
      WorkflowExecutionHistory history =
          harness.environment
              .getWorkflowClient()
              .fetchHistory(execution.getWorkflowId(), execution.getRunId());
      long replayStarted = System.nanoTime();
      WorkflowReplayer.replayWorkflowExecution(
          history,
          MathProofMeshSolveWorkflowImpl.class,
          RouteExplorationWorkflowImpl.class);
      long replayNanos = System.nanoTime() - replayStarted;

      MathProofMeshSolveWorkflow continued =
          harness.newWorkflow("phase17-temporal-continue-as-new");
      long continueStarted = System.nanoTime();
      SolveResult continuedResult =
          continued.solve(
              new SolveRequest(
                  "phase17-temporal-can-run",
                  "mock",
                  2,
                  40,
                  0,
                  GENERATIONS));
      long continueNanos = System.nanoTime() - continueStarted;
      assertThat(continuedResult.state().status()).isEqualTo("completed");
      assertThat(continuedResult.state().generation()).isEqualTo(GENERATIONS);
      assertThat(
              harness.store.applicationCount(
                  "phase17-temporal-can-run:preflight"))
          .isEqualTo(1);
      assertThat(
              harness.store.applicationCount(
                  "phase17-temporal-can-run:plan"))
          .isEqualTo(1);

      Path report =
          Path.of(System.getProperty("mathproofmesh.projectRoot"))
              .resolve("target/benchmark-reports/phase17-temporal.json");
      Files.createDirectories(report.getParent());
      Files.writeString(
          report,
          """
          {
            "scenario":"temporal-multi-route-replay-continue-as-new",
            "routes":%d,
            "history_events":%d,
            "continue_as_new_generations":%d,
            "multi_route_elapsed_ns":%d,
            "replay_elapsed_ns":%d,
            "continue_as_new_elapsed_ns":%d,
            "duplicate_domain_applications":0,
            "result":"PASS"
          }
          """
              .formatted(
                  ROUTES,
                  history.getEvents().size(),
                  GENERATIONS,
                  multiRouteNanos,
                  replayNanos,
                  continueNanos),
          StandardCharsets.UTF_8);
    }
  }

  private static final class Harness implements AutoCloseable {
    private final TestWorkflowEnvironment environment =
        TestWorkflowEnvironment.newInstance();
    private final InMemoryDomainActionStore store =
        new InMemoryDomainActionStore();

    private Harness() {
      Worker worker = environment.newWorker(WorkflowContracts.TASK_QUEUE);
      worker.registerWorkflowImplementationTypes(
          MathProofMeshSolveWorkflowImpl.class,
          RouteExplorationWorkflowImpl.class);
      worker.registerActivitiesImplementations(
          new IdempotentWorkflowActivities(store, 1));
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
