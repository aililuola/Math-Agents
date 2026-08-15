package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedFactAssumptionIsolationTest {
  @Test
  void conditionalFactCannotSupportUnconditionalClaim() {
    boolean supported =
        TrustedFactIsolationFixtures.supports(
            CriticalClaimContext.empty(),
            List.of("ker(T)={0}"),
            List.of(),
            List.of(),
            List.of());

    assertThat(supported).isFalse();
  }
}
