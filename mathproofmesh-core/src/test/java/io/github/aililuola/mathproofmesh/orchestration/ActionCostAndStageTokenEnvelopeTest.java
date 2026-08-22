package io.github.aililuola.mathproofmesh.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ActionKind;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ActionCostAndStageTokenEnvelopeTest {

  @Test
  void complexDeepenAccountsForEveryBoundedPhysicalCall() {
    ActionCostEstimator.Profile profile =
        new ActionCostEstimator.Profile(
            1, 2, true, 2, 1, 1, 1, 1, 2, 0, 1, 1, 1, 1, 1_000L, 4_000L);
    ActionCostEstimator estimator =
        new ActionCostEstimator(
            profile,
            new PricingSnapshot(
                "provider",
                "model",
                new BigDecimal("1"),
                new BigDecimal("2"),
                PricingSnapshot.BillingMode.BILLED,
                "config",
                null));

    BudgetResourceVector estimate = estimator.estimate(ActionKind.DEEPEN);

    assertThat(estimate.calls()).isEqualTo(9L);
    assertThat(estimate.estimatedInputTokens()).isEqualTo(9_000L);
    assertThat(estimate.maxOutputTokens()).isEqualTo(36_000L);
    assertThat(estimate.maxCostUsd()).isPositive();
  }

  @Test
  void resolverAppliesEveryLimitAndFailsClosedForOversizedInput() {
    StageTokenEnvelopeResolver resolver = new StageTokenEnvelopeResolver();
    var allowed =
        resolver.resolve(
            new StageTokenEnvelopeResolver.Request(
                2_000L, 128_000L, 96_000L, 64_000L, 32_000L, 24_000L, 30_000L,
                40_000L, 8_000L));

    assertThat(allowed.allowed()).isTrue();
    assertThat(allowed.maxOutputTokens()).isEqualTo(24_000);
    assertThat(allowed.reservedTotalTokens()).isEqualTo(26_000L);

    var blocked =
        resolver.resolve(
            new StageTokenEnvelopeResolver.Request(
                10_001L, 128_000L, 96_000L, 64_000L, 64_000L, 64_000L, 10_000L,
                20_000L, 10_000L));
    assertThat(blocked.allowed()).isFalse();
    assertThat(blocked.code()).isEqualTo("INPUT_CONTEXT_EXCEEDS_BUDGET_ENVELOPE");
  }
}
