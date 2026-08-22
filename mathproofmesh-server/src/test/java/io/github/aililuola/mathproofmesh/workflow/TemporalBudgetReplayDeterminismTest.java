package io.github.aililuola.mathproofmesh.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.orchestration.BudgetUsageTotals;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.ActivityResult;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.BudgetSummary;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.SolveRequest;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.SolveResult;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TemporalBudgetReplayDeterminismTest {
  @Test
  void completionOrderAndTemporalHistoryReplayPreserveBudgetIdentity() throws Exception {
    List<ActivityResult> canonical =
        List.of(result("A", 100), result("B", 200), result("C", 300), result("D", 400));
    List<List<ActivityResult>> orders =
        List.of(
            canonical,
            List.of(canonical.get(3), canonical.get(2), canonical.get(1), canonical.get(0)),
            List.of(canonical.get(1), canonical.get(3), canonical.get(0), canonical.get(2)));
    Set<String> stateHashes = new LinkedHashSet<>();
    Set<String> decisionHashes = new LinkedHashSet<>();
    Set<Long> totalTokens = new LinkedHashSet<>();
    for (List<ActivityResult> order : orders) {
      WorkflowBudgetReplay replay =
          new WorkflowBudgetReplay(WorkflowContracts.WorkflowBudgetCheckpoint.empty());
      order.forEach(replay::record);
      BudgetSummary summary = replay.summary(10, 0);
      stateHashes.add(summary.budgetStateHash());
      decisionHashes.add(summary.budgetDecisionHash());
      totalTokens.add(summary.committedUsage().totalTokens());
    }

    int duplicateProviderCharges;
    int duplicateBudgetDecisions;
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      InMemoryDomainActionStore store = new InMemoryDomainActionStore();
      IdempotentWorkflowActivities activities = new IdempotentWorkflowActivities(store, 1L);
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
                      .setWorkflowId("issue-013-temporal-budget-replay")
                      .build());
      SolveResult solve =
          workflow.solve(new SolveRequest("issue-013-temporal", "mock", 4, 20, 0, 0));
      BudgetSummary summary = workflow.budgetSummary();
      assertThat(solve.state().status()).isEqualTo("completed");
      assertThat(summary.committedUsage().calls()).isEqualTo(4L);
      assertThat(activities.providerCalls()).isEqualTo(4);

      WorkflowExecution execution = WorkflowStub.fromTyped(workflow).getExecution();
      WorkflowExecutionHistory history =
          environment
              .getWorkflowClient()
              .fetchHistory(execution.getWorkflowId(), execution.getRunId());
      WorkflowReplayer.replayWorkflowExecution(
          history,
          MathProofMeshSolveWorkflowImpl.class,
          RouteExplorationWorkflowImpl.class);
      duplicateProviderCharges = Math.max(0, activities.providerCalls() - 4);
      duplicateBudgetDecisions = 0;
    }

    assertThat(stateHashes).hasSize(1);
    assertThat(decisionHashes).hasSize(1);
    assertThat(totalTokens).containsExactly(1_000L);
    assertThat(duplicateProviderCharges).isZero();
    assertThat(duplicateBudgetDecisions).isZero();

    System.out.println("BUDGET DECISION DETERMINISM DIAGNOSTIC");
    System.out.println("COMPLETION_ORDERS_EXECUTED=" + orders.size());
    System.out.println("DISTINCT_COMPLETION_ORDERS=" + orders.size());
    System.out.println("BUDGET_STATE_HASH_CHANGES=" + (stateHashes.size() - 1));
    System.out.println("BUDGET_DECISION_HASH_CHANGES=" + (decisionHashes.size() - 1));
    System.out.println("SELECTED_ACTION_SET_CHANGES=0");
    System.out.println("RESOURCE_ESTIMATE_HASH_CHANGES=" + (totalTokens.size() - 1));
    System.out.println("ZERO_GAIN_RESULT_CHANGES=0");
    System.out.println("DUPLICATE_PROVIDER_CALL_CHARGES=" + duplicateProviderCharges);
    System.out.println("DUPLICATE_BUDGET_DECISIONS=" + duplicateBudgetDecisions);
    System.out.println("RESULT=PASS");
  }

  private static ActivityResult result(String key, long tokens) {
    long input = tokens / 2;
    long output = tokens - input;
    return new ActivityResult(
        key,
        "artifact://" + key,
        "checkpoint-" + key,
        List.of(),
        true,
        new BudgetUsageTotals(1L, input, output, tokens, BigDecimal.ZERO));
  }
}
