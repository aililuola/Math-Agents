package io.github.aililuola.mathproofmesh.desktop.benchmark;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Writes one sanitized, public-structure-only run evidence bundle. */
public final class OlympiadEvidenceBundleWriter {
  static final List<String> JSON_EVIDENCE_FILES =
      List.of(
          "concurrency-metrics.json",
          "strategies.json",
          "routes.json",
          "attempts.json",
          "claims.json",
          "obligations.json",
          "claim-court.json",
          "negative-knowledge.json",
          "proof-graph.json",
          "pivots.json",
          "artifacts.json",
          "computations.json",
          "epochs.json",
          "receipts.json",
          "checkpoints.json",
          "budget-decisions.json",
          "budget-usage.json",
          "zero-gain.json",
          "final-verification.json",
          "failure-attribution.json",
          "prompt-transport-audit.json");

  private OlympiadEvidenceBundleWriter() {}

  public static void write(
      Path benchmarkRoot,
      Path bundleDirectory,
      OlympiadBenchmarkHarness.RunRequest request,
      OlympiadBenchmarkHarness.RunOutcome outcome,
      OlympiadSecretRedactor redactor) {
    Objects.requireNonNull(benchmarkRoot, "benchmarkRoot");
    Path root = Objects.requireNonNull(bundleDirectory, "bundleDirectory").toAbsolutePath().normalize();
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(redactor, "redactor");

    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("benchmark_id", OlympiadBenchmarkPlan.BENCHMARK_ID);
    manifest.put("problem_id", request.spec().problemId());
    manifest.put("trial_id", request.spec().trialId());
    manifest.put("run_id", request.runId());
    manifest.put("baseline_commit", OlympiadBenchmarkPlan.BASELINE_COMMIT);
    manifest.put("problem_prompt_sha256", request.problem().sha256());
    manifest.put("root_goal_hash_initial", outcome.rootGoalHashInitial());
    manifest.put("root_goal_hash_final", outcome.rootGoalHashFinal());
    manifest.put("config_hash", outcome.configHash());
    manifest.put("pricing_hash", outcome.pricingHash());
    manifest.put("model_id", outcome.modelId());
    manifest.put("provider_id", outcome.providerId());
    manifest.put("coordination_key_label", request.spec().coordinationKeyLabel());
    manifest.put("research_key_labels", request.spec().researchKeyLabels());
    manifest.put("cold_start", true);
    manifest.put(
        "recovery_trial",
        request.spec().kind() == OlympiadBenchmarkPlan.RunKind.CONTROLLED_RECOVERY);
    manifest.put("started_at", request.startedAt().toString());
    manifest.put("ended_at", outcome.endedAt().toString());
    manifest.put("final_status", outcome.finalStatus().name());
    manifest.put("stop_reason", outcome.stopReason());
    manifest.put("external_score", null);
    manifest.put("bundle_complete", evidenceComplete(outcome));
    writeJson(root.resolve("run-manifest.json"), manifest, redactor);

    Object configSnapshot = outcome.evidenceDocuments().get("config-snapshot.redacted.yaml");
    writeDocument(
        root.resolve("config-snapshot.redacted.yaml"),
        configSnapshot == null
            ? "benchmark_id: "
                + OlympiadBenchmarkPlan.BENCHMARK_ID
                + "\nprovider: "
                + outcome.providerId()
                + "\nmodel: "
                + outcome.modelId()
                + "\ncoordination_key: "
                + request.spec().coordinationKeyLabel()
                + "\nresearch_keys: "
                + request.spec().researchKeyLabels()
                + "\nreal_credentials: redacted-in-memory-only\n"
            : configSnapshot,
        redactor);
    writeText(
        root.resolve("git-state.txt"),
        "branch="
            + OlympiadBenchmarkPlan.BRANCH
            + "\nhead="
            + OlympiadBenchmarkPlan.BASELINE_COMMIT
            + "\nbaseline_dirty=false\n",
        redactor);

    writeDocument(
        root.resolve("provider-usage.ndjson"),
        outcome.evidenceDocuments().get("provider-usage.ndjson"),
        redactor);
    for (String file : JSON_EVIDENCE_FILES) {
      Object document = outcome.evidenceDocuments().get(file);
      writeJson(
          root.resolve(file),
          document == null
              ? OlympiadBenchmarkHarness.missing("production projection unavailable")
              : document,
          redactor);
    }
    writeDocument(
        root.resolve("proof-debt-series.csv"),
        outcome.evidenceDocuments().get("proof-debt-series.csv"),
        redactor);
    writeText(
        root.resolve("final-proof.md"),
        outcome.finalProof().isBlank()
            ? "# Final proof\n\nMISSING: no public final proof was produced.\n"
            : outcome.finalProof(),
        redactor);

    Map<String, Object> issueMatrix = new LinkedHashMap<>();
    outcome
        .issueObservations()
        .forEach(
            (issue, observation) ->
                issueMatrix.put(
                    issue,
                    Map.of(
                        "violations",
                        observation.violations(),
                        "evidence_refs",
                        observation.evidenceRefs())));
    writeJson(root.resolve("issue-matrix.json"), issueMatrix, redactor);

    Map<String, Object> recovery = new LinkedHashMap<>();
    recovery.put("provider_call_replays", outcome.recoveryEvidence().providerCallReplays());
    recovery.put("task_losses", outcome.recoveryEvidence().taskLosses());
    recovery.put("state_drifts", outcome.recoveryEvidence().stateDrifts());
    recovery.put("before_hash", outcome.recoveryEvidence().beforeHash());
    recovery.put("after_hash", outcome.recoveryEvidence().afterHash());
    writeJson(root.resolve("recovery-evidence.json"), recovery, redactor);

    writeRedactionReport(root, redactor);
    OlympiadBundleChecksums.write(root);
  }

  private static boolean evidenceComplete(OlympiadBenchmarkHarness.RunOutcome outcome) {
    return outcome.evidenceDocuments().keySet().containsAll(JSON_EVIDENCE_FILES)
        && outcome.evidenceDocuments().containsKey("provider-usage.ndjson")
        && outcome.evidenceDocuments().containsKey("proof-debt-series.csv");
  }

  private static void writeRedactionReport(Path root, OlympiadSecretRedactor redactor) {
    OlympiadSecretRedactor.LeakReport first = redactor.scan(root);
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("files_scanned", first.filesScanned());
    report.put("secret_leaks", first.secretLeaks());
    report.put("authorization_header_leaks", first.authorizationHeaderLeaks());
    report.put("credential_pattern_leaks", first.credentialPatternLeaks());
    report.put("result", first.passed() ? "PASS" : "FAIL");
    writeJson(root.resolve("redaction-report.json"), report, redactor);
  }

  private static void writeDocument(
      Path path, Object document, OlympiadSecretRedactor redactor) {
    if (document instanceof String text) {
      writeText(path, text, redactor);
      return;
    }
    writeJson(
        path,
        document == null
            ? OlympiadBenchmarkHarness.missing("production projection unavailable")
            : document,
        redactor);
  }

  private static void writeJson(Path path, Object value, OlympiadSecretRedactor redactor) {
    writeText(path, ContractObjectMapper.write(value) + "\n", redactor);
  }

  private static void writeText(Path path, String value, OlympiadSecretRedactor redactor) {
    String sanitized = redactor.sanitize(Objects.requireNonNull(value, "value"));
    Path parent = Objects.requireNonNull(path.getParent(), "bundle file parent");
    try {
      Files.createDirectories(parent);
      Path temporary = Files.createTempFile(parent, ".benchmark.", ".tmp");
      try {
        Files.writeString(temporary, sanitized, StandardCharsets.UTF_8);
        try {
          Files.move(
              temporary,
              path,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
          Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark evidence file could not be written", exception);
    }
  }
}
