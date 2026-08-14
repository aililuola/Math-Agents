package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PivotObligationAuthorityBoundaryTest {
  @Test
  void focusRetirementCannotRewriteOrCloseAnOldObligation() {
    assertThatThrownBy(
            () ->
                new PivotObligationChange(
                    SemanticPivotTestFixtures.OLD_OBLIGATION,
                    SemanticPivotTestFixtures.OLD_TARGET,
                    PivotObligationAction.RETIRE_FROM_STRATEGY_FOCUS,
                    "rewritten old target",
                    io.github.aililuola.mathproofmesh.contract.ObligationKind.SUBGOAL,
                    List.of(),
                    List.of(),
                    "close it"))
        .isInstanceOf(IllegalArgumentException.class);

    PivotDelta valid = SemanticPivotTestFixtures.validDelta();
    PivotDelta unknownRetirement =
        PivotDelta.create(
            valid.problemHash(),
            valid.rootGoalHash(),
            valid.routeId(),
            valid.sourceStrategyId(),
            valid.transformationTypes(),
            valid.obstructionRefs(),
            valid.objectChanges(),
            valid.directionChanges(),
            valid.assumptionChanges(),
            valid.claimUseChanges(),
            List.of(
                new PivotObligationChange(
                    "unknown-obligation",
                    "unknown-target",
                    PivotObligationAction.RETIRE_FROM_STRATEGY_FOCUS,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    "retire only from focus")),
            valid.proposedStrategy(),
            valid.rationale());
    assertThat(
            new SemanticPivotDeterministicAuditor()
                .audit(
                    unknownRetirement,
                    SemanticPivotTestFixtures.sourceSignature(),
                    SemanticPivotTestFixtures.proposedSignature(),
                    SemanticPivotTestFixtures.authority())
                .failureCodes())
        .contains(SemanticPivotDeterministicAuditor.UNAUTHORIZED_OBLIGATION_CLOSURE);
  }
}
