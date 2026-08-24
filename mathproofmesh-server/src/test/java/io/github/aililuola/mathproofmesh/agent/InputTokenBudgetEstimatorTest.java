package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class InputTokenBudgetEstimatorTest {
  @Test
  void preservesHeadroomForTheObservedBenchmarkUnderestimate() {
    String prompt = "a".repeat(4_381);
    long legacyEstimate = (prompt.length() + 3L) / 4L;

    long estimate = InputTokenBudgetEstimator.estimate(prompt, "");

    assertThat(legacyEstimate).isEqualTo(1_096L);
    assertThat(estimate).isGreaterThanOrEqualTo(1_151L);
    assertThat(estimate).isEqualTo(2_339L);
  }

  @Test
  void budgetsNonAsciiPromptsFromUtf8BytesInsteadOfJavaCharactersAlone() {
    String prompt = "\u6570".repeat(1_000);

    long estimate = InputTokenBudgetEstimator.estimate(prompt, "");

    assertThat(prompt.getBytes(StandardCharsets.UTF_8)).hasSize(3_000);
    assertThat(estimate).isEqualTo(1_762L);
  }

  @Test
  void retainsProviderMessageOverheadForEmptyVisibleText() {
    assertThat(InputTokenBudgetEstimator.estimate("", "")).isEqualTo(512L);
  }

  @Test
  void reservesTheObservedMetaReviewTransportOverheadBeforeDispatch() {
    String prompt = "\u6570".repeat(19_316);
    long priorUtf8Estimate =
        (prompt.getBytes(StandardCharsets.UTF_8).length + 2L) / 3L;
    long priorEstimate = (priorUtf8Estimate * 5L + 3L) / 4L + 128L;

    long estimate = InputTokenBudgetEstimator.estimate(prompt, "");

    assertThat(priorEstimate).isEqualTo(24_273L);
    assertThat(estimate).isGreaterThanOrEqualTo(24_348L);
    assertThat(estimate).isEqualTo(24_657L);
  }
}
