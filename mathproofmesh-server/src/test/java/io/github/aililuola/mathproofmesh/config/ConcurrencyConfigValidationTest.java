package io.github.aililuola.mathproofmesh.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class ConcurrencyConfigValidationTest {
  @Test
  void defaultsRemainGenericAndSequential() {
    ConcurrencyConfig config = ConcurrencyConfig.defaults();
    assertThat(config.researchSlots()).isEqualTo(1);
    assertThat(config.coordinationSlots()).isZero();
  }

  @Test
  void rejectsSlotsBeyondGlobalRuntimeCapacity() {
    assertThatThrownBy(
            () -> new StrictYamlConfigLoader().read(
                """
                agents:
                  - {id: a, provider: mock, model: mock, roles: [general], max_concurrency: 2}
                runtime: {max_parallel_calls: 2}
                concurrency: {research_slots: 2, coordination_slots: 1, max_in_flight_tasks: 3}
                """))
        .isInstanceOf(ConfigValidationException.class);
  }
}
