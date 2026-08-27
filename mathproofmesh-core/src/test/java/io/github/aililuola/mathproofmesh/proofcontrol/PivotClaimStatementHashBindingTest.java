package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PivotClaimStatementHashBindingTest {
  private final SemanticPivotDeterministicAuditor auditor =
      new SemanticPivotDeterministicAuditor();

  @Test
  void knownClaimCannotBeReferencedWithAnUnboundStatementHash() {
    PivotDelta delta =
        withClaims(
            List.of(
                new PivotClaimUseChange(
                    SemanticPivotTestFixtures.VERIFIED_CLAIM,
                    "wrong-statement-hash",
                    PivotClaimUsageAction.RETAIN_AS_VERIFIED_FACT,
                    "Retain the previously verified claim.")));

    PivotDeltaAudit audit =
        auditor.audit(
            delta,
            SemanticPivotTestFixtures.sourceSignature(),
            SemanticPivotTestFixtures.proposedSignature(),
            SemanticPivotTestFixtures.authority());

    assertThat(audit.failureCodes()).contains("CLAIM_STATEMENT_HASH_MISMATCH");
    System.out.println("KNOWN_CLAIM_WRONG_HASH_REJECTIONS=1");
  }

  @Test
  void proposedClaimWithoutMaterializableDraftCannotCreateAStateDelta() {
    PivotDelta delta =
        withClaims(
            List.of(
                new PivotClaimUseChange(
                    "ghost-proposed-claim",
                    "ghost-statement-hash",
                    PivotClaimUsageAction.ADD_AS_PROPOSED_CLAIM,
                    "Add a new claim without supplying its mathematical statement.")));

    PivotDeltaAudit audit =
        auditor.audit(
            delta,
            SemanticPivotTestFixtures.sourceSignature(),
            SemanticPivotTestFixtures.proposedSignature(),
            SemanticPivotTestFixtures.authority());

    assertThat(audit.failureCodes()).contains("UNMATERIALIZABLE_PROPOSED_CLAIM");
    System.out.println("GHOST_PROPOSED_CLAIM_REJECTIONS=1");
  }

  @Test
  void completeProposedClaimDraftIsBoundToItsNormalizedStatementHash() {
    String claimId = "materializable-proposed-claim";
    String statement = "Every minimal support family contains a globally maximal witness.";
    String statementHash = PivotProposedClaimDraft.statementHash(statement);
    PivotDelta delta =
        withClaims(
            List.of(
                new PivotClaimUseChange(
                    claimId,
                    statementHash,
                    PivotClaimUsageAction.ADD_AS_PROPOSED_CLAIM,
                    "Add a bounded candidate lemma for independent review.",
                    new PivotProposedClaimDraft(
                        claimId,
                        statement,
                        statementHash,
                        List.of("the support family is nonempty"),
                        List.of("artifact://pivot/support-family"),
                        List.of(),
                        List.of("candidate:global-support")))));

    PivotDeltaAudit audit =
        auditor.audit(
            delta,
            SemanticPivotTestFixtures.sourceSignature(),
            SemanticPivotTestFixtures.proposedSignature(),
            SemanticPivotTestFixtures.authority());

    assertThat(audit.failureCodes()).isEmpty();
  }

  private static PivotDelta withClaims(List<PivotClaimUseChange> claims) {
    PivotDelta source = SemanticPivotTestFixtures.validDelta();
    return PivotDelta.create(
        source.problemHash(),
        source.rootGoalHash(),
        source.routeId(),
        source.sourceStrategyId(),
        source.transformationTypes(),
        source.obstructionRefs(),
        source.objectChanges(),
        source.directionChanges(),
        source.assumptionChanges(),
        claims,
        source.obligationChanges(),
        source.proposedStrategy(),
        source.rationale());
  }
}
