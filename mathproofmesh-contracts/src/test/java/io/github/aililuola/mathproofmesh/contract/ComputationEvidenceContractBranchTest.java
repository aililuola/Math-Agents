package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComputationEvidenceContractBranchTest {
  private static final String HASH = "a".repeat(64);
  private static final String OTHER_HASH = "b".repeat(64);

  @Test
  void certificateDefaultsAndRejectsMalformedCoverageOrIdentity() {
    var certificate =
        new ComputationCertificateEnvelope(
            HASH,
            OTHER_HASH,
            " capability ",
            " 1 ",
            HASH,
            OTHER_HASH,
            HASH,
            ComputationCertificateType.FINITE_EXHAUSTIVE_COVERAGE,
            null,
            null,
            null,
            HASH,
            " producer ",
            " 1 ",
            null);

    assertEquals("capability", certificate.capabilityId());
    assertEquals(0, certificate.casesExpected());
    assertEquals(0, certificate.casesChecked());
    assertTrue(certificate.witness().isEmpty());
    assertEquals(64, certificate.certificateHash().length());
    assertNotSame(certificate.witness(), certificate.witness());

    assertContractFailure(
        () ->
            new ComputationCertificateEnvelope(
                HASH,
                OTHER_HASH,
                "capability",
                "1",
                HASH,
                OTHER_HASH,
                HASH,
                ComputationCertificateType.FINITE_EXHAUSTIVE_COVERAGE,
                1,
                2,
                JsonNodeFactory.instance.objectNode(),
                HASH,
                "producer",
                "1",
                null),
        "cases_checked");
    assertContractFailure(() -> certificateWith(" ", 0, 0, null), null);
    assertContractFailure(() -> certificateWith("capability", -1, 0, null), null);
    assertContractFailure(() -> certificateWith("capability", 0, -1, null), null);
    assertContractFailure(
        () -> certificateWith("capability", 0, 0, "0".repeat(64)), null);
  }

  @Test
  void decisionPlanRequiresExactTargetsAndImmutableBranches() {
    var branch =
        new ComputationDecisionBranch(
            ExperimentOutcome.CERTIFIED,
            ComputationDecisionAction.SATISFY_FINITE_DOMAIN_OBLIGATION,
            HASH);
    var obligationPlan =
        new ComputationDecisionPlan(
            " plan ", null, null, " obligation ", null, List.of(branch), null);
    var claimPlan =
        new ComputationDecisionPlan(
            "claim-plan", "claim", HASH, null, "canonical", List.of(branch), null);

    assertEquals("plan", obligationPlan.planId());
    assertEquals("obligation", obligationPlan.targetObligationId());
    assertEquals(HASH, claimPlan.targetClaimSemanticHash());
    assertEquals(List.of(branch), claimPlan.branches());
    assertEquals(64, claimPlan.planHash().length());

    assertContractFailure(
        () -> new ComputationDecisionPlan("plan", null, null, "obligation", null, null, null),
        "branch");
    assertContractFailure(
        () -> new ComputationDecisionPlan("plan", null, null, null, null, List.of(branch), null),
        "target");
    assertContractFailure(
        () -> new ComputationDecisionPlan("plan", "claim", null, null, null, List.of(branch), null),
        "semantic hash");
    assertContractFailure(
        () ->
            new ComputationDecisionPlan(
                "plan", null, null, "obligation", null, List.of(branch), HASH),
        null);
    assertContractFailure(
        () ->
            new ComputationDecisionBranch(
                null, ComputationDecisionAction.RETAIN_AUDIT_ONLY, HASH),
        null);
    assertContractFailure(
        () -> new ComputationDecisionBranch(ExperimentOutcome.ERROR, null, HASH), null);
    assertContractFailure(
        () ->
            new ComputationDecisionBranch(
                ExperimentOutcome.ERROR, ComputationDecisionAction.RETAIN_AUDIT_ONLY, null),
        null);
  }

  @Test
  void verificationAndApplicationReceiptsEnforceAuthorityAndHashes() {
    var valid =
        new ComputationVerificationReceipt(
            " receipt ",
            HASH,
            " verifier ",
            " 1 ",
            ComputationVerificationStatus.VALID,
            true,
            ComputationVerifiedAuthority.EXACT_COUNTEREXAMPLE,
            OTHER_HASH,
            null,
            "1970-01-01T00:00:00Z",
            null);
    var invalid =
        new ComputationVerificationReceipt(
            "invalid",
            HASH,
            "verifier",
            "1",
            ComputationVerificationStatus.INVALID,
            null,
            ComputationVerifiedAuthority.AUDIT_ONLY,
            OTHER_HASH,
            List.of("invalid"),
            null,
            null);
    var application =
        new ComputationOutcomeApplicationReceipt(
            " app ",
            " execution ",
            HASH,
            OTHER_HASH,
            ComputationDecisionAction.RETAIN_AUDIT_ONLY,
            null,
            null,
            null);

    assertTrue(valid.valid());
    assertTrue(valid.diagnostics().isEmpty());
    assertFalse(invalid.valid());
    assertFalse(invalid.createdAt().isBlank());
    assertEquals("app", application.applicationId());
    assertFalse(application.applied());
    assertTrue(application.diagnostic().isEmpty());

    assertContractFailure(
        () ->
            receipt(
                ComputationVerificationStatus.INVALID,
                true,
                ComputationVerifiedAuthority.AUDIT_ONLY,
                null),
        "status");
    assertContractFailure(
        () ->
            receipt(
                ComputationVerificationStatus.INVALID,
                false,
                ComputationVerifiedAuthority.FORMAL_CERTIFICATE,
                null),
        "authority");
    assertContractFailure(
        () ->
            receipt(
                ComputationVerificationStatus.VALID,
                true,
                ComputationVerifiedAuthority.AUDIT_ONLY,
                HASH),
        null);
    assertContractFailure(
        () ->
            new ComputationOutcomeApplicationReceipt(
                " ",
                "execution",
                HASH,
                OTHER_HASH,
                ComputationDecisionAction.RETAIN_AUDIT_ONLY,
                false,
                "",
                null),
        null);
    assertContractFailure(
        () ->
            new ComputationOutcomeApplicationReceipt(
                "app", "execution", HASH, OTHER_HASH, null, false, "", null),
        null);
    assertContractFailure(
        () ->
            new ComputationOutcomeApplicationReceipt(
                "app",
                "execution",
                HASH,
                OTHER_HASH,
                ComputationDecisionAction.RETAIN_AUDIT_ONLY,
                true,
                "ok",
                HASH),
        null);
  }

  @Test
  void toolRequestDefaultsAndRejectsUnknownOrUnboundedCapabilities() {
    var request =
        new ToolRequest(
            null, null, ComputationMethod.EXACT_LINEAR_ALGEBRA.value(), null, " check ", null);
    assertTrue(request.arguments().isEmpty());
    assertTrue(request.domains().isEmpty());
    assertEquals(100_000, request.maxCases());
    assertTrue(request.requestId().startsWith("toolreq"));
    assertNotSame(request.arguments(), request.arguments());

    assertContractFailure(() -> new ToolRequest(null, null, "unknown", 1, "check", "id"), null);
    assertContractFailure(
        () ->
            new ToolRequest(
                null, null, ComputationMethod.EXACT_LINEAR_ALGEBRA.value(), 0, "check", "id"),
        null);
    assertContractFailure(
        () ->
            new ToolRequest(
                null,
                null,
                ComputationMethod.EXACT_LINEAR_ALGEBRA.value(),
                100_000_001,
                "check",
                "id"),
        null);
    assertContractFailure(
        () ->
            new ToolRequest(
                null,
                null,
                ComputationMethod.EXACT_LINEAR_ALGEBRA.value(),
                1,
                null,
                "id"),
        null);
  }

  private static ComputationCertificateEnvelope certificateWith(
      String capabilityId, Integer expected, Integer checked, String certificateHash) {
    return new ComputationCertificateEnvelope(
        HASH,
        OTHER_HASH,
        capabilityId,
        "1",
        HASH,
        OTHER_HASH,
        HASH,
        ComputationCertificateType.BOUNDED_OBSERVATION,
        expected,
        checked,
        JsonNodeFactory.instance.objectNode(),
        HASH,
        "producer",
        "1",
        certificateHash);
  }

  private static ComputationVerificationReceipt receipt(
      ComputationVerificationStatus status,
      boolean valid,
      ComputationVerifiedAuthority authority,
      String receiptHash) {
    return new ComputationVerificationReceipt(
        "receipt",
        HASH,
        "verifier",
        "1",
        status,
        valid,
        authority,
        OTHER_HASH,
        List.of(),
        "1970-01-01T00:00:00Z",
        receiptHash);
  }

  private static void assertContractFailure(Runnable action, String messageFragment) {
    var failure = assertThrows(ContractValidationException.class, action::run);
    if (messageFragment != null) {
      assertTrue(failure.getMessage().contains(messageFragment));
    }
  }
}
