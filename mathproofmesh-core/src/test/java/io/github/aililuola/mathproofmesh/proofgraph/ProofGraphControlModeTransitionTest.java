package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProofGraphControlModeTransitionTest {
  @Test
  void entersFocusedRecoveryThenCooldownAndCanReenter() {
    ProofGraphStore graph = ProofGraphConvergenceTestFixtures.graphWithTarget();
    ProofGraphConvergenceMonitor monitor = new ProofGraphConvergenceMonitor();

    monitor.observe(stagnant(0), graph, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    monitor.observe(stagnant(1), graph, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    assertThat(monitor.controlMode()).isEqualTo(ProofGraphControlMode.FOCUSED_RECOVERY);

    monitor.observe(
        ProofGraphConvergenceTestFixtures.metrics(
            2, 1, 0, 0, 0, 0, 0, 1, 0, 0, 4.0d),
        graph,
        ProofGraphConvergenceTestFixtures.ROOT_HASH);
    assertThat(monitor.controlMode()).isEqualTo(ProofGraphControlMode.RECOVERY_COOLDOWN);

    monitor.observe(stagnant(3), graph, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    assertThat(monitor.controlMode()).isEqualTo(ProofGraphControlMode.RECOVERY_COOLDOWN);
    monitor.observe(stagnant(4), graph, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    assertThat(monitor.controlMode()).isEqualTo(ProofGraphControlMode.NORMAL_EXPANSION);

    monitor.observe(stagnant(5), graph, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    monitor.observe(stagnant(6), graph, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    assertThat(monitor.controlMode()).isEqualTo(ProofGraphControlMode.FOCUSED_RECOVERY);
    assertThat(monitor.focusedRecoveryEntries()).isEqualTo(2);
    assertThat(monitor.focusedRecoveryExits()).isEqualTo(1);
  }

  private static ProofGraphRoundMetrics stagnant(int round) {
    return ProofGraphConvergenceTestFixtures.metrics(
        round, 1, 0, 0, 0, 1, 0, 0, 0, 0, 4.0d);
  }
}
