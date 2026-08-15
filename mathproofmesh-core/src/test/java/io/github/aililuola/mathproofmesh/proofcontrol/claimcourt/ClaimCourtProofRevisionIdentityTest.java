package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimCourtProofRevisionIdentityTest {
  @Test
  void oneStatementCreatesDistinctCourtCasesForDistinctProofRevisions() {
    var first = ClaimCourtTestFixtures.linearClaim();
    var second =
        ClaimCourtTestFixtures.claim(
            first.claimId(),
            first.statement(),
            first.conclusion(),
            first.assumptions(),
            List.of(
                ClaimCourtTestFixtures.step(
                    "linear-step-v2",
                    "T(x)=T(y) implies T(x-y)=0, so x-y=0 and x=y.",
                    "Use linearity and ker(T)={0}.")));

    FrozenClaimSnapshot firstFrozen = ClaimCourtTestFixtures.freeze(first);
    FrozenClaimSnapshot secondFrozen = ClaimCourtTestFixtures.freeze(second);

    assertThat(firstFrozen.claimSemanticHash()).isEqualTo(secondFrozen.claimSemanticHash());
    assertThat(firstFrozen.initialProofRevisionId())
        .isNotEqualTo(secondFrozen.initialProofRevisionId());
    assertThat(firstFrozen.courtCaseId()).isNotEqualTo(secondFrozen.courtCaseId());
  }

  @Test
  void legacyCaseIdIsResolvedByTheSameExactProofIdentity() {
    FrozenClaimSnapshot current = ClaimCourtTestFixtures.freeze(ClaimCourtTestFixtures.linearClaim());
    FrozenClaimSnapshot legacy =
        new FrozenClaimSnapshot(
            "claim-court-legacy-id",
            current.problemHash(),
            current.rootGoalHash(),
            current.claimId(),
            current.claimStatementHash(),
            current.claimSemanticHash(),
            current.statement(),
            current.conclusion(),
            current.assumptions(),
            current.quantifiers(),
            current.variableBindings(),
            current.scopeLimitations(),
            current.polarity(),
            current.dependencyClaimIds(),
            current.dependencySnapshotHash(),
            current.initialProofRevisionId(),
            current.sourceAttemptId(),
            current.sourceRouteId(),
            current.authorAgentId());
    ClaimCourtLedger ledger = new ClaimCourtLedger();
    ledger.open(legacy, ClaimCourtTestFixtures.roles());

    assertThat(ledger.findProofCase(current))
        .get()
        .extracting(ClaimCourtRecord::courtCaseId)
        .isEqualTo(legacy.courtCaseId());
  }
}
