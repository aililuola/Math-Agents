package io.github.aililuola.mathproofmesh.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.runstate.RunCampaignStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunApiRecoverableResumeTest {
  @TempDir Path temporaryDirectory;

  @Test
  void recoverableRunResumesWithANewExecutionAttempt() {
    AtomicInteger calls = new AtomicInteger();
    RunExecutionBackend backend =
        (request, runId, traceId, directory, progress) -> {
          int call = calls.incrementAndGet();
          try {
            Files.createDirectories(directory.resolve("structured"));
            Files.writeString(
                directory.resolve("structured/desktop-solve-state.json"),
                "{\"terminal\":false,\"problemHash\":\"" + "1".repeat(64) + "\"}",
                StandardCharsets.UTF_8);
          } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
          }
          return result(call == 1 ? "failed" : "completed");
        };
    RunApiService service =
        new RunApiService(
            new ApiObservability(new SimpleMeterRegistry()),
            temporaryDirectory.toString(),
            1,
            backend);
    var failed = service.solve(new SolveRequest("Prove P.", "recoverable", null, "smoke"));
    String firstAttempt = failed.authorityStateHash();
    assertThat(failed.campaignStatus()).isEqualTo(RunCampaignStatus.RECOVERABLE);
    var resumed = service.resume(new ResumeRequest("recoverable"));
    assertThat(calls).hasValue(2);
    assertThat(resumed.authorityStateHash()).isNotEqualTo(firstAttempt);
  }

  static RunExecutionBackend.RunExecutionResult result(String status) {
    return new RunExecutionBackend.RunExecutionResult(
        status,
        "proof",
        status,
        List.of(),
        List.of(),
        "",
        1);
  }
}
