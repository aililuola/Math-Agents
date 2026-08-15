package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class ClaimCourtAtomicProjectionTest {
  @Test
  void runtimeFailureRestoresAllCourtOwnedLedgers() {
    ClaimCourtLedger court = new ClaimCourtLedger();
    ClaimProofRevisionLedger revisions = new ClaimProofRevisionLedger();
    ClaimCourtStageExecutionLedger executions = new ClaimCourtStageExecutionLedger();
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(ClaimCourtTestFixtures.linearClaim());
    String courtBefore = court.stableHash();
    String revisionsBefore = revisions.stableHash();
    String executionsBefore = executions.stableHash();
    ClaimCourtAtomicProjection projection =
        new ClaimCourtAtomicProjection(court, revisions, executions);
    assertThatThrownBy(
            () ->
                projection.run(
                    () -> {
                      court.open(frozen, ClaimCourtTestFixtures.roles());
                      revisions.createOriginal(
                          frozen,
                          ClaimCourtTestFixtures.linearClaim().proofSteps(),
                          java.util.List.of());
                      throw new IllegalStateException("injected");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(court.stableHash()).isEqualTo(courtBefore);
    assertThat(revisions.stableHash()).isEqualTo(revisionsBefore);
    assertThat(executions.stableHash()).isEqualTo(executionsBefore);
  }
}
