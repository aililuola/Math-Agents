package io.github.aililuola.mathproofmesh.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidationEscalationParityTest {

  @Test
  void high_risk_fact_promotion_uses_full_available_ladder() {
    ValidationEscalationPolicy policy =
        new ValidationEscalationPolicy(
            true, true, true, true, true, true, 0.7, true, true, true);
    EscalationPlan plan =
        new ValidationEscalator(policy)
            .plan(0.9, List.of("pass", "fail"), true, true, true, false);

    assertThat(plan.blocksFactPromotion()).isTrue();
    assertThat(plan.levels())
        .containsExactly(
            ValidationLevel.DETERMINISTIC,
            ValidationLevel.BLIND_SAME_MODEL,
            ValidationLevel.ADVERSARIAL_BLIND,
            ValidationLevel.CROSS_PROVIDER,
            ValidationLevel.TOOL_OR_FORMAL);
  }

  @Test
  void missing_heterogeneous_provider_degrades_with_diagnostic() {
    ValidationEscalationPolicy policy =
        new ValidationEscalationPolicy(
            true, true, true, true, true, true, 0.7, true, true, true);
    EscalationPlan plan =
        new ValidationEscalator(policy)
            .plan(0.9, List.of(), false, false, false, true);

    assertThat(plan.levels())
        .contains(ValidationLevel.ADVERSARIAL_BLIND)
        .doesNotContain(ValidationLevel.CROSS_PROVIDER);
    assertThat(plan.diagnostics()).anyMatch(item -> item.contains("unavailable"));
  }

  @Test
  void executor_runs_every_level_and_fails_closed_when_one_is_missing() {
    EscalationPlan plan =
        new ValidationEscalator(ValidationEscalationPolicy.defaults())
            .plan(0.8, List.of(), false, true, true, false);
    Map<ValidationLevel, java.util.function.Supplier<ValidationStepResult>> handlers =
        new EnumMap<>(ValidationLevel.class);
    for (ValidationLevel level : plan.levels()) {
      if (level != ValidationLevel.ADVERSARIAL_BLIND) {
        handlers.put(
            level, () -> ValidationStepResult.passed(level, List.of("evidence")));
      }
    }

    ValidationExecution execution =
        new ValidationEscalationExecutor().execute(plan, handlers);

    assertThat(execution.passed()).isFalse();
    assertThat(execution.factPromotionAllowed()).isFalse();
    assertThat(execution.steps())
        .anyMatch(
            step ->
                step.level() == ValidationLevel.ADVERSARIAL_BLIND
                    && !step.executed());
  }
}
