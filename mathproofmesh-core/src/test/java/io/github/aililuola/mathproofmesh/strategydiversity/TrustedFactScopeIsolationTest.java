package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedFactScopeIsolationTest {
  @Test
  void eventualFactCannotSupportGlobalClaim() {
    CriticalClaimContext global =
        new CriticalClaimContext(
            List.of(), List.of(), List.of("index_scope=all"), List.of(), "positive");

    boolean supported =
        TrustedFactIsolationFixtures.supports(
            global,
            List.of(),
            List.of(),
            List.of("index_scope=eventual"),
            List.of());

    assertThat(supported).isFalse();
  }
}
