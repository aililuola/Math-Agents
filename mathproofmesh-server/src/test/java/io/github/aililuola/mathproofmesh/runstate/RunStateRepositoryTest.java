package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunStateRepositoryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void fileAuthorityRoundTripsWithoutProjectionInference() {
    FileRunStateStore store = new FileRunStateStore(temporaryDirectory);
    RunStateSnapshot state =
        RunStateServerTestSupport.state("repository-run", RunExecutionStatus.FAILED, true);
    store.compareAndSet("repository-run", -1, state, "test", 0);
    assertThat(store.load("repository-run")).contains(state);
  }
}
