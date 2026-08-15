package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class ClaimCourtRoleIndependenceTest {
  @Test
  void authorAuditorRepairerFalsifierAndBlindRefereeMustAllDiffer() {
    ClaimCourtRolePolicy policy = new ClaimCourtRolePolicy();
    assertThat(policy.independent(ClaimCourtTestFixtures.roles())).isTrue();
    assertThat(
            policy.independent(
                new ClaimCourtRolePolicy.Assignment(
                    "author", "falsifier", "auditor", "auditor", "blind")))
        .isFalse();
  }
}
