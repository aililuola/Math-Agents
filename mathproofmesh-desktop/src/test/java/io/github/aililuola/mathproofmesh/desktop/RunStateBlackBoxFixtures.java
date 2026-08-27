package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class RunStateBlackBoxFixtures {
  private RunStateBlackBoxFixtures() {}

  static Fixture legacyRun(Path root, String runId, long calls, long input, long output)
      throws Exception {
    DesktopPaths paths = DesktopTestSupport.paths(root);
    Path run = paths.safeRunDirectory(runId);
    Files.createDirectories(run.resolve("structured"));
    Files.writeString(run.resolve("problem.txt"), "Prove a useful statement.");
    Files.writeString(
        run.resolve("structured/problem_contract.json"),
        "{\"original_statement\":\"Prove a useful statement.\"}");
    Map<String, Object> checkpoint = new LinkedHashMap<>();
    checkpoint.put("schemaVersion", 18);
    checkpoint.put("runId", runId);
    checkpoint.put("problemHash", "a".repeat(64));
    checkpoint.put("currentStage", "route_team");
    checkpoint.put("usageTotals", usage(calls, input, output));
    checkpoint.put("admittedStrategies", java.util.List.of(Map.of("strategyId", "s-1")));
    checkpoint.put("routes", java.util.List.of(Map.of("routeId", "r-1")));
    checkpoint.put("terminal", false);
    Files.writeString(
        run.resolve("structured/desktop-solve-state.json"),
        DesktopTestSupport.MAPPER.writeValueAsString(checkpoint));
    RunRepository repository = new RunRepository(paths, DesktopTestSupport.MAPPER);
    Instant now = Instant.now();
    repository.writeMetadata(
        new DesktopRunMetadata(
            runId, "smoke", "failed", now, now, "solve", "backend failure", 0L));
    repository.writeResult(
        runId,
        Map.of(
            "status", "failed",
            "task_status", "failed",
            "math_status", "unverified",
            "execution_status", "failed",
            "total_calls", 0,
            "total_usage", usage(0, 0, 0)));
    return new Fixture(paths, run, repository);
  }

  static Map<String, Object> usage(long calls, long input, long output) {
    return Map.of(
        "calls", calls,
        "inputTokens", input,
        "outputTokens", output,
        "costUsd", "1.25",
        "latencyMs", 100.0,
        "total_tokens", input + output,
        "estimated_cost_usd", 1.25);
  }

  static void assertCanonicalFailureVector(Map<String, Object> summary, long calls, long tokens) {
    assertThat(summary)
        .containsEntry("execution_status", "FAILED")
        .containsEntry("math_status", "PARTIAL_UNVERIFIED")
        .containsEntry("usage_status", "RECORDED")
        .containsEntry("campaign_status", "RECOVERABLE")
        .containsEntry("total_calls", calls)
        .containsEntry("total_tokens", tokens);
  }

  record Fixture(DesktopPaths paths, Path runDirectory, RunRepository repository) {}
}
