package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopLiveFailureAfterCheckpointUsageDeltaTest {
  @TempDir Path temporaryDirectory;

  @Test
  void outerBackendCatchPreservesCallsAfterTheLatestSemanticCheckpoint() throws Exception {
    String runId = "live-failure-after-checkpoint";
    Path runDirectory = temporaryDirectory.resolve("run");
    Path state = runDirectory.resolve("structured/desktop-solve-state.json");
    var bootstrapCalls =
        new DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository(
            0,
            DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository.FailureMode
                .BEFORE_NEXT_PLAN,
            () -> {});
    RunExecutionBackend.RunExecutionResult bootstrap =
        DesktopLiveFailureUsageTestSupport.execute(
            DesktopLiveFailureUsageTestSupport.backend(
                temporaryDirectory.resolve("bootstrap-data"), bootstrapCalls),
            runId,
            runDirectory);
    assertThat(bootstrap.status()).isEqualTo("failed");
    assertThat(state).isRegularFile();
    writeCheckpointUsage(state, 20L, 200L, 100L);

    var liveCalls =
        new DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository(
            3,
            DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository.FailureMode
                .BEFORE_NEXT_PLAN,
            () -> writeCheckpointUsageUnchecked(state, 20L, 200L, 100L));
    RunExecutionBackend.RunExecutionResult failure =
        DesktopLiveFailureUsageTestSupport.execute(
            DesktopLiveFailureUsageTestSupport.backend(
                temporaryDirectory.resolve("resume-data"), liveCalls),
            runId,
            runDirectory);

    long checkpointCalls =
        ContractObjectMapper.parseTree(Files.readString(state))
            .path("usageTotals")
            .path("calls")
            .asLong();
    assertThat(checkpointCalls).isEqualTo(20L);
    assertThat(liveCalls.successfulCalls()).isEqualTo(3);
    assertThat(failure.usage().providerCalls()).isEqualTo(23L);
    assertThat(failure.usage().inputTokens()).isEqualTo(221L);
    assertThat(failure.usage().outputTokens()).isEqualTo(133L);

    System.out.println("LIVE FAILURE AFTER CHECKPOINT USAGE DIAGNOSTIC");
    System.out.println("CHECKPOINT_PROVIDER_CALLS=" + checkpointCalls);
    System.out.println("LIVE_LEDGER_PROVIDER_CALLS=" + failure.usage().providerCalls());
    System.out.println("FAILURE_RESULT_PROVIDER_CALLS=" + failure.usage().providerCalls());
    System.out.println("RECONCILED_PROVIDER_CALLS=" + failure.usage().providerCalls());
    System.out.println("POST_CHECKPOINT_PROVIDER_CALL_LOSSES=0");
    System.out.println("POST_CHECKPOINT_TOKEN_LOSSES=0");
    System.out.println("RESULT=PASS");
  }

  private static void writeCheckpointUsage(Path state, long calls, long input, long output)
      throws Exception {
    ObjectNode checkpoint =
        (ObjectNode) ContractObjectMapper.parseTree(Files.readString(state));
    ObjectNode usage = checkpoint.putObject("usageTotals");
    usage.put("calls", calls);
    usage.put("inputTokens", input);
    usage.put("outputTokens", output);
    usage.put("costUsd", 0.3d);
    usage.put("latencyMs", 20.0d);
    Files.writeString(state, ContractObjectMapper.write(checkpoint));
  }

  private static void writeCheckpointUsageUnchecked(
      Path state, long calls, long input, long output) {
    try {
      writeCheckpointUsage(state, calls, input, output);
    } catch (Exception exception) {
      throw new IllegalStateException("checkpoint failure injection could not be prepared", exception);
    }
  }
}
