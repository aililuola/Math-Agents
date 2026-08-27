package io.github.aililuola.mathproofmesh.desktop;

import java.util.Objects;

/** Pure legacy-checkpoint cursor inference kept outside the production coordinator. */
final class DesktopWorkflowCursorInference {
  private DesktopWorkflowCursorInference() {}

  static String infer(DesktopSolveCheckpoint checkpoint) {
    Objects.requireNonNull(checkpoint, "checkpoint");
    if (checkpoint.terminal()) {
      return "terminal";
    }
    if (checkpoint.problem() == null) {
      return "freeze_problem";
    }
    if (checkpoint.triage() == null) {
      return "triage";
    }
    if (checkpoint.strategySet() == null) {
      return "strategy_diversity";
    }
    if (checkpoint.routes().isEmpty()) {
      return "initial_routes";
    }
    if (checkpoint.finalProof() != null) {
      return "blind_final_review";
    }
    String stage = checkpoint.currentStage() == null ? "" : checkpoint.currentStage();
    if (stage.contains("synthesis")) {
      return "synthesis";
    }
    if (stage.contains("scheduler")
        || stage.contains("meta")
        || stage.contains("inspiration")
        || stage.contains("broker")) {
      return "scheduler_inspiration";
    }
    boolean hasUnintegratedAttempt =
        checkpoint.routes().stream().anyMatch(route -> route.attempt() != null && !route.integrated());
    return hasUnintegratedAttempt ? "integrate_routes" : "isolated_exploration";
  }
}
