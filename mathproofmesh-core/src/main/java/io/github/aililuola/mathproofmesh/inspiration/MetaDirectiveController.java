package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import io.github.aililuola.mathproofmesh.contract.InspirationTask;
import io.github.aililuola.mathproofmesh.contract.MetaDirective;
import io.github.aililuola.mathproofmesh.contract.MetaDirectiveAction;
import io.github.aililuola.mathproofmesh.contract.MetaDirectiveAudit;
import io.github.aililuola.mathproofmesh.contract.MetaDirectiveExecution;
import io.github.aililuola.mathproofmesh.contract.MetaStrategyDecision;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Audits and applies strategy control exactly once without creating evidence. */
public final class MetaDirectiveController {
  private final InspirationPolicy policy;
  private final Map<String, RouteControl> routes = new LinkedHashMap<>();
  private final Map<String, ExecutionResult> executions = new LinkedHashMap<>();

  public MetaDirectiveController(InspirationPolicy policy, List<RouteControl> initialRoutes) {
    this.policy = java.util.Objects.requireNonNull(policy, "policy");
    for (RouteControl route : initialRoutes == null ? List.<RouteControl>of() : initialRoutes) {
      routes.put(route.routeId(), route);
    }
  }

  public MetaDirective fromDecision(MetaStrategyDecision decision, InspirationSnapshot snapshot) {
    MetaDirectiveAction action =
        switch (decision.action()) {
          case "continue_current_mechanism" -> MetaDirectiveAction.CONTINUE;
          case "switch_representation" -> MetaDirectiveAction.SWITCH_REPRESENTATION;
          case "surprise_exploration" -> MetaDirectiveAction.ALLOCATE_SURPRISE_BUDGET;
          case "cooldown_route" -> MetaDirectiveAction.COOLDOWN_ROUTE;
          case "abandon_route" -> MetaDirectiveAction.ABANDON_ROUTE;
          case "merge_route" -> MetaDirectiveAction.MERGE_ROUTES;
          case "local_repair", "invent_auxiliary_construction" -> MetaDirectiveAction.REPAIR;
          default -> MetaDirectiveAction.REWRITE_PLAN;
        };
    String id =
        "meta_directive_"
            + CanonicalJson.stableHash(
                    List.of(
                        decision.decisionId(),
                        decision.roundIndex(),
                        action.value(),
                        decision.affectedRouteIds()))
                .substring(0, 12);
    return new MetaDirective(
        action,
        id,
        decision.estimatedCalls(),
        decision.roundIndex() + 2,
        snapshot.finalRepairFailed()
            || action == MetaDirectiveAction.COOLDOWN_ROUTE
            || action == MetaDirectiveAction.ABANDON_ROUTE,
        decision.observableMetrics(),
        decision.reason(),
        decision.roundIndex(),
        decision.affectedRouteIds(),
        decision.selectedMechanism(),
        decision.decisionId());
  }

  public MetaDirectiveAudit audit(MetaDirective directive, InspirationSnapshot snapshot) {
    boolean targetsValid = directive.routeIds().stream().allMatch(routes::containsKey);
    boolean evidenceComplete =
        !directive.reason().isBlank() && !directive.observableEvidence().isEmpty();
    boolean budgetSafe =
        directive.estimatedCalls() <= snapshot.schedulableCalls()
            && directive.estimatedCalls() <= 4;
    List<String> reasons = new java.util.ArrayList<>();
    if (directive.roundIndex() > snapshot.roundIndex()) {
      reasons.add("directive originates in a future round");
    }
    if (directive.expiresRound() < snapshot.roundIndex()) {
      reasons.add("directive expired");
    }
    if (!targetsValid) {
      reasons.add("one or more target routes are unknown");
    }
    if (!evidenceComplete) {
      reasons.add("observable evidence is incomplete");
    }
    if (!budgetSafe) {
      reasons.add("directive consumes protected finalization budget");
    }
    if (SetLike.routeMutation(directive.action()) && directive.routeIds().isEmpty()) {
      reasons.add("route-mutating directive has no target");
    }
    if (directive.action() == MetaDirectiveAction.MERGE_ROUTES
        && directive.routeIds().size() < 2) {
      reasons.add("merge requires two routes");
    }
    return new MetaDirectiveAudit(
        reasons.isEmpty(),
        "deterministic_meta_directive_auditor",
        budgetSafe,
        directive.directiveId(),
        evidenceComplete,
        reasons.isEmpty() ? "directive passed all control gates" : String.join("; ", reasons),
        targetsValid);
  }

  public synchronized ExecutionResult execute(
      MetaDirective directive,
      MetaDirectiveAudit audit,
      InspirationSnapshot snapshot,
      String triggerId) {
    ExecutionResult prior = executions.get(directive.directiveId());
    if (prior != null) {
      return prior;
    }
    if (!audit.accepted()) {
      return remember(
          directive.directiveId(),
          new ExecutionResult(
              new MetaDirectiveExecution(
                  List.of(), directive.directiveId(), List.of(), audit.reason(), "rejected"),
              List.of(),
              false));
    }
    if (policy.recordsOnly()) {
      return remember(
          directive.directiveId(),
          new ExecutionResult(
              new MetaDirectiveExecution(
                  List.of(),
                  directive.directiveId(),
                  List.of(),
                  "shadow mode recorded the directive",
                  "noop"),
              List.of(),
              false));
    }
    if (directive.action() == MetaDirectiveAction.CONTINUE) {
      return remember(
          directive.directiveId(),
          new ExecutionResult(
              new MetaDirectiveExecution(
                  List.of(),
                  directive.directiveId(),
                  List.of(),
                  "current mechanism retained",
                  "noop"),
              List.of(),
              false));
    }
    if (directive.action() == MetaDirectiveAction.COOLDOWN_ROUTE
        || directive.action() == MetaDirectiveAction.ABANDON_ROUTE) {
      List<String> affected = new java.util.ArrayList<>();
      for (String routeId : directive.routeIds()) {
        RouteControl route = routes.get(routeId);
        if (route == null || !route.active()) {
          continue;
        }
        routes.put(
            routeId,
            directive.action() == MetaDirectiveAction.COOLDOWN_ROUTE
                ? new RouteControl(
                    routeId, false, snapshot.roundIndex() + 2, false, directive.reason())
                : new RouteControl(routeId, false, -1, true, directive.reason()));
        affected.add(routeId);
      }
      return remember(
          directive.directiveId(),
          new ExecutionResult(
              new MetaDirectiveExecution(
                  affected,
                  directive.directiveId(),
                  List.of(),
                  "audited route control applied",
                  affected.isEmpty() ? "noop" : "executed"),
              List.of(),
              !affected.isEmpty()));
    }
    InspirationMechanism mechanism = mechanismFor(directive);
    String taskId =
        "inspiration_task_"
            + CanonicalJson.stableHash(
                    List.of(directive.directiveId(), mechanism.value()))
                .substring(0, 12);
    InspirationTask task =
        new InspirationTask(
            policy.limits().maxProposalsPerTask(),
            mechanism,
            "MetaDirective " + directive.action().value() + ": " + directive.reason(),
            snapshot.openObligationIds(),
            directive.routeIds(),
            taskId,
            triggerId);
    return remember(
        directive.directiveId(),
        new ExecutionResult(
            new MetaDirectiveExecution(
                directive.routeIds(),
                directive.directiveId(),
                List.of(taskId),
                "directive generated a scheduler-admissible task",
                "executed"),
            List.of(task),
            true));
  }

  public synchronized RouteControl route(String routeId) {
    return routes.get(routeId);
  }

  private ExecutionResult remember(String id, ExecutionResult result) {
    executions.put(id, result);
    return result;
  }

  private static InspirationMechanism mechanismFor(MetaDirective directive) {
    if (directive.action() == MetaDirectiveAction.SWITCH_REPRESENTATION) {
      return InspirationMechanism.REPRESENTATION_SWITCH;
    }
    if (directive.action() == MetaDirectiveAction.ALLOCATE_SURPRISE_BUDGET) {
      return InspirationMechanism.SURPRISE_EXPLORATION;
    }
    if (directive.selectedMechanism() != null
        && directive.selectedMechanism() != InspirationMechanism.META_REPLAN) {
      return directive.selectedMechanism();
    }
    return directive.action() == MetaDirectiveAction.REPAIR
        ? InspirationMechanism.AUXILIARY_CONSTRUCTION
        : InspirationMechanism.REVERSE_GOAL_ANALYSIS;
  }

  private static final class SetLike {
    private SetLike() {}

    static boolean routeMutation(MetaDirectiveAction action) {
      return action == MetaDirectiveAction.COOLDOWN_ROUTE
          || action == MetaDirectiveAction.ABANDON_ROUTE
          || action == MetaDirectiveAction.MERGE_ROUTES;
    }
  }

  public record RouteControl(
      String routeId, boolean active, int cooldownUntilRound, boolean abandoned, String reason) {
    public RouteControl {
      routeId = routeId == null ? "" : routeId.strip();
      reason = reason == null ? "" : reason.strip();
      if (routeId.isEmpty()) {
        throw new IllegalArgumentException("routeId is required");
      }
    }
  }

  public record ExecutionResult(
      MetaDirectiveExecution execution,
      List<InspirationTask> generatedTasks,
      boolean businessMutation) {
    public ExecutionResult {
      generatedTasks = List.copyOf(generatedTasks);
    }
  }
}
