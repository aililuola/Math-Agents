package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CanonicalProofTaskLeaseTest {
  @Test
  void aScopeAndActionLeaseCanBeAcquiredExactlyOnce() {
    ObligationCanonicalizationRegistry registry = new ObligationCanonicalizationRegistry();
    assertThat(registry.acquireTaskLease(ProofTaskScope.BOTTLENECK_FAMILY, "family", "repair"))
        .isTrue();
    assertThat(registry.acquireTaskLease(ProofTaskScope.BOTTLENECK_FAMILY, "family", "repair"))
        .isFalse();
    assertThat(registry.acquireTaskLease(ProofTaskScope.BOTTLENECK_FAMILY, "family", "falsify"))
        .isTrue();
    assertThat(registry.acquireTaskLease(ProofTaskScope.CANONICAL_TARGET, "family", "repair"))
        .isTrue();
  }
}
