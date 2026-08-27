package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimStatementAssessment;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementFalsificationDecision;
import io.github.aililuola.mathproofmesh.contract.StatementCounterexampleCandidate;
import io.github.aililuola.mathproofmesh.contract.StatementFalsificationDisposition;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimStatementFalsificationAuthorityTest {
  @Test
  void modelCandidateCannotRefuteButExactTrustedEvidenceCan() {
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(ClaimCourtTestFixtures.linearClaim());
    ClaimStatementFalsificationDecision modelCandidate =
        new ClaimStatementFalsificationDecision(
            frozen.claimId(),
            StatementFalsificationDisposition.COUNTEREXAMPLE_CANDIDATE,
            List.of(
                new StatementCounterexampleCandidate(
                    "candidate-witness",
                    frozen.claimId(),
                    frozen.claimStatementHash(),
                    "A model-proposed witness",
                    frozen.assumptions(),
                    frozen.quantifiers(),
                    frozen.scopeLimitations(),
                    frozen.polarity(),
                    List.of("research://candidate-only"))),
            "Candidate only");
    ClaimStatementAuthorityService service = new ClaimStatementAuthorityService();
    ClaimStatementAuthorityService.Result untrusted =
        service.assess(
            frozen, modelCandidate, new NegativeKnowledgeRegistry(), 0, List.of());
    assertThat(untrusted.assessment())
        .isEqualTo(ClaimStatementAssessment.OPEN_NO_VERIFIED_REFUTATION);

    ClaimRefutationEvidence trusted =
        new ClaimRefutationEvidence(
            "formal-refutation-1",
            ClaimRefutationEvidenceType.FORMAL_CERTIFICATE,
            frozen.claimId(),
            frozen.claimStatementHash(),
            frozen.claimSemanticHash(),
            "machine-checked countermodel",
            "formal://certificate/1",
            true,
            true);
    ClaimStatementAuthorityService.Result refuted =
        service.assess(
            frozen,
            modelCandidate,
            new NegativeKnowledgeRegistry(),
            0,
            List.of(trusted));
    assertThat(refuted.assessment())
        .isEqualTo(ClaimStatementAssessment.REFUTED_BY_VERIFIED_EVIDENCE);
    assertThat(refuted.evidenceIds()).containsExactly(trusted.evidenceId());
  }
}
