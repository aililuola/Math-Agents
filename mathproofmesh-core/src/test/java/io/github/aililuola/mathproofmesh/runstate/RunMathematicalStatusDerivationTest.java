package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class RunMathematicalStatusDerivationTest {
  @Test
  void derivesStatusOnlyFromMathematicalEvidence() {
    assertThat(RunStateReconciler.deriveMath(RunMathematicalProgressSnapshot.empty()))
        .isEqualTo(RunMathematicalStatus.NOT_STARTED);
    assertThat(RunStateReconciler.deriveMath(RunStateTestSupport.partial()))
        .isEqualTo(RunMathematicalStatus.PARTIAL_UNVERIFIED);
    assertThat(RunStateReconciler.deriveMath(RunStateTestSupport.verified()))
        .isEqualTo(RunMathematicalStatus.VERIFIED);
  }
}
