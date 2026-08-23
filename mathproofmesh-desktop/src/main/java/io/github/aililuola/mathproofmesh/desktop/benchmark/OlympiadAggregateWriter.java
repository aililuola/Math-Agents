package io.github.aililuola.mathproofmesh.desktop.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Generates external-scoring-ready aggregate files without inventing mathematical scores. */
public final class OlympiadAggregateWriter {
  private OlympiadAggregateWriter() {}

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
      StringBuilder failures =
          new StringBuilder(
              "problem_id,trial_id,run_id,execution_status,current_stage,failure_attribution,external_comments\n");
      int[] issueViolations = new int[13];
      int[] issueRuns = new int[13];
      Map<String, ProviderTotals> providerTotals = new LinkedHashMap<>();
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
        collectIssueMatrix(run.bundleDirectory(), issueViolations, issueRuns);
        collectProviderUsage(run.bundleDirectory(), providerTotals);
        JsonNode attribution = readJson(run.bundleDirectory().resolve("failure-attribution.json"));
        failures
            .append(csv(run.spec().problemId()))
            .append(',')
            .append(csv(run.spec().trialId()))
            .append(',')
            .append(csv(run.runId()))
            .append(',')
            .append(csv(attribution.path("execution_status").asText()))
            .append(',')
            .append(csv(attribution.path("current_stage").asText()))
            .append(',')
            .append(csv("PENDING_INDEPENDENT_ATTRIBUTION"))
            .append(",\n");
      }
      StringBuilder issues =
          new StringBuilder("issue,total_violations,runs_with_violations\n");
      for (int issue = 1; issue <= 13; issue++) {
        issues
            .append("issue_%03d".formatted(issue))
            .append(',')
            .append(issueViolations[issue - 1])
            .append(',')
            .append(issueRuns[issue - 1])
            .append('\n');
      }
      StringBuilder providerUsage =
          new StringBuilder(
              "key_label,calls,input_tokens,output_tokens,total_tokens,cost_usd\n");
      for (String label : OlympiadBenchmarkPlan.KEY_LABELS) {
        ProviderTotals totals = providerTotals.getOrDefault(label, ProviderTotals.zero());
        providerUsage
            .append(label)
            .append(',')
            .append(totals.calls())
            .append(',')
            .append(totals.inputTokens())
            .append(',')
            .append(totals.outputTokens())
            .append(',')
            .append(Math.addExact(totals.inputTokens(), totals.outputTokens()))
            .append(',')
            .append(totals.costUsd().toPlainString())
            .append('\n');
      }
      Files.writeString(root.resolve("problem-scores.csv"), scores, StandardCharsets.UTF_8);
      Files.writeString(root.resolve("cost-and-token-summary.csv"), costs, StandardCharsets.UTF_8);
      Files.writeString(root.resolve("issue-001-013-matrix.csv"), issues, StandardCharsets.UTF_8);
      Files.writeString(root.resolve("provider-key-usage.csv"), providerUsage, StandardCharsets.UTF_8);
      Files.writeString(root.resolve("failure-attribution.csv"), failures, StandardCharsets.UTF_8);
      Files.writeString(
          root.resolve("historical-P16-comparison.md"),
          "# P16 Historical Comparison\n\n"
              + "The current P16 run evidence is included in this campaign. Historical metrics "
              + "remain pending extraction from the user-owned prior report and were not exposed "
              + "to any provider prompt.\n",
          StandardCharsets.UTF_8);
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

  private static void collectIssueMatrix(
      Path bundle, int[] issueViolations, int[] issueRuns) {
    JsonNode matrix = readJson(bundle.resolve("issue-matrix.json"));
    for (int issue = 1; issue <= 13; issue++) {
      int violations = matrix.path("issue_%03d".formatted(issue)).path("violations").asInt(0);
      issueViolations[issue - 1] = Math.addExact(issueViolations[issue - 1], violations);
      if (violations > 0) {
        issueRuns[issue - 1] = Math.addExact(issueRuns[issue - 1], 1);
      }
    }
  }

  private static void collectProviderUsage(
      Path bundle, Map<String, ProviderTotals> totals) {
    Path usage = bundle.resolve("provider-usage.ndjson");
    try {
      for (String line : Files.readAllLines(usage, StandardCharsets.UTF_8)) {
        if (line.isBlank()) {
          continue;
        }
        JsonNode record = ContractObjectMapper.parseTree(line);
        String label = record.path("key_label").asText("UNAVAILABLE");
        if (!OlympiadBenchmarkPlan.KEY_LABELS.contains(label)) {
          continue;
        }
        ProviderTotals delta =
            new ProviderTotals(
                1L,
                record.path("input_tokens").asLong(0L),
                record.path("output_tokens").asLong(0L),
                decimal(record.path("cost_usd")));
        totals.merge(label, delta, ProviderTotals::plus);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("provider usage aggregate could not be read", exception);
    }
  }

  private static JsonNode readJson(Path path) {
    try {
      return ContractObjectMapper.parseTree(Files.readString(path, StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark aggregate evidence could not be read", exception);
    }
  }

  private static BigDecimal decimal(JsonNode value) {
    if (value.isNumber() || value.isTextual()) {
      try {
        return new BigDecimal(value.asText());
      } catch (NumberFormatException ignored) {
        return BigDecimal.ZERO;
      }
    }
    return BigDecimal.ZERO;
  }

  private static String csv(String value) {
    String normalized = Objects.toString(value, "");
    if (normalized.indexOf(',') < 0
        && normalized.indexOf('"') < 0
        && normalized.indexOf('\n') < 0
        && normalized.indexOf('\r') < 0) {
      return normalized;
    }
    return '"' + normalized.replace("\"", "\"\"") + '"';
  }

  private record ProviderTotals(
      long calls, long inputTokens, long outputTokens, BigDecimal costUsd) {
    private ProviderTotals {
      if (calls < 0 || inputTokens < 0 || outputTokens < 0) {
        throw new IllegalArgumentException("provider aggregate counters must not be negative");
      }
      costUsd = Objects.requireNonNull(costUsd, "costUsd");
    }

    private static ProviderTotals zero() {
      return new ProviderTotals(0L, 0L, 0L, BigDecimal.ZERO);
    }

    private ProviderTotals plus(ProviderTotals other) {
      return new ProviderTotals(
          Math.addExact(calls, other.calls),
          Math.addExact(inputTokens, other.inputTokens),
          Math.addExact(outputTokens, other.outputTokens),
          costUsd.add(other.costUsd));
    }
  }
}
