package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerArtifactNoFalseUtilityTest {
  @Test
  void deliveryParsingAndUnrelatedStepsCreateNoUtility() {
    var scenario = BrokerArtifactTestFixtures.delivered(2.0d);
    var receipt =
        scenario
            .broker()
            .acknowledge("provider-request-1", null, Set.of("unrelated-step"))
            .getFirst();
    assertThat(
            scenario
                .broker()
                .verifyEffect(
                    receipt.deliveryId(),
                    new BrokerArtifactEffectObservation(
                        Set.of("unrelated-step"),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        null,
                        null,
                        null,
                        null,
                        false,
                        1.0d)))
        .isEmpty();
    assertThat(scenario.broker().utilities()).isEmpty();
    assertThat(scenario.broker().lineage()).isEmpty();
  }
}
