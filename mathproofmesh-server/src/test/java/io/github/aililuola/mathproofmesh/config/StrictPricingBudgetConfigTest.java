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
}
