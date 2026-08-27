package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUseChange;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUsageAction;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotDeltaStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotProposedClaimDraft;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopPivotProposedClaimMaterializationTest {
  @TempDir java.nio.file.Path directory;

  @Test
  void appliedPivotCannotLeaveAProposedClaimOnlyInItsStructuralHash() throws Exception {
    try (DesktopSemanticPivotTestHarness harness =
        DesktopSemanticPivotTestHarness.open(directory, "pivot-proposed-claim")) {
      String claimId = "pivot-proposed-claim-1";
      String statement =
          "Every inclusion-minimal global support contains a maximal prime witness.";
      String statementHash = PivotProposedClaimDraft.statementHash(statement);
      var delta =
          harness.validDelta(
              301,
              List.of(
                  new PivotClaimUseChange(
                      claimId,
                      statementHash,
                      PivotClaimUsageAction.ADD_AS_PROPOSED_CLAIM,
                      "The global reduction exposes a new candidate lemma.",
                      new PivotProposedClaimDraft(
                          claimId,
                          statement,
                          statementHash,
                          List.of("the global support family is nonempty"),
                          List.of("artifact://semantic-pivot/global-support"),
                          List.of(),
                          List.of("candidate:global-support")))));

      assertThat(harness.apply(delta).status()).isEqualTo(PivotDeltaStatus.APPLIED);
      assertThat(harness.proposedClaimExists(claimId)).isTrue();
      assertThat(harness.proposedClaimDirectlyVerified(claimId)).isFalse();
      assertThat(harness.proposedClaimDirectlyPromoted(claimId)).isFalse();
      System.out.println("VALID_PROPOSED_CLAIMS_CREATED=1");
      System.out.println("PROPOSED_CLAIM_DIRECT_VERIFICATIONS=0");
      System.out.println("PROPOSED_CLAIM_DIRECT_FACT_PROMOTIONS=0");
    }
  }

  @Test
  void failedAtomicApplyRemovesEveryProposedClaimProjection() throws Exception {
    try (DesktopSemanticPivotTestHarness harness =
        DesktopSemanticPivotTestHarness.open(directory.resolve("rollback"), "pivot-claim-rollback")) {
      String claimId = "pivot-proposed-claim-rollback";
      String statement = "Every global support has a reviewable bounded witness.";
      String statementHash = PivotProposedClaimDraft.statementHash(statement);
      var delta =
          harness.validDelta(
              302,
              List.of(
                  new PivotClaimUseChange(
                      claimId,
                      statementHash,
                      PivotClaimUsageAction.ADD_AS_PROPOSED_CLAIM,
                      "Create one candidate Claim inside the Pivot transaction.",
                      new PivotProposedClaimDraft(
                          claimId,
                          statement,
                          statementHash,
                          List.of("the support family is nonempty"),
                          List.of(),
                          List.of(),
                          List.of("candidate:atomic-rollback")))));

      harness.failurePoint(SemanticPivotFailurePoint.AFTER_ROUTE_SWITCH);
      assertThatThrownBy(() -> harness.apply(delta))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("AFTER_ROUTE_SWITCH");

      assertThat(harness.proposedClaimCount(claimId)).isZero();
      assertThat(harness.proposedClaimLifecycleCount(claimId)).isZero();
      assertThat(harness.pendingPivotProposedClaimCount(claimId)).isZero();
      System.out.println("PROPOSED_CLAIM_ROLLBACK_LEAKS=0");
    }
  }
}
