package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopRepairerEvidenceAuthorityBoundaryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void repairerCannotPromoteAnInventedEvidenceReferenceIntoBlindReview()
      throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("evidence-authority"), "evidence-authority")) {
      harness.freezeAndCreateRoute();
      harness.installSingleClaimRound(
          0,
          "repair-evidence-injection",
          "REPAIRABLE EVIDENCE_INJECTION: equal finite sets admit a bijection.");
      harness.integrateInstalledRound();

      long blindCalls = harness.callsForSchema("ClaimBlindAdjudicationBatch");
      long facts =
          harness.typedMemory().facts().stream()
              .filter(message -> message.messageId().equals("repair-evidence-injection"))
              .count();
      var claim =
          harness.lemmaMemory().claims().stream()
              .filter(value -> value.claimId().equals("repair-evidence-injection"))
              .findFirst()
              .orElseThrow();

      assertThat(blindCalls).isZero();
      assertThat(facts).isZero();
      assertThat(claim.status()).isEqualTo(ClaimStatus.UNCERTAIN);
      assertThat(harness.claimProofRevisions().recordsForClaim(claim.claimId()))
          .hasSize(1);

      System.out.println("UNTRUSTED_EVIDENCE_PATCH_ATTEMPTS=1");
      System.out.println("UNTRUSTED_EVIDENCE_PATCH_ACCEPTS=0");
      System.out.println("UNTRUSTED_EVIDENCE_IN_BLIND_PACKETS=0");
      System.out.println("DIRECT_EVIDENCE_AUTHORITY_ESCALATIONS=0");
      System.out.println("RESULT=PASS");
    }
  }
}
