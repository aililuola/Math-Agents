package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PivotRootGoalPreservationTest {
  @Test
  void rootGoalHashMutationFailsClosed() {
    PivotDelta valid = SemanticPivotTestFixtures.validDelta();
    PivotDelta mutated =
        PivotDelta.create(
            valid.problemHash(),
            "different-root",
            valid.routeId(),
            valid.sourceStrategyId(),
            valid.transformationTypes(),
            valid.obstructionRefs(),
            valid.objectChanges(),
            valid.directionChanges(),
            valid.assumptionChanges(),
            valid.claimUseChanges(),
            valid.obligationChanges(),
            valid.proposedStrategy(),
            valid.rationale());
    assertThat(
            new SemanticPivotDeterministicAuditor()
                .audit(
                    mutated,
                    SemanticPivotTestFixtures.sourceSignature(),
                    SemanticPivotTestFixtures.proposedSignature(),
                    SemanticPivotTestFixtures.authority())
                .failureCodes())
        .contains(SemanticPivotDeterministicAuditor.ROOT_GOAL_MISMATCH);
  }
}
