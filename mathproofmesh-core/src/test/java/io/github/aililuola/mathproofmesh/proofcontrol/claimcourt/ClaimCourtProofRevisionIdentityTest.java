package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
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
  void distinctClaimsWithTheSameEmptyProofReceiveDistinctRevisionIdentities() {
    FrozenClaimSnapshot first =
        ClaimCourtTestFixtures.freeze(
            ClaimCourtTestFixtures.claim(
                "empty-proof-one",
                "Every multiple of four is even.",
                "The integer is even.",
                List.of("The integer is divisible by four."),
                List.of()));
    FrozenClaimSnapshot second =
        ClaimCourtTestFixtures.freeze(
            ClaimCourtTestFixtures.claim(
                "empty-proof-two",
                "Every multiple of six is divisible by three.",
                "The integer is divisible by three.",
                List.of("The integer is divisible by six."),
                List.of()));

    assertThat(first.initialProofRevisionId()).isNotEqualTo(second.initialProofRevisionId());
    assertThat(first.courtCaseId()).isNotEqualTo(second.courtCaseId());
  }

  @Test
  void identicalClaimProofsRemainStableAcrossAttemptProvenance() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot first = ClaimCourtTestFixtures.freeze(claim);
    FrozenClaimSnapshot laterAttempt =
        new ClaimFreezeService()
            .freeze(
                first.problemHash(),
                first.rootGoalHash(),
                "later-route",
                new io.github.aililuola.mathproofmesh.contract.ClaimCard(
                    claim.assumptions(),
                    claim.claimId(),
                    claim.conclusion(),
                    claim.contentHash(),
                    claim.counterexampleRisk(),
                    claim.dependencies(),
                    claim.dependencyRefs(),
                    claim.evidenceRefs(),
                    claim.proofSteps(),
                    claim.scopeLimitations(),
                    claim.selfConfidence(),
                    claim.sourceAgentId(),
                    "later-attempt",
                    claim.sourceDeltaId(),
                    claim.statement(),
                    claim.status(),
                    claim.tags(),
                    claim.verificationConfidence()),
                new FrozenClaimSemanticContext(
                    first.assumptions(),
                    first.quantifiers(),
                    first.variableBindings(),
                    first.scopeLimitations(),
                    first.polarity()));

    assertThat(laterAttempt.initialProofRevisionId()).isEqualTo(first.initialProofRevisionId());
    assertThat(laterAttempt.courtCaseId()).isEqualTo(first.courtCaseId());
  }

  @Test
  void legacyCaseIdIsResolvedByTheSameExactProofIdentity() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot current = ClaimCourtTestFixtures.freeze(claim);
    String legacyRevisionId =
        "claim-proof-original-"
            + CanonicalJson.stableHash(claim.proofSteps()).substring(0, 24);
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
            legacyRevisionId,
            current.sourceAttemptId(),
            current.sourceRouteId(),
            current.authorAgentId());
    ClaimCourtLedger ledger = new ClaimCourtLedger();
    ledger.open(legacy, ClaimCourtTestFixtures.roles());

    assertThat(current.initialProofRevisionId()).isNotEqualTo(legacyRevisionId);
    assertThat(ledger.findProofCase(current))
        .get()
        .extracting(ClaimCourtRecord::courtCaseId)
        .isEqualTo(legacy.courtCaseId());
  }
}
