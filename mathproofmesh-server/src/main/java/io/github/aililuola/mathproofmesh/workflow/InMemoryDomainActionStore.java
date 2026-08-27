package io.github.aililuola.mathproofmesh.workflow;

import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.ActivityResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Test/reference action store preserving state-transition exactly-once semantics. */
public final class InMemoryDomainActionStore implements DomainActionStore {
  private final Map<String, StoredAction> actions = new LinkedHashMap<>();

  @Override
  public synchronized ActivityResult applyOnce(
      String actionKey, long fencingToken, Supplier<ActivityResult> transition) {
    String key = required(actionKey);
    if (fencingToken <= 0) {
      throw new IllegalArgumentException("fencingToken must be positive");
    }
    StoredAction prior = actions.get(key);
    if (prior != null) {
      if (fencingToken < prior.fencingToken()) {
        throw new IllegalStateException("stale fencing token");
      }
      return prior.result();
    }
    ActivityResult result = java.util.Objects.requireNonNull(transition.get(), "transition");
    actions.put(key, new StoredAction(fencingToken, result));
    return result;
  }

  @Override
  public synchronized int applicationCount(String actionKey) {
    return actions.containsKey(required(actionKey)) ? 1 : 0;
  }

  private static String required(String value) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException("actionKey is required");
    }
    return result;
  }

  private record StoredAction(long fencingToken, ActivityResult result) {}
}
