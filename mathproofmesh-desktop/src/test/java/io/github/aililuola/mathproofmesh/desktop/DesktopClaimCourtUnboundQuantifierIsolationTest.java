package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.aililuola.mathproofmesh.contract.ClaimSemanticContextBinding;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimCourtUnboundQuantifierIsolationTest {
  private static final String CLAIM_ID = "attempt-local-unbound-quantifier";
  private static final String STATEMENT =
      "For every finite tournament T, an extremal ordering exists.";

  @Test
  void attemptLocalUnboundQuantifierIsQuarantinedBeforeCourt(@TempDir Path directory)
      throws Exception {
    int quarantines;
    long courtCalls;
    long factLeaks;
    int rootHashChanges;
    int permanentNegativeHashChanges;

    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(directory, "claim-unbound-quantifier-isolation")) {
      harness.freezeAndCreateRoute();
      String rootHash = harness.rootGoal().sourceStatementHash();
      String permanentNegativeHash = harness.permanentNegativeHash();
      ClaimSemanticContextBinding invalidBinding =
          new ClaimSemanticContextBinding(
              CLAIM_ID,
              "@claim",
              List.of("T is a finite tournament."),
              List.of(
                  new QuantifierSpec(
                      "T", "finite tournaments", "forall", 0, List.of(), "tournament-T")),
              List.of(),
              List.of("finite tournament scope"),
              "positive");
      harness.installModernLocalClaimRound(0, CLAIM_ID, STATEMENT, invalidBinding);

      assertThatCode(harness::integrateInstalledRound).doesNotThrowAnyException();

      quarantines =
          (int)
              harness.attemptArtifacts().records().stream()
                  .filter(record -> record.claimId().equals(CLAIM_ID))
                  .filter(record -> record.status() == AttemptArtifactStatus.UNCERTAIN)
                  .filter(
                      record ->
                          record.history().stream()
                              .anyMatch(
                                  event ->
                                      event.contains("UNBOUND_CLAIM_COURT_QUANTIFIER")))
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

      assertThat(quarantines).isEqualTo(1);
      assertThat(courtCalls).isZero();
      assertThat(factLeaks).isZero();
      assertThat(harness.claimCourt().records()).isEmpty();
      assertThat(harness.lemmaMemory().claims())
          .noneMatch(claim -> claim.claimId().equals(CLAIM_ID));
      assertThat(rootHashChanges).isZero();
      assertThat(permanentNegativeHashChanges).isZero();
    }

    System.out.println("CLAIM COURT VARIABLE IDENTITY ISOLATION DIAGNOSTIC");
    print("UNBOUND_QUANTIFIER_QUARANTINES", quarantines);
    print("UNBOUND_QUANTIFIER_COURT_CALLS", courtCalls);
    print("UNBOUND_QUANTIFIER_FACT_LEAKS", factLeaks);
    print("ROOT_HASH_CHANGES", rootHashChanges);
    print("PERMANENT_NEGATIVE_HASH_CHANGES", permanentNegativeHashChanges);
    System.out.println("RESULT=PASS");
  }

  private static void print(String name, long value) {
    System.out.println(name + "=" + value);
  }
}
