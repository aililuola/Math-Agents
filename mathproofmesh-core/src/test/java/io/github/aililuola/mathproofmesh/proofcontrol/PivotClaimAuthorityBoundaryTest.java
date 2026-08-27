package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PivotClaimAuthorityBoundaryTest {
  @Test
  void pivotCannotDeclareAVerifiedClaimFalseOrRetainAnUnknownFact() {
    PivotDelta valid = SemanticPivotTestFixtures.validDelta();
    PivotDelta invalid =
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
            List.of(
                new PivotClaimUseChange(
                    SemanticPivotTestFixtures.VERIFIED_CLAIM,
                    "verified-claim-hash",
                    PivotClaimUsageAction.RETIRE_FROM_ACTIVE_DEPENDENCY,
                    "This verified claim is false.")),
            valid.obligationChanges(),
            valid.proposedStrategy(),
            valid.rationale());
    PivotDeltaAudit audit =
        new SemanticPivotDeterministicAuditor()
            .audit(
                invalid,
                SemanticPivotTestFixtures.sourceSignature(),
                SemanticPivotTestFixtures.proposedSignature(),
                SemanticPivotTestFixtures.authority());
    assertThat(audit.failureCodes())
        .contains(SemanticPivotDeterministicAuditor.UNAUTHORIZED_CLAIM_RETIREMENT);
  }
}
