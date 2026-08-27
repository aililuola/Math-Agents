package io.github.aililuola.mathproofmesh.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase17ValidationEscalatorBranchTest {

  @Test
  void disabledAndBelowThresholdPoliciesDoNotEscalate() {
    var disabled =
        new ValidationEscalator(
            new ValidationEscalationPolicy(
                false, false, false, false, false, false, 0.7, false, false, false));
    assertThat(disabled.plan(1.0, List.of("pass", "fail"), true, true, true, true).levels())
        .isEmpty();

    var quiet =
        new ValidationEscalator(
            new ValidationEscalationPolicy(
                true, false, true, true, true, true, 0.7, false, false, false));
    assertThat(quiet.plan(0.2, null, true, true, false, false).levels()).isEmpty();
    assertThat(quiet.plan(0.2, Arrays.asList(null, " ", "pass", " pass "), true, true, false, false)
            .levels())
        .isEmpty();
    assertThatThrownBy(() -> new ValidationEscalator(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void eachIndependentEscalationCauseAndBackendAvailabilityIsCovered() {
    ValidationEscalationPolicy all =
        new ValidationEscalationPolicy(
            true, true, true, true, true, true, 0.7, true, true, true);
    ValidationEscalator escalator = new ValidationEscalator(all);

    assertThat(escalator.plan(0.2, List.of("pass", "fail"), true, true, false, false).levels())
        .contains(ValidationLevel.BLIND_SAME_MODEL, ValidationLevel.CROSS_PROVIDER);
    assertThat(escalator.plan(0.2, List.of(), true, true, true, false).blocksFactPromotion())
        .isTrue();
    assertThat(escalator.plan(0.2, List.of(), true, true, false, true).levels())
        .contains(ValidationLevel.ADVERSARIAL_BLIND);

    EscalationPlan unavailable =
        escalator.plan(0.9, List.of("pass"), false, false, true, false);
    assertThat(unavailable.levels())
        .contains(
            ValidationLevel.DETERMINISTIC,
            ValidationLevel.BLIND_SAME_MODEL,
            ValidationLevel.ADVERSARIAL_BLIND)
        .doesNotContain(ValidationLevel.CROSS_PROVIDER, ValidationLevel.TOOL_OR_FORMAL);
    assertThat(unavailable.diagnostics()).hasSize(2);

    EscalationPlan available =
        escalator.plan(0.9, List.of("pass"), true, true, false, false);
    assertThat(available.levels())
        .contains(ValidationLevel.CROSS_PROVIDER, ValidationLevel.TOOL_OR_FORMAL);
    assertThat(available.blocksFactPromotion()).isFalse();
  }

  @Test
  void optionalReviewLevelsCanBeIndependentlyDisabled() {
    ValidationEscalationPolicy minimal =
        new ValidationEscalationPolicy(
            true, false, false, false, false, false, 0.0, true, true, true);
    EscalationPlan plan =
        new ValidationEscalator(minimal)
            .plan(0.0, List.of("same", "different"), false, false, true, true);
    assertThat(plan.levels()).isEmpty();
    assertThat(plan.blocksFactPromotion()).isTrue();
  }
}
