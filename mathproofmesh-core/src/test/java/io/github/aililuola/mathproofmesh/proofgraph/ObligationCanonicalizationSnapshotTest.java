package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObligationCanonicalizationSnapshotTest {
  @Test
  void snapshotRoundTripPreservesStableIdsAlternativesFamiliesAndLeases() throws Exception {
    ProofGraphStore graph = new ProofGraphStore(ObligationCanonicalizationTestFixtures.PROBLEM_HASH);
    for (int index = 0; index < 3; index++) {
      ProofObligation obligation =
          ObligationCanonicalizationTestFixtures.obligation(
              "snapshot-" + index,
              "route-" + index,
              index < 2 ? "same target" : "sibling target",
              index < 2 ? "same target" : "sibling target",
              "snapshot-family");
      graph.addObligationCanonicalized(
          obligation,
          ObligationCanonicalizationTestFixtures.context(
              obligation,
              "route-" + index,
              "snapshot-family",
              List.of(),
              "positive",
              Map.of(),
              index));
    }
    String familyId = graph.allBottleneckFamilies().getFirst().familyId();
    assertThat(graph.acquireCanonicalTaskLease(ProofTaskScope.BOTTLENECK_FAMILY, familyId, "repair"))
        .isTrue();
    String before = graph.canonicalizationHash();

    ProofGraphSnapshot decoded =
        ContractObjectMapper.read(
            ContractObjectMapper.write(graph.snapshot()), ProofGraphSnapshot.class);
    ProofGraphStore restored = ProofGraphStore.restore(decoded, ProofGraphPolicy.defaults());

    assertThat(restored.canonicalizationHash()).isEqualTo(before);
    assertThat(restored.rawObligationOccurrences()).hasSize(3);
    assertThat(restored.allCanonicalTargets()).hasSize(2);
    assertThat(restored.allBottleneckFamilies()).hasSize(1);
    assertThat(restored.hasCanonicalTaskLease(
            ProofTaskScope.BOTTLENECK_FAMILY, familyId, "repair"))
        .isTrue();
  }
}
