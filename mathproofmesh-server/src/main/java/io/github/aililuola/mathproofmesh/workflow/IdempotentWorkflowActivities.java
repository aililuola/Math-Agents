package io.github.aililuola.mathproofmesh.workflow;

import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.ActivityCommand;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.ActivityResult;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.SafeProgress;
import io.temporal.activity.Activity;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Activity adapter with state-transition idempotency. Retries may re-enter this object, but each
 * action key is applied once by the store.
 */
public final class IdempotentWorkflowActivities implements WorkflowActivities {
  private final DomainActionStore store;
  private final long fencingToken;
  private final AtomicInteger providerCalls = new AtomicInteger();

  public IdempotentWorkflowActivities(DomainActionStore store, long fencingToken) {
    this.store = java.util.Objects.requireNonNull(store, "store");
    if (fencingToken <= 0) {
      throw new IllegalArgumentException("fencingToken must be positive");
    }
    this.fencingToken = fencingToken;
  }

  @Override
  public ActivityResult preflight(ActivityCommand command) {
    return apply("preflight", command, false);
  }

  @Override
  public ActivityResult plan(ActivityCommand command) {
    return apply("plan", command, false);
  }

  @Override
  public ActivityResult agentCall(ActivityCommand command) {
    return apply("agent-call", command, true);
  }

  @Override
  public ActivityResult compute(ActivityCommand command) {
    return apply("compute", command, false);
  }

  @Override
  public ActivityResult broker(ActivityCommand command) {
    return apply("broker", command, false);
  }

  @Override
  public ActivityResult memory(ActivityCommand command) {
    return apply("memory", command, false);
  }

  @Override
  public ActivityResult proofGraph(ActivityCommand command) {
    return apply("proof-graph", command, false);
  }

  @Override
  public ActivityResult verify(ActivityCommand command) {
    return apply("verify", command, false);
  }

  @Override
  public ActivityResult synthesize(ActivityCommand command) {
    return apply("synthesize", command, false);
  }

  @Override
  public ActivityResult finalReview(ActivityCommand command) {
    return apply("final-review", command, false);
  }

  @Override
  public ActivityResult persist(ActivityCommand command) {
    return apply("persist", command, false);
  }

  @Override
  public ActivityResult report(ActivityCommand command) {
    return apply("report", command, false);
  }

  public int providerCalls() {
    return providerCalls.get();
  }

  private ActivityResult apply(
      String kind, ActivityCommand command, boolean providerCall) {
    java.util.Objects.requireNonNull(command, "command");
    return store.applyOnce(
        command.actionKey(),
        fencingToken,
        () -> {
          heartbeat(command.actionKey(), "applying");
          if (providerCall) {
            providerCalls.incrementAndGet();
          }
          String checkpoint =
              command.checkpointId().isEmpty()
                  ? "checkpoint-" + command.runId() + "-" + kind
                  : command.checkpointId();
          List<String> claims =
              kind.equals("verify") || kind.equals("final-review")
                  ? List.of("claim-" + command.runId())
                  : List.of();
          heartbeat(command.actionKey(), "committed");
          return new ActivityResult(
              command.actionKey(),
              "artifact://" + command.runId() + "/" + kind,
              checkpoint,
              claims,
              true);
        });
  }

  private static void heartbeat(String actionKey, String state) {
    try {
      Activity.getExecutionContext().heartbeat(new SafeProgress(actionKey, state));
    } catch (IllegalStateException ignored) {
      // Direct unit calls have no Temporal activity context.
    }
  }
}
