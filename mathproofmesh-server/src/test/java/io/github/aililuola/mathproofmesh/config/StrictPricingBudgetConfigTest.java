package io.github.aililuola.mathproofmesh.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class StrictPricingBudgetConfigTest {
  private static final StrictYamlConfigLoader LOADER = new StrictYamlConfigLoader();

  @Test
  void costCapRejectsRemoteProviderWithUnknownZeroPricing() {
    assertThatThrownBy(
            () ->
                LOADER.read(
                    """
                    agents:
                      - id: remote
                        provider: deepseek
                        model: deepseek-test
                        api_key: fixture
                    budget:
                      max_cost_usd: 0.75
                    """))
        .isInstanceOf(ConfigValidationException.class)
        .hasMessageContaining("UNPRICED_PROVIDER");
  }

  @Test
  void explicitMockExemptionAndPricedRemoteProviderRemainValid() {
    SystemConfig mock =
        LOADER.read(
            """
            agents:
              - id: local-fixture
                provider: mock
                model: deterministic
            budget:
              max_cost_usd: 0.75
            """);
    SystemConfig priced =
        LOADER.read(
            """
            agents:
              - id: remote
                provider: deepseek
                model: deepseek-test
                api_key: fixture
                pricing:
                  input_per_million: 0.5
                  output_per_million: 1.25
            budget:
              max_cost_usd: 0.75
            """);

    assertThat(mock.agents().getFirst().provider()).isEqualTo("mock");
    assertThat(priced.agents().getFirst().pricing().outputPerMillion()).isEqualTo(1.25d);
  }

  @Test
  void inputTokenPlanningHeadroomIsExplicitBoundedAndBackwardCompatible() {
    SystemConfig defaults =
        LOADER.read(
            """
            agents:
              - id: local-fixture
                provider: mock
                model: deterministic
            """);
    SystemConfig configured =
        LOADER.read(
            """
            agents:
              - id: local-fixture
                provider: mock
                model: deterministic
            budget:
              estimated_input_tokens_per_call: 16000
            """);

    assertThat(defaults.budget().estimatedInputTokensPerCall()).isNull();
    assertThat(defaults.budget().effectiveEstimatedInputTokensPerCall()).isEqualTo(2_000);
    assertThat(configured.budget().estimatedInputTokensPerCall()).isEqualTo(16_000);
    assertThatThrownBy(
            () ->
                LOADER.read(
                    """
                    agents:
                      - id: local-fixture
                        provider: mock
                        model: deterministic
                    budget:
                      estimated_input_tokens_per_call: 127
                    """))
        .isInstanceOf(ConfigValidationException.class)
        .hasMessageContaining("estimated_input_tokens_per_call");
  }
}
