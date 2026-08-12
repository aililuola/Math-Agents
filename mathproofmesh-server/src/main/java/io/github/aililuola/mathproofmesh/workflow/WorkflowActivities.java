package io.github.aililuola.mathproofmesh.workflow;

import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.ActivityCommand;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.ActivityResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** All workflow I/O ports. Workflow code itself contains no Spring, JDBC, HTTP, or files. */
@ActivityInterface
public interface WorkflowActivities {
  @ActivityMethod
  ActivityResult preflight(ActivityCommand command);

  @ActivityMethod
  ActivityResult plan(ActivityCommand command);

  @ActivityMethod
  ActivityResult agentCall(ActivityCommand command);

  @ActivityMethod
  ActivityResult compute(ActivityCommand command);

  @ActivityMethod
  ActivityResult broker(ActivityCommand command);

  @ActivityMethod
  ActivityResult memory(ActivityCommand command);

  @ActivityMethod
  ActivityResult proofGraph(ActivityCommand command);

  @ActivityMethod
  ActivityResult verify(ActivityCommand command);

  @ActivityMethod
  ActivityResult synthesize(ActivityCommand command);

  @ActivityMethod
  ActivityResult finalReview(ActivityCommand command);

  @ActivityMethod
  ActivityResult persist(ActivityCommand command);

  @ActivityMethod
  ActivityResult report(ActivityCommand command);
}
