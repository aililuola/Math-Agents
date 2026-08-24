package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimCourtStatementIdentityIsolationTest {
  private static final String CLAIM_ID = "critical-claim";
  private static final String AUTHORITATIVE_STATEMENT =
      "VALID_BOUND: every integer divisible by six is divisible by three.";
  private static final String ALTERED_STATEMENT =
      "VALID_BOUND: every integer divisible by three is divisible by six.";

  @Test
  void alteredStatementUnderExistingClaimIdIsQuarantinedBeforeCourt(@TempDir Path directory)
      throws Exception {
    int mismatchQuarantines;
    long courtCalls;
    long factLeaks;
    int rootHashChanges;
    int permanentNegativeHashChanges;

    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(directory, "claim-statement-identity-isolation")) {
      harness.freezeAndCreateRouteForClaim(
          "bound-critical-strategy", CLAIM_ID, AUTHORITATIVE_STATEMENT);
      String rootHash = harness.rootGoal().sourceStatementHash();
      String permanentNegativeHash = harness.permanentNegativeHash();
      harness.installSingleClaimRound(0, CLAIM_ID, ALTERED_STATEMENT);

      assertThatCode(harness::integrateInstalledRound).doesNotThrowAnyException();

      mismatchQuarantines =
          (int)
              harness.attemptArtifacts().records().stream()
                  .filter(record -> record.claimId().equals(CLAIM_ID))
                  .filter(record -> record.status() == AttemptArtifactStatus.UNCERTAIN)
                  .filter(
                      record ->
                          record.history().stream()
                              .anyMatch(
                                  event ->
                                      event.contains(
                                          "CLAIM_COURT_CONTEXT_STATEMENT_MISMATCH")))
                  .count();
      courtCalls =
          harness.callsForSchema("ClaimStatementFalsificationBatch")
              + harness.callsForSchema("ClaimProofAuditBatch")
              + harness.callsForSchema("ClaimBlindAdjudicationBatch");
      factLeaks =
          harness.typedMemory().facts().stream()
              .filter(fact -> fact.messageId().equals(CLAIM_ID))
              .count();
      rootHashChanges = harness.rootGoal().sourceStatementHash().equals(rootHash) ? 0 : 1;
      permanentNegativeHashChanges =
          harness.permanentNegativeHash().equals(permanentNegativeHash) ? 0 : 1;

      assertThat(mismatchQuarantines).isEqualTo(1);
      assertThat(courtCalls).isZero();
      assertThat(factLeaks).isZero();
      assertThat(harness.claimCourt().records()).isEmpty();
      assertThat(harness.lemmaMemory().claims())
          .noneMatch(claim -> claim.claimId().equals(CLAIM_ID));
      assertThat(rootHashChanges).isZero();
      assertThat(permanentNegativeHashChanges).isZero();
    }

    System.out.println("CLAIM COURT STATEMENT IDENTITY ISOLATION DIAGNOSTIC");
    print("MISMATCH_QUARANTINES", mismatchQuarantines);
    print("MISMATCH_COURT_CALLS", courtCalls);
    print("MISMATCH_FACT_LEAKS", factLeaks);
    print("ROOT_HASH_CHANGES", rootHashChanges);
    print("PERMANENT_NEGATIVE_HASH_CHANGES", permanentNegativeHashChanges);
    System.out.println("RESULT=PASS");
  }

  @Test
  void exactStatementUnderExistingClaimIdStillTraversesCourt(@TempDir Path directory)
      throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(directory, "claim-statement-identity-exact")) {
      harness.freezeAndCreateRouteForClaim(
          "bound-critical-strategy", CLAIM_ID, AUTHORITATIVE_STATEMENT);

      harness.runSingleLegacyClaimRound(0, CLAIM_ID, AUTHORITATIVE_STATEMENT);

      assertThat(harness.claimCourt().records()).hasSize(1);
      assertThat(harness.callsForSchema("ClaimStatementFalsificationBatch")).isEqualTo(1);
      assertThat(harness.callsForSchema("ClaimProofAuditBatch")).isEqualTo(1);
      assertThat(harness.callsForSchema("ClaimBlindAdjudicationBatch")).isEqualTo(1);
      assertThat(harness.typedMemory().facts())
          .anyMatch(fact -> fact.messageId().equals(CLAIM_ID));
    }
  }

  private static void print(String name, long value) {
    System.out.println(name + "=" + value);
  }
}
