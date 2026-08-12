package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunRepositoryResumeProjectionTest {
  @TempDir java.nio.file.Path temporaryDirectory;

  @Test
  void activeResumeMetadataTakesPrecedenceOverThePreviousTerminalResult() throws Exception {
    DesktopPaths paths = DesktopTestSupport.paths(temporaryDirectory.resolve("desktop-data"));
    String runId = "resume-stale-result";
    Files.createDirectories(paths.safeRunDirectory(runId).resolve("structured"));
    RunRepository repository = new RunRepository(paths, DesktopTestSupport.MAPPER);
    Instant now = Instant.now();
    repository.writeMetadata(
        new DesktopRunMetadata(
            runId,
            "smoke",
            "failed",
            now,
            now,
            "solve",
            "failed",
            ProcessHandle.current().pid()));
    repository.writeResult(
        runId,
        Map.of(
            "status", "failed",
            "task_status", "failed",
            "math_status", "unverified",
            "execution_status", "failed",
            "total_calls", 2,
            "total_usage", Map.of("total_tokens", 100, "estimated_cost_usd", 0.01)));
    assertEquals("failed", repository.summary(runId).get("lifecycle"));

    repository.writeMetadata(
        new DesktopRunMetadata(
            runId,
            "smoke",
            "queued",
            now,
            now,
            "resume",
            null,
            ProcessHandle.current().pid()));

    Map<String, Object> summary = repository.summary(runId);
    assertEquals("queued", summary.get("lifecycle"));
    assertEquals("queued", summary.get("status"));
    assertFalse((Boolean) summary.get("resumable"));
    Map<String, Object> detail = repository.detail(runId);
    assertTrue(((Map<?, ?>) detail.get("result")).isEmpty());
    assertEquals("", detail.get("report"));
  }
}
