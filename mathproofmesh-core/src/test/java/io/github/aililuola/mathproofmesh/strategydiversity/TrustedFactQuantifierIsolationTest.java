package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedFactQuantifierIsolationTest {
  @Test
  void existentialFactCannotSupportUniversalClaim() {
    var universal = TrustedFactIsolationFixtures.quantifier("T", "forall");
    var existential = TrustedFactIsolationFixtures.quantifier("T", "exists");
    var binding = TrustedFactIsolationFixtures.binding("T", "claim");
    CriticalClaimContext claimContext =
        new CriticalClaimContext(
            List.of(), List.of(universal), List.of(), List.of(binding), "positive");

    boolean supported =
        TrustedFactIsolationFixtures.supports(
            claimContext,
            List.of(),
            List.of(existential),
            List.of(),
            List.of(binding));

    assertThat(supported).isFalse();
  }
}
