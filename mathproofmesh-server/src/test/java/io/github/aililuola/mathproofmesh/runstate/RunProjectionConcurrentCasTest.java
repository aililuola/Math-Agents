package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunProjectionConcurrentCasTest {
  @TempDir Path temporaryDirectory;

  @Test
  void onlyOneProjectionWriterCanCommitFromTheSameStateFrontier() {
    FileRunStateStore store = new FileRunStateStore(temporaryDirectory);
    RunStateSnapshot base =
        RunStateServerTestSupport.state("projection-cas", RunExecutionStatus.RUNNING, true);
    store.compareAndSet("projection-cas", -1L, base, "worker", 0L);
    RunStateSnapshot first = projection(base, "results/first.json");
    RunStateSnapshot stale = projection(base, "results/stale.json");

    store.compareAndSetProjection("projection-cas", base.stateHash(), 0L, first, "worker", 0L);
    assertThatThrownBy(
            () ->
                store.compareAndSetProjection(
                    "projection-cas", base.stateHash(), 0L, stale, "worker", 0L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stale projection");

    assertThat(store.load("projection-cas").orElseThrow().projection().runResultRef())
        .isEqualTo("results/first.json");
  }

  static RunStateSnapshot projection(RunStateSnapshot source, String reference) {
    RunProjectionSnapshot projection =
        new RunProjectionSnapshot(
            source.authority().authorityHash(),
            RunReportStatus.PARTIAL,
            reference,
            io.github.aililuola.mathproofmesh.contract.CanonicalJson.stableHash(reference),
            "",
            "",
            "",
            "",
            0L,
            List.of(),
            source.projection().projectionVersion() + 1L,
            null);
    return RunStateSnapshot.create(
        source.authority(),
        projection,
        source.reconciliationStatus(),
        source.conflicts(),
        Instant.parse("2026-08-17T00:00:02Z"));
  }
}
