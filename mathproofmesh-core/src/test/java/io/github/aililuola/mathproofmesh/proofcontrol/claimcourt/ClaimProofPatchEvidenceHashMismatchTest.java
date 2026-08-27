package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimProofPatch;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperation;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperationType;
import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ClaimProofPatchEvidenceHashMismatchTest {
  private static final String TRUSTED_HASH =
      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

  @Test
  void rejectsContentHashThatDoesNotMatchTrustedArtifact() {
    Scenario scenario = scenario();
    EvidenceRef supplied =
        new EvidenceRef(
            "artifact://verified-computation",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "certificate",
            "Mismatched evidence.");

    var result =
        validate(
            scenario,
            supplied,
            List.of(trusted(scenario.frozen(), trustedReference())));

    assertThat(result.passed()).isFalse();
    assertThat(result.failureCodes())
        .contains(ClaimTrustedEvidenceResolver.EVIDENCE_CONTENT_HASH_MISMATCH);
  }

  @Test
  void acceptsOnlyTheCanonicalReferenceFromVerifiedAuthority() {
    Scenario scenario = scenario();
    EvidenceRef supplied =
        new EvidenceRef(
            "artifact://verified-computation",
            TRUSTED_HASH,
            "certificate",
            "Model-provided summary is not authoritative.");
    EvidenceRef canonical = trustedReference();

    var result =
        validate(
            scenario,
            supplied,
            List.of(trusted(scenario.frozen(), canonical)));

    assertThat(result.passed()).isTrue();
    assertThat(result.evidenceRefs()).contains(canonical).doesNotContain(supplied);
    assertThat(result.proofSteps().getFirst().calculationEvidenceRefs())
        .containsExactly(canonical);
    long verifiedAttempts = 1L;
    long verifiedAccepts = result.passed() ? 1L : 0L;
    assertThat(verifiedAccepts).isEqualTo(1L);
    System.out.println("VERIFIED_EVIDENCE_PATCH_ATTEMPTS=" + verifiedAttempts);
    System.out.println("VERIFIED_EVIDENCE_PATCH_ACCEPTS=" + verifiedAccepts);
    System.out.println("RESULT=PASS");
  }

  @Test
  void rejectsEveryAuthorityBoundaryWithAStableCode() {
    Scenario scenario = scenario();
    EvidenceRef reference = trustedReference();
    ClaimTrustedEvidenceResolver resolver = new ClaimTrustedEvidenceResolver();

    assertThat(
            resolver
                .resolve(
                    scenario.frozen(),
                    reference,
                    List.of(
                        evidence(
                            reference,
                            "other-problem",
                            scenario.frozen().claimSemanticHash(),
                            true,
                            true,
                            true,
                            true)))
                .failureCode())
        .isEqualTo(ClaimTrustedEvidenceResolver.EVIDENCE_PROBLEM_SCOPE_MISMATCH);
    assertThat(
            resolver
                .resolve(
                    scenario.frozen(),
                    reference,
                    List.of(
                        evidence(
                            reference,
                            scenario.frozen().problemHash(),
                            "other-claim",
                            true,
                            true,
                            true,
                            true)))
                .failureCode())
        .isEqualTo(ClaimTrustedEvidenceResolver.EVIDENCE_CLAIM_SCOPE_MISMATCH);
    assertThat(
            resolver
                .resolve(
                    scenario.frozen(),
                    reference,
                    List.of(
                        evidence(
                            reference,
                            scenario.frozen().problemHash(),
                            scenario.frozen().claimSemanticHash(),
                            false,
                            true,
                            true,
                            true)))
                .failureCode())
        .isEqualTo(ClaimTrustedEvidenceResolver.EVIDENCE_NOT_VERIFIED);
    assertThat(
            resolver
                .resolve(
                    scenario.frozen(),
                    reference,
                    List.of(
                        evidence(
                            reference,
                            scenario.frozen().problemHash(),
                            scenario.frozen().claimSemanticHash(),
                            true,
                            false,
                            true,
                            true)))
                .failureCode())
        .isEqualTo(ClaimTrustedEvidenceResolver.EVIDENCE_REPLAY_NOT_VERIFIED);
    assertThat(
            resolver
                .resolve(
                    scenario.frozen(),
                    reference,
                    List.of(
                        evidence(
                            reference,
                            scenario.frozen().problemHash(),
                            scenario.frozen().claimSemanticHash(),
                            true,
                            true,
                            false,
                            true)))
                .failureCode())
        .isEqualTo(ClaimTrustedEvidenceResolver.EVIDENCE_AUTHORITY_ESCALATION);
  }

  private static ClaimProofPatchValidator.ValidationResult validate(
      Scenario scenario,
      EvidenceRef supplied,
      List<TrustedClaimEvidence> trusted) {
    return new ClaimProofPatchValidator(ClaimCourtConfig.defaults())
        .validate(
            scenario.frozen(),
            scenario.base(),
            scenario.audit(),
            patch(scenario, supplied),
            Set.of(),
            trusted);
  }

  private static ClaimProofPatch patch(Scenario scenario, EvidenceRef supplied) {
    return new ClaimProofPatch(
        "patch-evidence",
        scenario.frozen().claimId(),
        scenario.frozen().claimSemanticHash(),
        scenario.base().revisionId(),
        scenario.base().proofHash(),
        List.of("issue-linear-step"),
        List.of("linear-step"),
        List.of(
            new ClaimProofPatchOperation(
                "add-evidence",
                ClaimProofPatchOperationType.ADD_VERIFIED_EVIDENCE_REF,
                "linear-step",
                null,
                null,
                null,
                null,
                supplied)),
        List.of("issue-linear-step"));
  }

  private static TrustedClaimEvidence trusted(
      FrozenClaimSnapshot frozen, EvidenceRef reference) {
    return evidence(
        reference,
        frozen.problemHash(),
        frozen.claimSemanticHash(),
        true,
        true,
        true,
        true);
  }

  private static TrustedClaimEvidence evidence(
      EvidenceRef reference,
      String problemHash,
      String claimSemanticHash,
      boolean verified,
      boolean replayVerified,
      boolean active,
      boolean proofStepUseAllowed) {
    return new TrustedClaimEvidence(
        "trusted-evidence",
        reference,
        problemHash,
        claimSemanticHash,
        ClaimTrustedEvidenceAuthority.REPLAYED_COMPUTATION,
        verified,
        replayVerified,
        active,
        proofStepUseAllowed);
  }

  private static EvidenceRef trustedReference() {
    return new EvidenceRef(
        "artifact://verified-computation",
        TRUSTED_HASH,
        "certificate",
        "Server-resolved verified computation.");
  }

  private static Scenario scenario() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimProofRevisionRecord base =
        ClaimCourtRepairTestFixtures.original(frozen, claim);
    return new Scenario(
        frozen,
        base,
        ClaimCourtRepairTestFixtures.localAudit(claim.claimId(), "linear-step"));
  }

  private record Scenario(
      FrozenClaimSnapshot frozen,
      ClaimProofRevisionRecord base,
      io.github.aililuola.mathproofmesh.contract.ClaimProofAuditDecision audit) {}
}
