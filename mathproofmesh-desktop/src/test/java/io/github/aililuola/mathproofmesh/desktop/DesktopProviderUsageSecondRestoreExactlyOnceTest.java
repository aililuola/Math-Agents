package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopProviderUsageSecondRestoreExactlyOnceTest {
  @TempDir Path temporaryDirectory;

  @Test
  void aSecondRestoreDoesNotCountTheSameDurableRequestTwice() throws Exception {
    String runId = "provider-usage-second-restore";
    Path runDirectory = temporaryDirectory.resolve("run");
    Path state = runDirectory.resolve("structured/desktop-solve-state.json");
    var bootstrap =
        new DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository(
            0,
            DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository.FailureMode
                .BEFORE_NEXT_PLAN,
            () -> {});
    DesktopLiveFailureUsageTestSupport.execute(
        DesktopLiveFailureUsageTestSupport.backend(
            temporaryDirectory.resolve("bootstrap-data"), bootstrap),
        runId,
        runDirectory);
    DesktopProviderUsageHardCrashTestSupport.writeProviderArtifacts(runDirectory, runId, 21);
    DesktopProviderUsageHardCrashTestSupport.writeCheckpointUsage(state, 20L);

    var firstCalls =
        new DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository(
            0,
            DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository.FailureMode
                .BEFORE_NEXT_PLAN,
            () -> {});
    RunExecutionBackend.RunExecutionResult first =
        DesktopLiveFailureUsageTestSupport.execute(
            DesktopLiveFailureUsageTestSupport.backend(
                temporaryDirectory.resolve("first-data"), firstCalls, "first-restore-request-"),
            runId,
            runDirectory);
    long firstTotal = first.usage().providerCalls();
    assertThat(firstTotal).isEqualTo(21L + firstCalls.successfulCalls());

    var secondCalls =
        new DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository(
            0,
            DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository.FailureMode
                .BEFORE_NEXT_PLAN,
            () -> {});
    RunExecutionBackend.RunExecutionResult second =
        DesktopLiveFailureUsageTestSupport.execute(
            DesktopLiveFailureUsageTestSupport.backend(
                temporaryDirectory.resolve("second-data"), secondCalls, "second-restore-request-"),
            runId,
            runDirectory);

    assertThat(secondCalls.successfulCalls()).isZero();
    assertThat(second.usage().providerCalls()).isEqualTo(firstTotal);
    System.out.println("PROVIDER USAGE SECOND RESTORE DIAGNOSTIC");
    System.out.println("POST_SECOND_RESTORE_PROVIDER_CALLS=" + second.usage().providerCalls());
    System.out.println("POST_SECOND_RESTORE_DUPLICATE_CALLS=0");
    System.out.println("RESULT=PASS");
  }
}
