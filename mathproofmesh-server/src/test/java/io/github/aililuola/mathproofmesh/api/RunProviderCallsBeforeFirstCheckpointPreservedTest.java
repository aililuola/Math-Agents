package io.github.aililuola.mathproofmesh.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunProviderCallsBeforeFirstCheckpointPreservedTest {
  @TempDir Path temporaryDirectory;

  @Test
  void resultAggregatePreservesProviderCallsBeforeFirstCheckpoint() {
    RunExecutionBackend.RunExecutionResult result =
        new RunExecutionBackend.RunExecutionResult(
            "failed",
            "triage",
            "early failure",
            List.of(),
            List.of(),
            "",
            1,
            new RunExecutionBackend.ExecutionUsage(
                3L, 120L, 30L, new BigDecimal("0.25"), 90.0d),
            null);

    var state =
        RunStateApiProjection.reconcile(
            new SolveRequest("Prove P.", "early-usage", null, "smoke"),
            "early-usage",
            "attempt-one",
            temporaryDirectory,
            result,
            null);

    assertThat(state.authority().usage().providerCalls()).isEqualTo(3L);
    assertThat(state.authority().usage().totalTokens()).isEqualTo(150L);
  }
}
