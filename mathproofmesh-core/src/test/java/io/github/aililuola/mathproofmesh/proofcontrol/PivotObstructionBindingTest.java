package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PivotObstructionBindingTest {
  @Test
  void unknownAndCrossScopeObstructionsAreRejected() {
    PivotDelta delta = SemanticPivotTestFixtures.validDelta();
    PivotAuthorityContext unknown = authority(Map.of(), SemanticPivotTestFixtures.PROBLEM_HASH);
    assertThat(audit(delta, unknown).failureCodes())
        .contains(SemanticPivotDeterministicAuditor.UNKNOWN_OBSTRUCTION);

    PivotObstructionRef crossRoute =
        new PivotObstructionRef(
            SemanticPivotTestFixtures.OBSTRUCTION_ID,
            PivotEvidenceAuthority.VERIFIED_COUNTEREXAMPLE,
            "attempt-artifact://counterexample-1",
            "route-other",
            SemanticPivotTestFixtures.SOURCE_ID,
            SemanticPivotTestFixtures.OLD_TARGET,
            "statement-hash");
    PivotDelta crossRouteDelta =
        PivotDelta.create(
            delta.problemHash(),
            delta.rootGoalHash(),
            delta.routeId(),
            delta.sourceStrategyId(),
            delta.transformationTypes(),
            List.of(crossRoute),
            delta.objectChanges(),
            delta.directionChanges(),
            delta.assumptionChanges(),
            delta.claimUseChanges(),
            delta.obligationChanges(),
            delta.proposedStrategy(),
            delta.rationale());
    PivotAuthorityContext crossScope =
        authority(
            Map.of(
                crossRoute.obstructionId(),
                new PivotAuthorityContext.KnownObstruction(
                    crossRoute, "another-problem", true)),
            SemanticPivotTestFixtures.PROBLEM_HASH);
    assertThat(audit(crossRouteDelta, crossScope).failureCodes())
        .contains(SemanticPivotDeterministicAuditor.OBSTRUCTION_SCOPE_MISMATCH);
  }

  private static PivotDeltaAudit audit(PivotDelta delta, PivotAuthorityContext authority) {
    return new SemanticPivotDeterministicAuditor()
        .audit(
            delta,
            SemanticPivotTestFixtures.sourceSignature(),
            SemanticPivotTestFixtures.proposedSignature(),
            authority);
  }

  private static PivotAuthorityContext authority(
      Map<String, PivotAuthorityContext.KnownObstruction> obstructions, String problemHash) {
    PivotAuthorityContext source = SemanticPivotTestFixtures.authority();
    return new PivotAuthorityContext(
        problemHash,
        source.rootGoalHash(),
        source.routeId(),
        source.sourceStrategyId(),
        obstructions,
        source.activeObjectIds(),
        source.activeCanonicalTargetIds(),
        source.knownObligationIds(),
        source.verifiedClaimIds(),
        source.knownClaimIds(),
        source.knownClaimStatementHashes(),
        source.permanentNegativeConflicts(),
        source.selectedFamilyId(),
        source.selectedCanonicalTargetIds(),
        source.focusedRecovery(),
        source.capacityAvailable());
  }
}
