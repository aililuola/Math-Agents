package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.MetaPivotEffect;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MetaPivotEffectivenessParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_empty_pivot_not_marked_effective",
        "test_pivot_falls_through_to_next_mechanism",
        "test_pivot_effect_requires_material_state_change",
        "test_deferred_pivot_waits_for_wake_condition");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("MetaPivotEffectivenessParityTest", authorityFunction);
  }

  @Test
  void materializationWithoutVerifiedGainIsNotEffective() {
    MetaPivotController controller = new MetaPivotController();
    var requested = controller.request("route-1", 4, List.of("bridge_lemma"));
    controller.admit(requested.pivotId(), true, "admission-evidence");
    controller.execute(
        requested.pivotId(),
        List.of("bridge_lemma"),
        appliedReceipt(4),
        List.of(),
        "reviewed semantic delta was atomically applied");

    var noGain = controller.evaluate(requested.pivotId(), true);

    assertThat(noGain.outcome().effect()).isEqualTo(MetaPivotEffect.MATERIALIZED_NO_GAIN);
  }

  @Test
  void independentlyReviewedMathematicalGainPromotesMaterializedPivot() {
    MetaPivotController controller = new MetaPivotController();
    var requested = controller.request("route-1", 5, List.of("bridge_lemma"));
    controller.admit(requested.pivotId(), true, "admission-evidence");
    controller.execute(
        requested.pivotId(),
        List.of("bridge_lemma"),
        appliedReceipt(5),
        List.of(),
        "reviewed semantic delta was atomically applied");

    var gained =
        controller.evaluate(
            requested.pivotId(), true, new MetaPivotController.GainEvidence(0, 1, 0, 4.0d, 3.0d));

    assertThat(gained.outcome().effect()).isEqualTo(MetaPivotEffect.EFFECTIVE);
  }

  private static SemanticPivotApplyReceipt appliedReceipt(int round) {
    return new SemanticPivotApplyReceipt(
        null,
        "semantic-pivot-" + round,
        "semantic-delta-hash-" + round,
        "route-1",
        "source-strategy-" + round,
        "strategy-epoch-" + round,
        List.of("obligation-" + round),
        List.of(),
        round,
        true);
  }
}
