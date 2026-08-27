package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopFailureUsageArtifactRecoveryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void durableResponseArtifactRecoversUsageBeforeTheLedgerCommit() {
    String runId = "artifact-usage-recovery";
    Path runDirectory = temporaryDirectory.resolve("run");
    var calls =
        new DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository(
            0,
            DesktopLiveFailureUsageTestSupport.FailingProviderCallRepository.FailureMode
                .AFTER_RESPONSE_ARTIFACT,
            () -> {});

    RunExecutionBackend.RunExecutionResult failure =
        DesktopLiveFailureUsageTestSupport.execute(
            DesktopLiveFailureUsageTestSupport.backend(
                temporaryDirectory.resolve("desktop-data"), calls),
            runId,
            runDirectory);

    assertThat(calls.successfulCalls()).isZero();
    assertThat(failure.usage().providerCalls()).isEqualTo(1L);
    assertThat(failure.usage().inputTokens()).isEqualTo(7L);
    assertThat(failure.usage().outputTokens()).isEqualTo(11L);
    assertThat(failure.usage().providerCallEvidence()).hasSize(1);
    long duplicateCounts =
        failure.usage().providerCallEvidence().size()
            - failure.usage().providerCallEvidence().stream()
                .map(call -> call.providerRequestId())
                .distinct()
                .count();
    assertThat(duplicateCounts).isZero();

    System.out.println("FAILURE USAGE ARTIFACT RECOVERY DIAGNOSTIC");
    System.out.println("ARTIFACT_PROVIDER_CALLS_RECOVERED=" + failure.usage().providerCalls());
    System.out.println("DUPLICATE_PROVIDER_CALL_COUNTS=" + duplicateCounts);
    System.out.println("RESULT=PASS");
  }
}
