package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopProviderUsageHardCrashBeforeFirstCheckpointTest {
  @TempDir Path temporaryDirectory;

  @Test
  void startupRecoversDurableArtifactsWhenNoSemanticCheckpointSurvived() {
    String runId = "provider-hard-crash-before-checkpoint";
    Path runDirectory = temporaryDirectory.resolve("run");
    Path state = runDirectory.resolve("structured/desktop-solve-state.json");
    DesktopProviderUsageHardCrashTestSupport.writeProviderArtifacts(runDirectory, runId, 2);
    var crash =
        new DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository(
            0,
            DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository.FailureMode
                .AFTER_RESPONSE_ARTIFACT,
            () -> DesktopProviderUsageHardCrashTestSupport.deleteCheckpoint(state),
            DesktopProviderUsageHardCrashTestSupport.SimulatedProcessTermination::new);
    assertThatThrownBy(
            () ->
                DesktopLiveFailureUsageTestSupport.execute(
                    DesktopLiveFailureUsageTestSupport.backend(
                        temporaryDirectory.resolve("crash-data"), crash, "early-crash-request-"),
                    runId,
                    runDirectory))
        .isInstanceOf(DesktopProviderUsageHardCrashTestSupport.SimulatedProcessTermination.class);
    assertThat(state).doesNotExist();
    long preCheckpointDurableCalls;
    try {
      preCheckpointDurableCalls =
          ProviderUsageRecovery.recoverEvidence(
                  runDirectory, DesktopLiveFailureUsageTestSupport.config())
              .size();
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
    assertThat(preCheckpointDurableCalls).isEqualTo(3L);

    var resumedCalls =
        new DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository(
            1_000,
            DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository.FailureMode
                .BEFORE_NEXT_PLAN,
            () -> {});
    RunExecutionBackend.RunExecutionResult resumed =
        DesktopLiveFailureUsageTestSupport.execute(
            DesktopLiveFailureUsageTestSupport.backend(
                temporaryDirectory.resolve("resume-data"), resumedCalls, "early-resume-request-"),
            runId,
            runDirectory);

    long postRestoreCalls = resumed.usage().providerCalls() - resumedCalls.successfulCalls();
    assertThat(postRestoreCalls)
        .as(
            "status=%s durable=%s resumed_successes=%s",
            resumed.status(), preCheckpointDurableCalls, resumedCalls.successfulCalls())
        .isEqualTo(3L);
    System.out.println("PROVIDER USAGE HARD CRASH BEFORE FIRST CHECKPOINT DIAGNOSTIC");
    System.out.println("HARD_CRASHES_INJECTED=1");
    System.out.println("PRECHECKPOINT_DURABLE_PROVIDER_CALLS=" + preCheckpointDurableCalls);
    System.out.println("PRECHECKPOINT_POST_RESTORE_PROVIDER_CALLS=" + postRestoreCalls);
    System.out.println("EARLY_HARD_CRASH_PROVIDER_CALL_LOSSES=0");
    System.out.println("RESULT=PASS");
  }
}
