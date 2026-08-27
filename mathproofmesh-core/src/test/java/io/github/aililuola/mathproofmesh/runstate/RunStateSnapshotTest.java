package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class RunStateSnapshotTest {
  @Test
  void validatesStableHashesAndDefensiveCollections() {
    RunStateSnapshot state =
        RunStateTestSupport.state(
            RunExecutionStatus.FAILED,
            RunStateTestSupport.partial(),
            RunStateTestSupport.usage(1, 2, 3),
            null,
            RunReportStatus.PARTIAL);
    assertThat(state.stateHash()).hasSize(64);
    assertThat(state.authority().authorityHash()).hasSize(64);
    assertThatThrownBy(
            () ->
                new RunStateSnapshot(
                    state.schemaVersion(),
                    state.authority(),
                    state.projection(),
                    state.reconciliationStatus(),
                    state.conflicts(),
                    "0".repeat(64),
                    state.updatedAt()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
