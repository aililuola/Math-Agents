package io.github.aililuola.mathproofmesh.proofcontrol;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ControlActionStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ControlActionType;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.Mode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies admitted proof-control actions exactly once. The executor is an
 * explicit authority boundary: this class never writes facts or closes
 * obligations itself.
 */
public final class ControlActionDispatcher {
  @FunctionalInterface
  public interface AuthorizedExecutor {
    String apply(Action action);
  }

  public record Action(
      String actionKey,
      String runId,
      String routeId,
      ControlActionType type,
      String targetId,
      Map<String, String> payload,
      ControlActionStatus status,
      String resultRef,
      List<String> audit) {
    public Action {
      actionKey = ProofControlModels.required(actionKey, "actionKey");
      runId = ProofControlModels.required(runId, "runId");
      routeId = ProofControlModels.blankToNull(routeId);
      type = Objects.requireNonNull(type, "type");
      targetId = ProofControlModels.required(targetId, "targetId");
      payload = payload == null ? Map.of() : Map.copyOf(payload);
      status = Objects.requireNonNull(status, "status");
      resultRef = ProofControlModels.blankToNull(resultRef);
      audit = audit == null ? List.of() : List.copyOf(audit);
    }

    @Override
    public Map<String, String> payload() {
      return Map.copyOf(payload);
    }

    @Override
    public List<String> audit() {
      return List.copyOf(audit);
    }
  }

  private final Map<String, Action> actions = new LinkedHashMap<>();

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "The authority executor failure is recorded transactionally and deliberately propagated")
  public synchronized Action dispatch(
      Mode mode,
      String runId,
      String routeId,
      ControlActionType type,
      String targetId,
      Map<String, String> payload,
      AuthorizedExecutor executor) {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(executor, "executor");
    String actionKey =
        ProofIdentity.actionKey(
            runId,
            type,
            List.of(runId),
            routeId == null || routeId.isBlank() ? List.of() : List.of(routeId),
            List.of(targetId),
            payload);
    Action existing = actions.get(actionKey);
    if (existing != null) {
      return existing;
    }

    Action proposed =
        new Action(
            actionKey,
            runId,
            routeId,
            type,
            targetId,
            payload,
            ControlActionStatus.PROPOSED,
            null,
            List.of("proposed"));
    if (mode == Mode.OFF) {
      Action rejected =
          transition(proposed, ControlActionStatus.REJECTED, null, "mode=off:no side effect");
      actions.put(actionKey, rejected);
      return rejected;
    }
    if (mode == Mode.SHADOW) {
      Action deferred =
          transition(
              proposed, ControlActionStatus.DEFERRED, null, "mode=shadow:recorded only");
      actions.put(actionKey, deferred);
      return deferred;
    }

    Action admitted =
        transition(proposed, ControlActionStatus.ADMITTED, null, "admission passed");
    actions.put(actionKey, admitted);
    Action executing =
        transition(admitted, ControlActionStatus.EXECUTING, null, "authorized execution began");
    actions.put(actionKey, executing);
    try {
      String resultRef =
          ProofControlModels.required(executor.apply(executing), "authorized result reference");
      Action applied =
          transition(
              executing,
              ControlActionStatus.APPLIED,
              resultRef,
              "authorized execution applied");
      actions.put(actionKey, applied);
      return applied;
    } catch (RuntimeException exception) {
      Action failed =
          transition(
              executing,
              ControlActionStatus.FAILED_RETRYABLE,
              null,
              "authorized execution failed:" + exception.getClass().getSimpleName());
      actions.put(actionKey, failed);
      throw exception;
    }
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "The authority executor failure is recorded transactionally and deliberately propagated")
  public synchronized Action retry(String actionKey, AuthorizedExecutor executor) {
    Objects.requireNonNull(executor, "executor");
    Action existing = required(actionKey);
    if (existing.status() == ControlActionStatus.APPLIED) {
      return existing;
    }
    if (existing.status() != ControlActionStatus.FAILED_RETRYABLE) {
      throw new IllegalStateException("only retryable actions may be retried");
    }
    Action executing =
        transition(existing, ControlActionStatus.EXECUTING, null, "authorized retry began");
    actions.put(actionKey, executing);
    try {
      String resultRef =
          ProofControlModels.required(executor.apply(executing), "authorized result reference");
      Action applied =
          transition(executing, ControlActionStatus.APPLIED, resultRef, "authorized retry applied");
      actions.put(actionKey, applied);
      return applied;
    } catch (RuntimeException exception) {
      actions.put(
          actionKey,
          transition(
              executing,
              ControlActionStatus.FAILED_RETRYABLE,
              null,
              "authorized retry failed:" + exception.getClass().getSimpleName()));
      throw exception;
    }
  }

  public synchronized Action get(String actionKey) {
    return required(actionKey);
  }

  public synchronized List<Action> actions() {
    return actions.values().stream()
        .sorted(Comparator.comparing(Action::actionKey))
        .toList();
  }

  private Action required(String actionKey) {
    Action action = actions.get(actionKey);
    if (action == null) {
      throw new IllegalArgumentException("unknown action: " + actionKey);
    }
    return action;
  }

  private static Action transition(
      Action action, ControlActionStatus status, String resultRef, String event) {
    List<String> audit = new ArrayList<>(action.audit());
    audit.add(event);
    return new Action(
        action.actionKey(),
        action.runId(),
        action.routeId(),
        action.type(),
        action.targetId(),
        action.payload(),
        status,
        resultRef,
        audit);
  }
}
