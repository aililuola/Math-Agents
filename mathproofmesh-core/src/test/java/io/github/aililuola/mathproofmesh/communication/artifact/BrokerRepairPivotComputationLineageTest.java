package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerRepairPivotComputationLineageTest {
  @Test
  void unrelatedRouteEffectsCannotSatisfyAnUnboundLineage() {
    BrokerArtifactEffectVerifier verifier = new BrokerArtifactEffectVerifier();
    BrokerDeliveryBaseline baseline = baseline();
    int repairs = verified(verifier, lineage(BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR), baseline,
        observation("unrelated-repair", null, null));
    int pivots = verified(verifier, lineage(BrokerArtifactUseKind.TRIGGERS_SEMANTIC_PIVOT), baseline,
        observation(null, "unrelated-pivot", null));
    int computations = verified(
        verifier, lineage(BrokerArtifactUseKind.SUPPORTS_COMPUTATION_PLAN), baseline,
        observation(null, null, "unrelated-computation"));

    System.out.println("UNRELATED_REPAIR_EFFECTS=" + repairs);
    System.out.println("UNRELATED_PIVOT_EFFECTS=" + pivots);
    System.out.println("UNRELATED_COMPUTATION_EFFECTS=" + computations);
    assertThat(repairs).isZero();
    assertThat(pivots).isZero();
    assertThat(computations).isZero();
  }

  @Test
  void onlyTheServerBoundPostBaselineEffectIdentityCanVerifyLineage() {
    BrokerArtifactEffectVerifier verifier = new BrokerArtifactEffectVerifier();
    BrokerDeliveryBaseline baseline = baseline();
    var repair = lineage(BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR)
        .bindEffectTarget("repair-exact");
    var pivot = lineage(BrokerArtifactUseKind.TRIGGERS_SEMANTIC_PIVOT)
        .bindEffectTarget("pivot-exact");
    var computation = lineage(BrokerArtifactUseKind.SUPPORTS_COMPUTATION_PLAN)
        .bindEffectTarget("computation-exact");

    assertThat(verifier.verify(repair, baseline,
        observation("repair-exact", null, null)).verified()).isTrue();
    assertThat(verifier.verify(pivot, baseline,
        observation(null, "pivot-exact", null)).verified()).isTrue();
    assertThat(verifier.verify(computation, baseline,
        observation(null, null, "computation-exact")).verified()).isTrue();
    assertThat(verifier.verify(repair, baseline,
        observation("repair-other", null, null)).verified()).isFalse();
  }

  private static int verified(
      BrokerArtifactEffectVerifier verifier,
      BrokerArtifactLineageRecord lineage,
      BrokerDeliveryBaseline baseline,
      BrokerArtifactEffectObservation observation) {
    return verifier.verify(lineage, baseline, observation).verified() ? 1 : 0;
  }

  private static BrokerArtifactLineageRecord lineage(BrokerArtifactUseKind kind) {
    return new BrokerArtifactLineageRecord(
        "lineage-" + kind, "artifact-1", "delivery-1", kind, List.of(), List.of(),
        List.of("target-tree"), null, null, "request-1", false);
  }

  private static BrokerDeliveryBaseline baseline() {
    return new BrokerDeliveryBaseline(
        "delivery-1", "route-b", "request-1", 0, 1.0d, Set.of("target-tree"),
        Set.of(), Set.of(), "strategy-1", "target-tree");
  }

  private static BrokerArtifactEffectObservation observation(
      String repairId, String pivotId, String computationId) {
    return new BrokerArtifactEffectObservation(
        Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), "target-tree", repairId, pivotId,
        computationId, false, 1.0d);
  }
}
