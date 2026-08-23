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
    assertThat(estimate).isEqualTo(1_955L);
  }

  @Test
  void budgetsNonAsciiPromptsFromUtf8BytesInsteadOfJavaCharactersAlone() {
    String prompt = "\u6570".repeat(1_000);

    long estimate = InputTokenBudgetEstimator.estimate(prompt, "");

    assertThat(prompt.getBytes(StandardCharsets.UTF_8)).hasSize(3_000);
    assertThat(estimate).isEqualTo(1_378L);
  }

  @Test
  void retainsProviderMessageOverheadForEmptyVisibleText() {
    assertThat(InputTokenBudgetEstimator.estimate("", "")).isEqualTo(128L);
  }
}
