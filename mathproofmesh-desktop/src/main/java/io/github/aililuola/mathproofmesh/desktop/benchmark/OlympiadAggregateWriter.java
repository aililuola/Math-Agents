package io.github.aililuola.mathproofmesh.desktop.benchmark;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Generates external-scoring-ready aggregate files without inventing mathematical scores. */
public final class OlympiadAggregateWriter {
  private OlympiadAggregateWriter() {}

  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_OUT",
      justification =
          "All aggregate filenames are fixed children of the harness-owned aggregate directory.")
  public static void write(
      Path aggregateDirectory,
      List<OlympiadBenchmarkPlan.RunSpec> planned,
      List<OlympiadBenchmarkHarness.CompletedRun> completed) {
    Path root = Objects.requireNonNull(aggregateDirectory, "aggregateDirectory")
        .toAbsolutePath()
        .normalize();
    Objects.requireNonNull(planned, "planned");
    Objects.requireNonNull(completed, "completed");
    try {
      Files.createDirectories(root);
      StringBuilder scores =
          new StringBuilder("problem_id,trial_id,run_id,final_status,external_score\n");
      StringBuilder costs =
          new StringBuilder(
              "problem_id,trial_id,calls,input_tokens,output_tokens,total_tokens,cost_usd,latency_ms\n");
      for (OlympiadBenchmarkHarness.CompletedRun run : completed) {
        scores
            .append(run.spec().problemId())
            .append(',')
            .append(run.spec().trialId())
            .append(',')
            .append(run.runId())
            .append(',')
            .append(run.finalStatus())
            .append(",\n");
        costs
            .append(run.spec().problemId())
            .append(',')
            .append(run.spec().trialId())
            .append(',')
            .append(run.usage().calls())
            .append(',')
            .append(run.usage().inputTokens())
            .append(',')
            .append(run.usage().outputTokens())
            .append(',')
            .append(run.usage().totalTokens())
            .append(',')
            .append(run.usage().costUsd().toPlainString())
            .append(',')
            .append(run.usage().latencyMillis())
            .append('\n');
      }
      Files.writeString(root.resolve("problem-scores.csv"), scores, StandardCharsets.UTF_8);
      Files.writeString(root.resolve("cost-and-token-summary.csv"), costs, StandardCharsets.UTF_8);
      Files.writeString(
          root.resolve("benchmark-summary.md"),
          "# Benchmark Summary\n\n"
              + "- Planned runs: "
              + planned.size()
              + "\n- Completed runs: "
              + completed.size()
              + "\n- External scores: pending independent evaluation\n",
          StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark aggregate files could not be written", exception);
    }
  }
}
