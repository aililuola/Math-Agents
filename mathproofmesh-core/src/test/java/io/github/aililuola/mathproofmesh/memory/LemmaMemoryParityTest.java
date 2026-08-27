package io.github.aililuola.mathproofmesh.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.Severity;
import io.github.aililuola.mathproofmesh.contract.VerificationIssue;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class LemmaMemoryParityTest {

  @Test
  void missingDependencyNeverSilentlyBecomesVerified() {
    LemmaMemory memory = new LemmaMemory();
    ClaimCard claim =
        MemoryFixtures.claim(
            "claim-a",
            List.of("claim-missing"),
            ClaimStatus.PROPOSED,
            null,
            null);
    memory.addMany(List.of(claim));

    ClaimCard updated =
        memory.applyClaimReport(
            MemoryFixtures.report(
                "claim-a", "claim", VerificationVerdict.PASS, List.of()));

    assertThat(updated.status()).isEqualTo(ClaimStatus.UNCERTAIN);
    assertThat(memory.verified()).isEmpty();
    assertThat(updated.scopeLimitations())
        .contains("missing dependency: claim-missing");
  }

  @Test
  void verifiedDependencyChainIsReusable() {
    LemmaMemory memory = new LemmaMemory();
    ClaimCard base =
        MemoryFixtures.claim("base", List.of(), ClaimStatus.PROPOSED, null, null);
    ClaimCard derived =
        MemoryFixtures.claim(
            "derived", List.of("base"), ClaimStatus.PROPOSED, null, null);
    memory.addMany(List.of(base, derived));

    memory.applyClaimReport(
        MemoryFixtures.report("base", "claim", VerificationVerdict.PASS, List.of()));
    memory.applyClaimReport(
        MemoryFixtures.report(
            "derived", "claim", VerificationVerdict.PASS, List.of()));

    assertThat(memory.verified())
        .extracting(ClaimCard::claimId)
        .containsExactlyInAnyOrder("base", "derived");
  }

  @Test
  void attemptFailurePreservesIndependentlyVerifiedLocalClaim() {
    LemmaMemory memory = new LemmaMemory();
    ClaimCard claim =
        MemoryFixtures.claimWithStep(
            "verified-local-lemma", "partial-attempt", ClaimStatus.VERIFIED, 0.97);
    memory.addMany(List.of(claim));

    memory.markAttemptVerified(
        "partial-attempt",
        MemoryFixtures.report(
            "partial-attempt", "attempt", VerificationVerdict.FAIL, List.of()));

    assertThat(memory.verified())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.claimId()).isEqualTo("verified-local-lemma");
              assertThat(item.verificationConfidence()).isEqualTo(0.97);
            });
  }

  @Test
  void attemptFailureCannotRejectVerifiedChildAndLeavesUnreviewedProposalOpen() {
    LemmaMemory memory = new LemmaMemory();
    ClaimCard verifiedChild =
        MemoryFixtures.claim(
            "bad-child", List.of(), ClaimStatus.VERIFIED, "attempt", 0.9);
    ClaimCard unresolved =
        MemoryFixtures.claim(
            "unresolved-child",
            List.of(),
            ClaimStatus.PROPOSED,
            "attempt",
            null);
    memory.addMany(List.of(verifiedChild, unresolved));
    VerificationIssue issue =
        new VerificationIssue(
            "bad-child",
            "",
            "counterexample",
            "This exact child claim has a counterexample.",
            null,
            null,
            "claim",
            "",
            null,
            Severity.ERROR,
            null);

    memory.markAttemptVerified(
        "attempt",
        MemoryFixtures.report(
            "attempt", "attempt", VerificationVerdict.FAIL, List.of(issue)));

    assertThat(memory.rejected()).isEmpty();
    assertThat(memory.verified())
        .extracting(ClaimCard::claimId)
        .containsExactly("bad-child");
    assertThat(memory.uncertain())
        .extracting(ClaimCard::claimId)
        .containsExactly("unresolved-child");
  }

  @Test
  void verifiedDependencyCycleIsDowngradedAndAudited() {
    LemmaMemory memory = new LemmaMemory();
    ClaimCard left =
        MemoryFixtures.claim(
            "left", List.of("right"), ClaimStatus.VERIFIED, null, 0.9);
    ClaimCard right =
        MemoryFixtures.claim(
            "right", List.of("left"), ClaimStatus.VERIFIED, null, 0.9);

    memory.addMany(List.of(left, right));

    assertThat(memory.verified()).isEmpty();
    assertThat(memory.uncertain()).hasSize(2);
    assertThat(memory.uncertain())
        .allSatisfy(
            item ->
                assertThat(item.scopeLimitations())
                    .contains("dependency cycle detected"));
  }

  @Test
  void snapshotRestoresAliasesAndCommittedStepAuthority() {
    LemmaMemory memory = new LemmaMemory();
    ClaimCard canonical =
        MemoryFixtures.claim(
            "canonical", List.of(), ClaimStatus.VERIFIED, null, 0.9);
    ClaimCard duplicate =
        new ClaimCard(
            canonical.assumptions(),
            "duplicate",
            canonical.conclusion(),
            canonical.contentHash(),
            canonical.counterexampleRisk(),
            canonical.dependencies(),
            canonical.dependencyRefs(),
            canonical.evidenceRefs(),
            canonical.proofSteps(),
            canonical.scopeLimitations(),
            canonical.selfConfidence(),
            canonical.sourceAgentId(),
            canonical.sourceAttemptId(),
            canonical.sourceDeltaId(),
            canonical.statement(),
            canonical.status(),
            canonical.tags(),
            canonical.verificationConfidence());
    memory.addMany(List.of(canonical, duplicate));
    memory.registerCommittedStepIds(List.of("checkpoint-step"));

    LemmaMemory restored = LemmaMemory.restore(memory.snapshot());

    assertThat(restored.resolveClaimId("duplicate")).isEqualTo("canonical");
    assertThat(restored.verified())
        .extracting(ClaimCard::claimId)
        .containsExactly("canonical");
  }
}
