package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class RunVerifiedClaimsCannotDisappearTest {
  @Test
  void sparseEvidenceCannotReduceVerifiedClaimCount() {
    RunMathematicalProgressSnapshot fiveVerified =
        new RunMathematicalProgressSnapshot(
            5, 0, 1, true, true, true, true, false, false, false, false, false, true);
    RunStateSnapshot previous =
        RunStateTestSupport.stateWithProofGraph(fiveVerified, null, "c".repeat(64));
    RunStateSnapshot next =
        RunStateTestSupport.stateWithProofGraph(
            RunMathematicalProgressSnapshot.empty(), previous, "c".repeat(64));

    assertThat(next.authority().mathematicalProgress().verifiedLocalClaims()).isEqualTo(5);
  }
}
