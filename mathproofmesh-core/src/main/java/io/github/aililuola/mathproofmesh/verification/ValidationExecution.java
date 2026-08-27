package io.github.aililuola.mathproofmesh.verification;

import java.util.List;

public record ValidationExecution(
    EscalationPlan plan,
    List<ValidationStepResult> steps,
    boolean passed,
    boolean factPromotionAllowed,
    List<String> diagnostics) {

  public ValidationExecution {
    plan = java.util.Objects.requireNonNull(plan, "plan");
    steps = steps == null ? List.of() : List.copyOf(steps);
    diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    if (factPromotionAllowed && plan.blocksFactPromotion() && !passed) {
      throw new IllegalArgumentException("failed escalation cannot allow Fact promotion");
    }
  }
}
