package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class RunRefutedClaimsCannotDisappearTest {
  @Test
  void sparseEvidenceCannotReduceRefutedClaimCount() {
    RunMathematicalProgressSnapshot threeRefuted =
        new RunMathematicalProgressSnapshot(
            0, 3, 1, true, true, true, true, false, false, false, false, false, true);
    RunStateSnapshot previous =
        RunStateTestSupport.stateWithProofGraph(threeRefuted, null, "c".repeat(64));
    RunStateSnapshot next =
        RunStateTestSupport.stateWithProofGraph(
            RunMathematicalProgressSnapshot.empty(), previous, "c".repeat(64));

    assertThat(next.authority().mathematicalProgress().refutedClaims()).isEqualTo(3);
  }
}
