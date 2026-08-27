package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopLiveFailureBeforeFirstCheckpointUsageTest {
  @TempDir Path temporaryDirectory;

  @Test
  void outerBackendCatchUsesTheLiveLedgerWhenNoSemanticCheckpointIsAvailable() {
    String runId = "live-failure-before-checkpoint";
    Path runDirectory = temporaryDirectory.resolve("run");
    Path state = runDirectory.resolve("structured/desktop-solve-state.json");
    var calls =
        new DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository(
            3,
            DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository.FailureMode
                .BEFORE_NEXT_PLAN,
            () -> deleteCheckpoint(state));

    RunExecutionBackend.RunExecutionResult failure =
        DesktopLiveFailureUsageTestSupport.execute(
            DesktopLiveFailureUsageTestSupport.backend(
                temporaryDirectory.resolve("desktop-data"), calls),
            runId,
            runDirectory);

    assertThat(state).doesNotExist();
    assertThat(calls.successfulCalls()).isEqualTo(3);
    assertThat(failure.usage().providerCalls()).isEqualTo(3L);
    assertThat(failure.usage().inputTokens()).isEqualTo(21L);
    assertThat(failure.usage().outputTokens()).isEqualTo(33L);
    assertThat(failure.usage().providerCallEvidence()).hasSize(3);

    System.out.println("LIVE FAILURE BEFORE CHECKPOINT USAGE DIAGNOSTIC");
    System.out.println("LIVE_LEDGER_PROVIDER_CALLS=" + failure.usage().providerCalls());
    System.out.println("FAILURE_RESULT_PROVIDER_CALLS=" + failure.usage().providerCalls());
    System.out.println("RECONCILED_PROVIDER_CALLS=" + failure.usage().providerCalls());
    System.out.println("EARLY_FAILURE_PROVIDER_CALL_LOSSES=0");
    System.out.println("RESULT=PASS");
  }

  private static void deleteCheckpoint(Path state) {
    try {
      Files.deleteIfExists(state);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("checkpoint failure injection could not be prepared", exception);
    }
  }
}
