package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopProviderUsageHardCrashAfterArtifactTest {
  @TempDir Path temporaryDirectory;

  @Test
  void durableArtifactExtendsCheckpointAfterAnUncatchableProcessTermination() throws Exception {
    String runId = "provider-hard-crash-after-artifact";
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
    DesktopProviderUsageHardCrashTestSupport.writeProviderArtifacts(runDirectory, runId, 20);
    DesktopProviderUsageHardCrashTestSupport.writeCheckpointUsage(state, 20L);

    var crash =
        new DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository(
            0,
            DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository.FailureMode
                .AFTER_RESPONSE_ARTIFACT,
            () -> {},
            DesktopProviderUsageHardCrashTestSupport.SimulatedProcessTermination::new);
    assertThatThrownBy(
            () ->
                DesktopLiveFailureUsageTestSupport.execute(
                    DesktopLiveFailureUsageTestSupport.backend(
                        temporaryDirectory.resolve("crash-data"), crash, "crash-request-"),
                    runId,
                    runDirectory))
        .isInstanceOf(DesktopProviderUsageHardCrashTestSupport.SimulatedProcessTermination.class);
    long durableArtifactCalls =
        ProviderUsageRecovery.recoverEvidence(
                runDirectory, DesktopLiveFailureUsageTestSupport.config())
            .size();
    assertThat(durableArtifactCalls).isEqualTo(21L);

    var resumedCalls =
        new DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository(
            1_000,
            DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository.FailureMode
                .BEFORE_NEXT_PLAN,
            () -> {});
    RunExecutionBackend.RunExecutionResult resumed =
        DesktopLiveFailureUsageTestSupport.execute(
            DesktopLiveFailureUsageTestSupport.backend(
                temporaryDirectory.resolve("resume-data"), resumedCalls, "resume-request-"),
            runId,
            runDirectory);

    long postRestoreCalls = resumed.usage().providerCalls() - resumedCalls.successfulCalls();
    assertThat(postRestoreCalls).isEqualTo(21L);
    System.out.println("PROVIDER USAGE HARD CRASH AFTER ARTIFACT DIAGNOSTIC");
    System.out.println("HARD_CRASHES_INJECTED=1");
    System.out.println("CHECKPOINT_PROVIDER_CALLS=20");
    System.out.println("DURABLE_ARTIFACT_PROVIDER_CALLS=" + durableArtifactCalls);
    System.out.println("POST_RESTORE_PROVIDER_CALLS=" + postRestoreCalls);
    System.out.println("POST_RESTORE_PROVIDER_CALL_LOSSES=0");
    System.out.println("RESULT=PASS");
  }
}
