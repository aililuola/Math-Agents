package io.github.aililuola.mathproofmesh.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.Severity;
import io.github.aililuola.mathproofmesh.contract.VerificationIssue;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class Phase17LemmaMemoryHardeningTest {

  @Test
  void duplicateEvidenceUpgradeAndAllReportVerdictsAreDeterministic() {
    LemmaMemory memory = new LemmaMemory();
    ClaimCard proposed =
        MemoryFixtures.claim("canonical", List.of(), ClaimStatus.PROPOSED, null, null);
    assertThat(memory.addMany(List.of(proposed))).containsExactly(proposed);
    assertThat(memory.addMany(List.of(proposed))).isEmpty();
    ClaimCard verifiedDuplicate =
        copyWithIdentity(proposed, "duplicate", ClaimStatus.VERIFIED, 0.8d, List.of("bounded"));
    assertThat(memory.addMany(List.of(verifiedDuplicate))).isEmpty();
    assertThat(memory.resolveClaimId("duplicate")).isEqualTo("canonical");
    assertThat(memory.claims())
        .singleElement()
        .satisfies(
            claim -> {
              assertThat(claim.status()).isEqualTo(ClaimStatus.VERIFIED);
              assertThat(claim.verificationConfidence()).isEqualTo(0.8d);
              assertThat(claim.scopeLimitations()).contains("bounded");
            });

    ClaimCard uncertain =
        MemoryFixtures.claim("uncertain", List.of(), ClaimStatus.UNCERTAIN, null, 0.4d);
    memory.addMany(List.of(uncertain));
    memory.addMany(
        List.of(copyWithIdentity(uncertain, "uncertain-upgrade", ClaimStatus.VERIFIED, 0.9d, List.of())));
    assertThat(memory.resolveClaimId("uncertain-upgrade")).isEqualTo("uncertain");
    assertThat(memory.applyClaimReport(
            MemoryFixtures.report("missing", "claim", VerificationVerdict.PASS, List.of())))
        .isNull();
    assertThat(memory.applyClaimReport(
            MemoryFixtures.report("canonical", "claim", VerificationVerdict.FAIL, List.of())).status())
        .isEqualTo(ClaimStatus.REJECTED);
    assertThat(memory.applyClaimReport(
            MemoryFixtures.report("canonical", "claim", VerificationVerdict.UNCERTAIN, List.of())).status())
        .isEqualTo(ClaimStatus.UNCERTAIN);
    assertThat(memory.applyClaimReport(
            MemoryFixtures.report("canonical", "claim", VerificationVerdict.SKIPPED, List.of())).status())
        .isEqualTo(ClaimStatus.UNCERTAIN);
    assertThat(memory.applyClaimReport(
            MemoryFixtures.report("canonical", "claim", VerificationVerdict.PASS, List.of())).status())
        .isEqualTo(ClaimStatus.VERIFIED);
    assertThat(memory.uncertain()).isEmpty();
    assertThat(memory.rejected()).isEmpty();
    assertThat(memory.audit()).isNotEmpty();
  }

  @Test
  void attemptReconciliationCoversCriticalNullWarningPassAndUnrelatedClaims() {
    LemmaMemory memory = new LemmaMemory();
    ClaimCard proposed =
        MemoryFixtures.claim("proposed", List.of(), ClaimStatus.PROPOSED, "attempt", null);
    ClaimCard already =
        MemoryFixtures.claim("already", List.of(), ClaimStatus.VERIFIED, "attempt", 0.99d);
    ClaimCard unrelated =
        MemoryFixtures.claim("unrelated", List.of(), ClaimStatus.PROPOSED, "other", null);
    memory.addMany(List.of(proposed, already, unrelated));

    VerificationIssue critical =
        new VerificationIssue(
            "proposed", "", "counterexample", "critical", null, null,
            "claim", "", null, Severity.CRITICAL, null);
    VerificationIssue warning =
        new VerificationIssue(
            "already", "", "warning", "warning", null, null,
            "claim", "", null, Severity.WARNING, null);
    VerificationIssue noClaim =
        new VerificationIssue(
            null, "", "warning", "warning", null, null,
            "claim", "", null, Severity.ERROR, null);
    assertThat(
            memory.markAttemptVerified(
                "attempt",
                MemoryFixtures.report(
                    "attempt", "attempt", VerificationVerdict.FAIL,
                    List.of(critical, warning, noClaim))))
        .extracting(ClaimCard::claimId)
        .containsExactlyInAnyOrder("proposed", "already");
    assertThat(memory.rejected()).extracting(ClaimCard::claimId).contains("proposed");
    assertThat(memory.verified()).extracting(ClaimCard::claimId).contains("already");

    ClaimCard passCandidate =
        MemoryFixtures.claim("pass", List.of(), ClaimStatus.PROPOSED, "pass-attempt", null);
    memory.addMany(List.of(passCandidate));
    assertThat(
            memory.markAttemptVerified(
                "pass-attempt",
                MemoryFixtures.report(
                    "pass-attempt", "attempt", VerificationVerdict.PASS, List.of())))
        .singleElement()
        .satisfies(claim -> assertThat(claim.status()).isEqualTo(ClaimStatus.PROPOSED));
    memory.applyClaimReviewDecision(
        "pass",
        new ClaimReviewDecision(
            "pass",
            VerificationVerdict.PASS,
            0.95d,
            List.of(),
            true,
            true,
            true,
            true,
            false,
            List.of(),
            "claim-scoped review passed"));
    assertThat(memory.verified()).extracting(ClaimCard::claimId).contains("pass");
    assertThat(memory.markAttemptVerified(
            "absent",
            MemoryFixtures.report("absent", "attempt", VerificationVerdict.PASS, List.of())))
        .isEmpty();
  }

  @Test
  void claimAndStepDependencyNamespacesCommittedReplacementAndRestoreAreClosed() {
    LemmaMemory memory = new LemmaMemory();
    ClaimCard base = MemoryFixtures.claim("base", List.of(), ClaimStatus.VERIFIED, null, 0.9d);
    ClaimCard localStep =
        MemoryFixtures.claimWithStep("local-step", null, ClaimStatus.VERIFIED, 0.9d);
    ClaimCard namespaced =
        withDependencies(
            MemoryFixtures.claim("namespaced", List.of(), ClaimStatus.VERIFIED, null, 0.9d),
            List.of("claim:base", "step:checkpoint", "external-claim"),
            List.of(
                ref("local_claim", "base"),
                ref("local_claim", ""),
                ref("local_step", "checkpoint"),
                ref("local_step", "")));
    ClaimCard external =
        MemoryFixtures.claim("external-claim", List.of(), ClaimStatus.REJECTED, null, 0.2d);
    memory.addMany(List.of(base, localStep, external, namespaced));
    assertThat(memory.verified()).extracting(ClaimCard::claimId)
        .contains("base", "local-step")
        .doesNotContain("namespaced");
    assertThat(memory.claims().stream()
            .filter(claim -> claim.claimId().equals("namespaced"))
            .findFirst()
            .orElseThrow()
            .scopeLimitations())
        .contains("missing local proof step: checkpoint", "dependency is not verified: external-claim");

    memory.registerCommittedStepIds(List.of("checkpoint"));
    memory.registerCommittedStepIds(List.of("checkpoint"));
    memory.applyClaimReport(
        MemoryFixtures.report("external-claim", "claim", VerificationVerdict.PASS, List.of()));
    memory.applyClaimReport(
        MemoryFixtures.report("namespaced", "claim", VerificationVerdict.PASS, List.of()));
    assertThat(memory.verified()).extracting(ClaimCard::claimId).contains("namespaced");
    memory.replaceCommittedStepIds(List.of());
    assertThat(memory.verified()).extracting(ClaimCard::claimId).doesNotContain("namespaced");
    memory.replaceCommittedStepIds(List.of("checkpoint"));
    memory.applyClaimReport(
        MemoryFixtures.report("namespaced", "claim", VerificationVerdict.PASS, List.of()));

    LemmaMemory restored = LemmaMemory.restore(memory.snapshot());
    assertThat(restored.claims()).hasSameSizeAs(memory.claims());
    assertThat(restored.audit()).hasSameSizeAs(memory.audit());
    assertThat(restored.verified()).extracting(ClaimCard::claimId).contains("namespaced");
  }

  private static ObjectNode ref(String kind, String target) {
    return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
        .objectNode()
        .put("kind", kind)
        .put("target_id", target);
  }

  private static ClaimCard copyWithIdentity(
      ClaimCard source,
      String id,
      ClaimStatus status,
      Double confidence,
      List<String> limitations) {
    return new ClaimCard(
        source.assumptions(),
        id,
        source.conclusion(),
        source.contentHash(),
        source.counterexampleRisk(),
        source.dependencies(),
        source.dependencyRefs(),
        source.evidenceRefs(),
        source.proofSteps(),
        limitations,
        source.selfConfidence(),
        source.sourceAgentId(),
        source.sourceAttemptId(),
        source.sourceDeltaId(),
        source.statement(),
        status,
        source.tags(),
        confidence);
  }

  private static ClaimCard withDependencies(
      ClaimCard source,
      List<String> dependencies,
      List<ObjectNode> refs) {
    return new ClaimCard(
        source.assumptions(),
        source.claimId(),
        source.conclusion(),
        "",
        source.counterexampleRisk(),
        dependencies,
        new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>(refs),
        source.evidenceRefs(),
        source.proofSteps(),
        source.scopeLimitations(),
        source.selfConfidence(),
        source.sourceAgentId(),
        source.sourceAttemptId(),
        source.sourceDeltaId(),
        source.statement(),
        source.status(),
        source.tags(),
        source.verificationConfidence());
  }
}
