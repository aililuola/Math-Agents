package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObligationCanonicalizationRegistryTest {
  @Test
  void exactTargetsMergeWhilePossibleEquivalentsAreOnlyQuarantined() {
    ObligationCanonicalizationRegistry registry = new ObligationCanonicalizationRegistry();
    ProofObligation exactA = obligation("a", "r1", "prove p for every positive integer n");
    ProofObligation exactB = obligation("b", "r2", "prove p for every positive integer n");
    ProofObligation possible = obligation("c", "r3", "prove p for each positive integer n");

    CanonicalizedObligationWriteResult first = registry.register(exactA, context(exactA, "r1"));
    CanonicalizedObligationWriteResult second = registry.register(exactB, context(exactB, "r2"));
    CanonicalizedObligationWriteResult third = registry.register(possible, context(possible, "r3"));

    assertThat(second.canonicalTarget().canonicalTargetId())
        .isEqualTo(first.canonicalTarget().canonicalTargetId());
    assertThat(registry.occurrences()).hasSize(3);
    assertThat(registry.canonicalTargets()).hasSize(2);
    assertThat(third.possibleEquivalentQuarantined()).isTrue();
    assertThat(registry.possibleEquivalentQuarantines()).isEqualTo(1);
    assertThat(registry.unsafeHardMerges()).isZero();
    assertThat(registry.snapshot().canonicalBySignature()).hasSize(2);
  }

  private static ProofObligation obligation(String id, String route, String normalized) {
    return ObligationCanonicalizationTestFixtures.obligation(
        id, route, normalized, normalized, "shared-family");
  }

  private static ObligationCreationContext context(ProofObligation obligation, String route) {
    return ObligationCanonicalizationTestFixtures.context(
        obligation, route, "shared-family", List.of("all"), "positive", Map.of(), 0);
  }
}
