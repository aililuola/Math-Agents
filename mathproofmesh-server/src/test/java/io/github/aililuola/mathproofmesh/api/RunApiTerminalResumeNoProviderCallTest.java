package io.github.aililuola.mathproofmesh.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunApiTerminalResumeNoProviderCallTest {
  @TempDir Path temporaryDirectory;

  @Test
  void terminalResumeReturnsAuthorityWithoutCallingBackend() {
    AtomicInteger calls = new AtomicInteger();
    RunExecutionBackend backend =
        (request, runId, traceId, directory, progress) -> {
          calls.incrementAndGet();
          return RunApiRecoverableResumeTest.result("completed");
        };
    RunApiService service =
        new RunApiService(
            new ApiObservability(new SimpleMeterRegistry()),
            temporaryDirectory.toString(),
            1,
            backend);
    service.solve(new SolveRequest("Prove P.", "terminal", null, "smoke"));
    service.resume(new ResumeRequest("terminal"));
    assertThat(calls).hasValue(1);
  }
}
