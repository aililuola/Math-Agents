package io.github.aililuola.mathproofmesh.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.ActivityCommand;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.ActivityResult;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.SolveRequest;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class TemporalConcurrentRouteChildrenTest {
  @Test
  void allRouteChildrenStartProviderWorkBeforeTheFirstOneCompletes() {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      InMemoryDomainActionStore store = new InMemoryDomainActionStore();
      TrackingActivities activities = new TrackingActivities(store);
      Worker worker = environment.newWorker(WorkflowContracts.TASK_QUEUE);
      worker.registerWorkflowImplementationTypes(
          MathProofMeshSolveWorkflowImpl.class, RouteExplorationWorkflowImpl.class);
      worker.registerActivitiesImplementations(activities);
      environment.start();
      MathProofMeshSolveWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  MathProofMeshSolveWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setTaskQueue(WorkflowContracts.TASK_QUEUE)
                      .setWorkflowId("temporal-concurrent-route-children")
                      .build());

      var result = workflow.solve(new SolveRequest("temporal-run", "test", 4, 20, 0, 0));

      assertThat(result.state().status()).isEqualTo("completed");
      assertThat(activities.agentCalls.get()).isEqualTo(4);
      assertThat(activities.maximumConcurrentAgentCalls.get()).isEqualTo(4);
    }
  }

  private static final class TrackingActivities implements WorkflowActivities {
    private final IdempotentWorkflowActivities delegate;
    private final AtomicInteger activeAgentCalls = new AtomicInteger();
    private final AtomicInteger maximumConcurrentAgentCalls = new AtomicInteger();
    private final AtomicInteger agentCalls = new AtomicInteger();

    private TrackingActivities(InMemoryDomainActionStore store) {
      delegate = new IdempotentWorkflowActivities(store, 1L);
    }

    @Override
    public ActivityResult preflight(ActivityCommand command) {
      return delegate.preflight(command);
    }

    @Override
    public ActivityResult plan(ActivityCommand command) {
      return delegate.plan(command);
    }

    @Override
    public ActivityResult agentCall(ActivityCommand command) {
      agentCalls.incrementAndGet();
      int active = activeAgentCalls.incrementAndGet();
      maximumConcurrentAgentCalls.accumulateAndGet(active, Math::max);
      try {
        Thread.sleep(80L);
        return delegate.agentCall(command);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("activity interrupted", exception);
      } finally {
        activeAgentCalls.decrementAndGet();
      }
    }

    @Override
    public ActivityResult compute(ActivityCommand command) {
      return delegate.compute(command);
    }

    @Override
    public ActivityResult broker(ActivityCommand command) {
      return delegate.broker(command);
    }

    @Override
    public ActivityResult memory(ActivityCommand command) {
      return delegate.memory(command);
    }

    @Override
    public ActivityResult proofGraph(ActivityCommand command) {
      return delegate.proofGraph(command);
    }

    @Override
    public ActivityResult verify(ActivityCommand command) {
      return delegate.verify(command);
    }

    @Override
    public ActivityResult synthesize(ActivityCommand command) {
      return delegate.synthesize(command);
    }

    @Override
    public ActivityResult finalReview(ActivityCommand command) {
      return delegate.finalReview(command);
    }

    @Override
    public ActivityResult persist(ActivityCommand command) {
      return delegate.persist(command);
    }

    @Override
    public ActivityResult report(ActivityCommand command) {
      return delegate.report(command);
    }
  }
}
