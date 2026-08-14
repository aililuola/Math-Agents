package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BottleneckFamilyRegistryTest {
  @Test
  void distinctTargetsShareOnlyAStableSchedulingFamily() {
    ObligationCanonicalizationRegistry registry = new ObligationCanonicalizationRegistry();
    ProofObligation first = obligation("first", "r1", "prove lemma alpha");
    ProofObligation second = obligation("second", "r2", "construct witness beta");
    CanonicalizedObligationWriteResult firstResult = registry.register(first, context(first, "r1"));
    String familyId = firstResult.bottleneckFamily().familyId();
    CanonicalizedObligationWriteResult secondResult = registry.register(second, context(second, "r2"));

    assertThat(registry.canonicalTargets()).hasSize(2);
    assertThat(registry.bottleneckFamilies()).singleElement()
        .satisfies(
            family -> {
              assertThat(family.familyId()).isEqualTo(familyId);
              assertThat(family.canonicalTargetIds()).hasSize(2);
              assertThat(family.memberRelations().values())
                  .allMatch(value -> value != BottleneckRelationType.DISTINCT);
            });
    assertThat(secondResult.canonicalTarget().canonicalTargetId())
        .isNotEqualTo(firstResult.canonicalTarget().canonicalTargetId());
  }

  private static ProofObligation obligation(String id, String route, String statement) {
    return ObligationCanonicalizationTestFixtures.obligation(
        id, route, statement, statement, "same-upstream-bottleneck");
  }

  private static ObligationCreationContext context(ProofObligation obligation, String route) {
    return ObligationCanonicalizationTestFixtures.context(
        obligation,
        route,
        "same-upstream-bottleneck",
        List.of(),
        "positive",
        Map.of(),
        0);
  }
}
