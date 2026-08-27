package io.github.aililuola.mathproofmesh.desktop.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Strict bundle/schema/invariant validator used at every phase gate. */
public final class OlympiadEvidenceBundleValidator {
  private static final List<String> REQUIRED_FILES =
      java.util.stream.Stream.concat(
              OlympiadEvidenceBundleWriter.JSON_EVIDENCE_FILES.stream(),
              java.util.stream.Stream.of(
                  "run-manifest.json",
                  "config-snapshot.redacted.yaml",
                  "git-state.txt",
                  "provider-usage.ndjson",
                  "proof-debt-series.csv",
                  "final-proof.md",
                  "issue-matrix.json",
                  "recovery-evidence.json",
                  "redaction-report.json",
                  "checksums.sha256"))
          .sorted()
          .toList();

  private OlympiadEvidenceBundleValidator() {}

  public static Validation validate(
      Path bundleDirectory,
      OlympiadBenchmarkHarness.RunRequest request,
      OlympiadGitExecutionState gitExecutionState,
      OlympiadSecretRedactor redactor) {
    Path root = Objects.requireNonNull(bundleDirectory, "bundleDirectory").toAbsolutePath().normalize();
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(gitExecutionState, "gitExecutionState");
    Objects.requireNonNull(redactor, "redactor");
    List<String> codes = new ArrayList<>();
    for (String file : REQUIRED_FILES) {
      if (!Files.isRegularFile(root.resolve(file), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        codes.add("MISSING_REQUIRED_FILE");
      }
    }
    if (codes.isEmpty()) {
      validateManifest(root.resolve("run-manifest.json"), request, gitExecutionState, codes);
      validateGitState(root.resolve("git-state.txt"), gitExecutionState, codes);
      validateIssueMatrix(root.resolve("issue-matrix.json"), codes);
      validateRedaction(root.resolve("redaction-report.json"), codes);
      OlympiadSecretRedactor.LeakReport leaks = redactor.scan(root);
      if (!leaks.passed()) {
        codes.add("SECRET_SCAN_FAILED");
      }
      if (!OlympiadBundleChecksums.verify(root).passed()) {
        codes.add("CHECKSUM_VERIFICATION_FAILED");
      }
    }
    return new Validation(REQUIRED_FILES.size(), List.copyOf(codes));
  }

  private static void validateManifest(
      Path path,
      OlympiadBenchmarkHarness.RunRequest request,
      OlympiadGitExecutionState gitExecutionState,
      List<String> codes) {
    JsonNode manifest = parse(path, codes);
    if (manifest == null) {
      return;
    }
    if (!OlympiadBenchmarkPlan.BENCHMARK_ID.equals(manifest.path("benchmark_id").asText())
        || !request.spec().problemId().equals(manifest.path("problem_id").asText())
        || !request.spec().trialId().equals(manifest.path("trial_id").asText())
        || !request.runId().equals(manifest.path("run_id").asText())
        || !OlympiadBenchmarkPlan.BASELINE_COMMIT.equals(manifest.path("baseline_commit").asText())
        || !gitExecutionState.branch().equals(manifest.path("execution_branch").asText())
        || !gitExecutionState.head().equals(manifest.path("execution_commit").asText())
        || gitExecutionState.dirty() != manifest.path("execution_dirty").asBoolean(!gitExecutionState.dirty())
        || !request.problem().sha256().equals(manifest.path("problem_prompt_sha256").asText())
        || !manifest.path("cold_start").asBoolean(false)
        || !manifest.path("external_score").isNull()) {
      codes.add("RUN_MANIFEST_SCHEMA_FAILED");
    }
    if (!manifest.path("root_goal_hash_initial").asText()
        .equals(manifest.path("root_goal_hash_final").asText())) {
      codes.add("ROOT_GOAL_DRIFT");
    }
  }

  private static void validateGitState(
      Path path, OlympiadGitExecutionState gitExecutionState, List<String> codes) {
    try {
      String state = Files.readString(path, StandardCharsets.UTF_8);
      if (!state.contains("branch=" + gitExecutionState.branch() + "\n")
          || !state.contains("head=" + gitExecutionState.head() + "\n")
          || !state.contains("dirty=" + gitExecutionState.dirty() + "\n")
          || !state.contains(
              "benchmark_origin_commit=" + OlympiadBenchmarkPlan.BASELINE_COMMIT + "\n")) {
        codes.add("GIT_EXECUTION_STATE_MISMATCH");
      }
    } catch (IOException exception) {
      codes.add("GIT_EXECUTION_STATE_MISMATCH");
    }
  }

  private static void validateIssueMatrix(Path path, List<String> codes) {
    JsonNode matrix = parse(path, codes);
    if (matrix == null) {
      return;
    }
    for (int issue = 1; issue <= 13; issue++) {
      JsonNode entry = matrix.path("issue_%03d".formatted(issue));
      if (!entry.isObject()
          || !entry.path("violations").canConvertToInt()
          || entry.path("violations").asInt(-1) < 0
          || !entry.path("evidence_refs").isArray()) {
        codes.add("ISSUE_MATRIX_SCHEMA_FAILED");
        return;
      }
    }
  }

  private static void validateRedaction(Path path, List<String> codes) {
    JsonNode report = parse(path, codes);
    if (report == null
        || !"PASS".equals(report.path("result").asText())
        || report.path("secret_leaks").asInt(-1) != 0
        || report.path("authorization_header_leaks").asInt(-1) != 0
        || report.path("credential_pattern_leaks").asInt(-1) != 0) {
      codes.add("REDACTION_REPORT_FAILED");
    }
  }

  private static JsonNode parse(Path path, List<String> codes) {
    try {
      return ContractObjectMapper.parseTree(Files.readString(path, StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException exception) {
      codes.add("INVALID_JSON_EVIDENCE");
      return null;
    }
  }

  public record Validation(int requiredFiles, List<String> failureCodes) {
    public Validation {
      if (requiredFiles < 1) {
        throw new IllegalArgumentException("requiredFiles must be positive");
      }
      failureCodes = List.copyOf(Objects.requireNonNull(failureCodes, "failureCodes"));
    }

    @Override
    public List<String> failureCodes() {
      return List.copyOf(failureCodes);
    }

    public int failures() {
      return failureCodes.size();
    }

    public boolean passed() {
      return failureCodes.isEmpty();
    }
  }
}
