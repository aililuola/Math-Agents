package io.github.aililuola.mathproofmesh.workflow;

import java.util.List;

/** Deterministic scheduling stages; mathematical decisions remain in domain services. */
public final class RunStageMachine {
  public static final List<Stage> STAGES = List.of(Stage.values());

  private RunStageMachine() {}

  public static Stage next(Stage current) {
    int index = STAGES.indexOf(current);
    if (index < 0 || index + 1 >= STAGES.size()) {
      throw new IllegalStateException("stage has no successor: " + current);
    }
    return STAGES.get(index + 1);
  }

  public enum Stage {
    PREFLIGHT,
    PLAN,
    ROUTE_EXPLORATION,
    BROKER,
    MEMORY,
    PROOF_GRAPH,
    VERIFY,
    SYNTHESIZE,
    FINAL_REVIEW,
    PERSIST,
    REPORT,
    COMPLETED
  }
}
