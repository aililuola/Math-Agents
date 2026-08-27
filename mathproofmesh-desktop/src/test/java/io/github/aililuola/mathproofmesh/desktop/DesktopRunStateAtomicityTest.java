package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.runstate.FileRunStateStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopRunStateAtomicityTest {
  @TempDir Path temporaryDirectory;

  @Test
  void rejectedCasLeavesAuthorityAndTransitionLedgerUnchanged() {
    FileRunStateStore store = new FileRunStateStore(temporaryDirectory);
    var state = DesktopRunStateTestSupport.failure("atomic-state", null, 7);
    store.compareAndSet("atomic-state", -1, state, "owner", 0);
    assertThatThrownBy(() -> store.compareAndSet("atomic-state", -1, state, "stale", 0))
        .isInstanceOf(IllegalStateException.class);
    assertThat(store.load("atomic-state")).contains(state);
    assertThat(store.transitions("atomic-state")).hasSize(1);
  }
}
