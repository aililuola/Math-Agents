package io.github.aililuola.mathproofmesh.workflow;

import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.ActivityResult;
import java.util.function.Supplier;

/**
 * Database-facing exactly-once boundary. Implementations combine an action key, Inbox entry, and
 * fencing token in one transaction.
 */
public interface DomainActionStore {
  ActivityResult applyOnce(
      String actionKey, long fencingToken, Supplier<ActivityResult> transition);

  int applicationCount(String actionKey);
}
