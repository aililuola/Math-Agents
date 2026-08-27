package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PivotDeltaValidationTest {
  @Test
  void serverOwnsPivotIdentityAndRejectsDuplicateTransformations() {
    PivotDelta valid = SemanticPivotTestFixtures.validDelta();
    assertThat(valid.pivotId()).startsWith("pivot_");
    assertThat(valid.structuralDeltaHash()).hasSize(64);

    assertThatThrownBy(
            () ->
                new PivotDelta(
                    "model-pivot-id",
                    valid.problemHash(),
                    valid.rootGoalHash(),
                    valid.routeId(),
                    valid.sourceStrategyId(),
                    valid.proposedStrategyId(),
                    valid.transformationTypes(),
                    valid.obstructionRefs(),
                    valid.objectChanges(),
                    valid.directionChanges(),
                    valid.assumptionChanges(),
                    valid.claimUseChanges(),
                    valid.obligationChanges(),
                    valid.proposedStrategy(),
                    valid.rationale(),
                    valid.structuralDeltaHash()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pivotId is server-owned");
    assertThatThrownBy(
            () ->
                PivotDelta.create(
                    valid.problemHash(),
                    valid.rootGoalHash(),
                    valid.routeId(),
                    valid.sourceStrategyId(),
                    List.of(
                        PivotTransformationType.OBJECT_REPLACEMENT,
                        PivotTransformationType.OBJECT_REPLACEMENT),
                    valid.obstructionRefs(),
                    valid.objectChanges(),
                    valid.directionChanges(),
                    valid.assumptionChanges(),
                    valid.claimUseChanges(),
                    valid.obligationChanges(),
                    valid.proposedStrategy(),
                    valid.rationale()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicates");
  }
}
