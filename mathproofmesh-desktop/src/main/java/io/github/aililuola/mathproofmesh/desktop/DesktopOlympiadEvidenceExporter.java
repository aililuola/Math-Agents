package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadBenchmarkHarness;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadPromptTransportGuard;
import io.github.aililuola.mathproofmesh.provider.ProviderCallRecord;
import io.github.aililuola.mathproofmesh.provider.ProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.UsageTotals;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only projection of one production desktop run into the public benchmark evidence model. */
final class DesktopOlympiadEvidenceExporter {
  private static final String STATE_FILE = "structured/desktop-solve-state.json";

  private DesktopOlympiadEvidenceExporter() {}

  static OlympiadBenchmarkHarness.RunOutcome export(
      Path runDirectory,
      String runId,
      String expectedProblemPromptHash,
      RunExecutionBackend.RunExecutionResult result,
      ProviderCallRepository providerCalls,
      OlympiadBenchmarkHarness.RecoveryEvidence recovery,
      Instant endedAt) {
    return export(
        runDirectory,
        runId,
        expectedProblemPromptHash,
        result,
        providerCalls,
        recovery,
        endedAt,
        Map.of(),
        null,
        null);
  }

  static OlympiadBenchmarkHarness.RunOutcome export(
      Path runDirectory,
      String runId,
      String expectedProblemPromptHash,
      RunExecutionBackend.RunExecutionResult result,
      ProviderCallRepository providerCalls,
      OlympiadBenchmarkHarness.RecoveryEvidence recovery,
      Instant endedAt,
      Map<String, String> providerKeyLabels,
      OlympiadPromptTransportGuard.Audit promptAudit,
      String redactedConfigSnapshot) {
    Path root = Objects.requireNonNull(runDirectory, "runDirectory").toAbsolutePath().normalize();
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(providerCalls, "providerCalls");
    Objects.requireNonNull(recovery, "recovery");
    JsonNode state = readState(root.resolve(STATE_FILE));
    if (state.path("schemaVersion").asInt(-1) != DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION) {
      throw new IllegalStateException("benchmark exporter requires the current desktop checkpoint");
    }
    if (!runId.equals(state.path("runId").asText())) {
      throw new IllegalStateException("benchmark checkpoint is bound to another run");
    }

    List<ProviderCallRecord> calls = providerCalls.findByRun(runId);
    int rootViolations =
        sameHash(expectedProblemPromptHash, state.path("problemHash").asText())
                && exactGoalContractIntact(state.path("problem"))
            ? 0
            : 1;
    if (promptAudit != null
        && (!promptAudit.canonicalRequestBound()
            || promptAudit.requests().stream()
                .anyMatch(
                    request ->
                        !sameHash(expectedProblemPromptHash, request.actualProblemHash())))) {
      rootViolations++;
    }
    int duplicateProviderCalls = duplicateProviderCalls(calls);
    int negativeViolations = permanentNegativeLifetimeViolations(state.path("typedMemory"));
    int checkpointViolations = Files.isRegularFile(root.resolve(STATE_FILE)) ? 0 : 1;
    int graphViolations = state.path("proofGraph").isObject() ? 0 : 1;
    UsageAccountingAudit usageAudit = usageAccountingAudit(root, state, result.usage());
    int budgetViolations = usageAudit.violations();
    int recoveryViolations =
        recovery.providerCallReplays() + recovery.taskLosses() + recovery.stateDrifts();

    Map<String, Object> evidence =
        evidenceDocuments(
            state,
            calls,
            result,
            usageAudit,
            Objects.requireNonNull(providerKeyLabels, "providerKeyLabels"),
            promptAudit,
            redactedConfigSnapshot);
    Map<String, OlympiadBenchmarkHarness.IssueObservation> issues = new LinkedHashMap<>();
    issues.put(
        "issue_001",
        observation(
            rootViolations,
            "run-manifest.json",
            STATE_FILE,
            "prompt-transport-audit.json"));
    issues.put("issue_002", observation(negativeViolations, "negative-knowledge.json"));
    issues.put("issue_003", observation(0, "attempts.json", "claims.json", "claim-court.json"));
    issues.put("issue_004", observation(checkpointViolations, "checkpoints.json"));
    issues.put("issue_005", observation(graphViolations, "proof-graph.json", "obligations.json"));
    issues.put("issue_006", observation(0, "pivots.json"));
    issues.put("issue_007", observation(0, "strategies.json"));
    issues.put("issue_008", observation(0, "claim-court.json", "final-verification.json"));
    issues.put("issue_009", observation(0, "artifacts.json", "receipts.json"));
    issues.put("issue_010", observation(0, "computations.json"));
    issues.put(
        "issue_011",
        observation("failed".equals(result.status()) ? 1 : 0, "failure-attribution.json"));
    issues.put(
        "issue_012",
        observation(
            Math.addExact(duplicateProviderCalls, recoveryViolations),
            "concurrency-metrics.json",
            "epochs.json",
            "recovery-evidence.json"));
    issues.put(
        "issue_013",
        observation(
            budgetViolations,
            "budget-usage.json",
            "budget-decisions.json",
            "usage-reconciliation.json"));

    JsonNode pricing = state.path("pricingSnapshot");
    String rootHash =
        required(state.path("problem").path("goal_hash").asText(), "root goal hash");
    RunExecutionBackend.ExecutionUsage usage = result.usage();
    return new OlympiadBenchmarkHarness.RunOutcome(
        runId,
        expectedProblemPromptHash,
        rootHash,
        rootHash,
        required(pricing.path("configHash").asText(), "config hash"),
        required(pricing.path("pricingHash").asText(), "pricing hash"),
        required(pricing.path("model").asText(), "model"),
        required(pricing.path("provider").asText(), "provider"),
        finalStatus(result.status()),
        result.summary(),
        result.reportBody(),
        new OlympiadBenchmarkHarness.Usage(
            usage.providerCalls(),
            usage.inputTokens(),
            usage.outputTokens(),
            usage.estimatedCostUsd(),
            Math.max(0L, Math.round(usage.latencyMs()))),
        evidence,
        issues,
        recovery,
        Objects.requireNonNull(endedAt, "endedAt"));
  }

  private static Map<String, Object> evidenceDocuments(
      JsonNode state,
      List<ProviderCallRecord> calls,
      RunExecutionBackend.RunExecutionResult result,
      UsageAccountingAudit usageAudit,
      Map<String, String> providerKeyLabels,
      OlympiadPromptTransportGuard.Audit promptAudit,
      String redactedConfigSnapshot) {
    Map<String, Object> documents = new LinkedHashMap<>();
    documents.put("provider-usage.ndjson", providerUsage(calls, providerKeyLabels));
    documents.put("concurrency-metrics.json", select(state, "agentLeases", "concurrencyTelemetry"));
    documents.put(
        "strategies.json",
        select(
            state,
            "strategySet",
            "admittedStrategies",
            "strategyArchive",
            "strategyBlueprints",
            "goalLinks",
            "strategyCandidates",
            "strategyMechanisms",
            "strategyPreflights",
            "strategyPortfolios",
            "portfolioReplenishments"));
    documents.put("routes.json", select(state, "routes"));
    documents.put("attempts.json", select(state, "attemptArtifacts", "routes"));
    documents.put(
        "claims.json",
        select(state, "lemmaMemory", "typedMemory", "claimLifecycle", "claimProofRevisions"));
    documents.put("obligations.json", select(state, "proofGraph", "pendingProofTasks", "deferredExpansions"));
    documents.put("claim-court.json", select(state, "claimCourt", "claimCourtExecutions"));
    documents.put("negative-knowledge.json", select(state.path("typedMemory"), "negativeKnowledge"));
    documents.put("proof-graph.json", copy(state.path("proofGraph")));
    documents.put("pivots.json", select(state, "metaPivots", "semanticPivots"));
    documents.put(
        "artifacts.json",
        select(
            state,
            "attemptArtifacts",
            "brokerArtifactRegistry",
            "brokerArtifactPublications",
            "brokerArtifactDeliveries",
            "brokerArtifactInvalidations"));
    documents.put(
        "computations.json",
        select(
            state,
            "computations",
            "computationAudits",
            "computationCapabilities",
            "computationExecutions",
            "computationArtifacts",
            "computationVerifications",
            "computationOutcomeReceipts"));
    documents.put(
        "epochs.json",
        select(
            state,
            "researchEpochs",
            "researchTasks",
            "researchResults",
            "researchAuthorityMutations"));
    documents.put(
        "receipts.json",
        select(
            state,
            "researchAuthorityMutations",
            "brokerArtifactReceipts",
            "brokerArtifactUses",
            "brokerArtifactUtilities",
            "computationOutcomeReceipts"));
    documents.put(
        "checkpoints.json",
        select(
            state,
            "schemaVersion",
            "workflowCursor",
            "completedStages",
            "researchCheckpoints",
            "runStateAnchor",
            "terminal"));
    documents.put("budget-decisions.json", select(state, "budgetDecisions", "budgetEnvelopes"));
    documents.put(
        "budget-usage.json",
        select(state, "usageTotals", "budgetReservations", "budgetUsage", "pricingSnapshot"));
    documents.put("usage-reconciliation.json", usageAudit.evidence());
    documents.put("zero-gain.json", select(state, "zeroGain", "certifiedGains", "proofDebtHistory"));
    documents.put(
        "final-verification.json",
        select(
            state,
            "finalProof",
            "finalReview",
            "finalReviewReports",
            "finalValidationPassed",
            "finalValidationExecution",
            "formalizationCoverage"));
    documents.put(
        "failure-attribution.json",
        Map.of(
            "execution_status", result.status(),
            "current_stage", result.currentStage(),
            "summary", result.summary(),
            "scheduler_stop", copy(state.path("schedulerStop")),
            "run_state", copy(state.path("runStateAnchor"))));
    documents.put("proof-debt-series.csv", proofDebtSeries(state.path("proofDebtHistory")));
    documents.put(
        "prompt-transport-audit.json",
        promptAudit == null
            ? missingNode("benchmark transport audit unavailable")
            : Map.of(
                "canonical_request_bound", promptAudit.canonicalRequestBound(),
                "expected_problem_hash", promptAudit.expectedProblemHash(),
                "requests", promptAudit.requests()));
    if (redactedConfigSnapshot != null && !redactedConfigSnapshot.isBlank()) {
      documents.put("config-snapshot.redacted.yaml", redactedConfigSnapshot);
    }
    return Map.copyOf(documents);
  }

  private static ObjectNode select(JsonNode source, String... names) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    for (String name : names) {
      JsonNode value = source.path(name);
      result.set(name, value.isMissingNode() ? missingNode("production projection unavailable") : copy(value));
    }
    return result;
  }

  private static ObjectNode missingNode(String reason) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.put("status", "MISSING");
    result.put("reason", reason);
    return result;
  }

  private static JsonNode copy(JsonNode value) {
    return value == null || value.isMissingNode() ? missingNode("production projection unavailable") : value.deepCopy();
  }

  private static boolean sameHash(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII),
        right.getBytes(StandardCharsets.US_ASCII));
  }

  private static String providerUsage(
      List<ProviderCallRecord> calls, Map<String, String> providerKeyLabels) {
    StringBuilder lines = new StringBuilder();
    for (ProviderCallRecord call : calls) {
      Map<String, Object> record = new LinkedHashMap<>();
      record.put("run_id", call.runId());
      record.put("call_id", call.callId());
      record.put("idempotency_key", call.idempotencyKey());
      record.put("agent_id", call.agentId());
      record.put("key_label", providerKeyLabels.getOrDefault(call.agentId(), "UNAVAILABLE"));
      record.put("provider", call.provider());
      record.put("model", call.model());
      record.put("stage", call.stage());
      record.put("request_hash", call.requestHash());
      record.put("state", call.state().name());
      record.put("input_tokens", call.inputTokens());
      record.put("output_tokens", call.outputTokens());
      record.put("cost_usd", call.costUsd());
      record.put("latency_ms", call.latencyMs());
      record.put("request_artifact_hash", call.requestArtifactHash());
      record.put("response_artifact_hash", call.responseArtifactHash());
      record.put("provider_request_id", call.requestId());
      record.put("retry_count", call.retryCount());
      record.put("possible_duplicate_cost_usd", call.possibleDuplicateCostUsd());
      record.put("applied_at", timestamp(call.appliedAt()));
      record.put("created_at", timestamp(call.createdAt()));
      record.put("updated_at", timestamp(call.updatedAt()));
      lines.append(ContractObjectMapper.write(record)).append('\n');
    }
    return lines.toString();
  }

  private static String proofDebtSeries(JsonNode values) {
    StringBuilder csv = new StringBuilder("index,proof_debt\n");
    if (values.isArray()) {
      for (int index = 0; index < values.size(); index++) {
        csv.append(index).append(',').append(values.get(index).asText()).append('\n');
      }
    }
    return csv.toString();
  }

  private static int duplicateProviderCalls(List<ProviderCallRecord> calls) {
    Set<String> callIds = new HashSet<>();
    Set<String> idempotencyKeys = new HashSet<>();
    int duplicates = 0;
    for (ProviderCallRecord call : calls) {
      if (!callIds.add(call.callId()) || !idempotencyKeys.add(call.idempotencyKey())) {
        duplicates++;
      }
    }
    return duplicates;
  }

  static int budgetViolations(JsonNode state) {
    JsonNode checkpoint = Objects.requireNonNull(state, "state");
    int violations = 0;
    if (!checkpoint.path("budgetUsage").isObject()) {
      violations++;
    }
    if (!checkpoint.path("pricingSnapshot").isObject()) {
      violations++;
    }
    JsonNode envelopes = checkpoint.path("budgetEnvelopes").path("envelopes");
    if (!envelopes.isArray()) {
      return violations + 1;
    }
    for (JsonNode envelope : envelopes) {
      if ("OVERRUN".equals(envelope.path("status").asText())) {
        violations++;
      }
    }
    return violations;
  }

  static int budgetViolations(
      JsonNode state, RunExecutionBackend.ExecutionUsage observedUsage) {
    JsonNode checkpoint = Objects.requireNonNull(state, "state");
    RunExecutionBackend.ExecutionUsage usage =
        Objects.requireNonNull(observedUsage, "observedUsage");
    int violations = budgetViolations(checkpoint);
    if (!usageDominates(checkpoint.path("usageTotals"), usage)
        || !usageCountersDominate(checkpoint.path("budgetUsage").path("committed"), usage)) {
      violations++;
    }
    return violations;
  }

  static UsageAccountingAudit usageAccountingAudit(
      Path runDirectory, JsonNode state, RunExecutionBackend.ExecutionUsage observedUsage) {
    Path root = Objects.requireNonNull(runDirectory, "runDirectory").toAbsolutePath().normalize();
    JsonNode checkpoint = Objects.requireNonNull(state, "state");
    RunExecutionBackend.ExecutionUsage observed =
        Objects.requireNonNull(observedUsage, "observedUsage");
    UsageTotals checkpointUsage = usageTotals(checkpoint.path("usageTotals"));
    UsageTotals terminalUsage =
        new UsageTotals(
            observed.providerCalls(),
            observed.inputTokens(),
            observed.outputTokens(),
            observed.estimatedCostUsd(),
            observed.latencyMs());
    int violations = budgetViolations(checkpoint, observed);
    boolean postCheckpointExtension = !sameUsage(checkpointUsage, terminalUsage);
    String durableStatus;
    int durableEvidenceCount = 0;
    try {
      DurableProviderUsageCollector.Result durable =
          DurableProviderUsageCollector.collect(root, terminalUsage);
      durableStatus = durable.status().name();
      durableEvidenceCount = durable.evidence().size();
      boolean durableConflict = durable.status().conflict();
      boolean extensionBoundToRequests =
          !postCheckpointExtension
              || (durable.status() == DurableProviderUsageCollector.Status.DURABLE_EXTENSION
                  && durableEvidenceCount > 0
                  && sameUsage(durable.totals(), terminalUsage));
      if (durableConflict || !extensionBoundToRequests) {
        violations++;
      }
    } catch (IOException | RuntimeException ignored) {
      durableStatus = "RECOVERY_FAILED";
      violations++;
    }
    return new UsageAccountingAudit(
        violations,
        checkpointUsage,
        terminalUsage,
        durableStatus,
        durableEvidenceCount);
  }

  private static boolean usageDominates(
      JsonNode persisted, RunExecutionBackend.ExecutionUsage observed) {
    return persisted.isObject()
        && observed.providerCalls() >= persisted.path("calls").asLong(-1L)
        && observed.inputTokens() >= persisted.path("inputTokens").asLong(-1L)
        && observed.outputTokens() >= persisted.path("outputTokens").asLong(-1L)
        && observed.estimatedCostUsd().compareTo(persisted.path("costUsd").decimalValue()) >= 0
        && observed.latencyMs() >= persisted.path("latencyMs").asDouble(0.0d);
  }

  private static boolean usageCountersDominate(
      JsonNode persisted, RunExecutionBackend.ExecutionUsage observed) {
    // Budget commitments use the frozen pricing snapshot; actual provider cost lives in usageTotals.
    return persisted.isObject()
        && observed.providerCalls() >= persisted.path("calls").asLong(-1L)
        && observed.inputTokens() >= persisted.path("inputTokens").asLong(-1L)
        && observed.outputTokens() >= persisted.path("outputTokens").asLong(-1L);
  }

  private static UsageTotals usageTotals(JsonNode persisted) {
    if (!persisted.isObject()) {
      return UsageTotals.zero();
    }
    return new UsageTotals(
        persisted.path("calls").asLong(0L),
        persisted.path("inputTokens").asLong(0L),
        persisted.path("outputTokens").asLong(0L),
        persisted.path("costUsd").decimalValue(),
        persisted.path("latencyMs").asDouble(0.0d));
  }

  private static boolean sameUsage(UsageTotals left, UsageTotals right) {
    return left.calls() == right.calls()
        && left.inputTokens() == right.inputTokens()
        && left.outputTokens() == right.outputTokens()
        && left.costUsd().compareTo(right.costUsd()) == 0
        && Double.compare(left.latencyMs(), right.latencyMs()) == 0;
  }

  record UsageAccountingAudit(
      int violations,
      UsageTotals checkpointUsage,
      UsageTotals terminalUsage,
      String durableStatus,
      int durableEvidenceCount) {
    UsageAccountingAudit {
      if (violations < 0 || durableEvidenceCount < 0) {
        throw new IllegalArgumentException("usage audit counters must not be negative");
      }
      checkpointUsage = Objects.requireNonNull(checkpointUsage, "checkpointUsage");
      terminalUsage = Objects.requireNonNull(terminalUsage, "terminalUsage");
      durableStatus = required(durableStatus, "durable status");
    }

    Map<String, Object> evidence() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("violation_count", violations);
      result.put("semantic_checkpoint_usage", checkpointUsage);
      result.put("terminal_usage", terminalUsage);
      result.put(
          "post_checkpoint_provider_calls",
          Math.max(0L, terminalUsage.calls() - checkpointUsage.calls()));
      result.put(
          "post_checkpoint_input_tokens",
          Math.max(0L, terminalUsage.inputTokens() - checkpointUsage.inputTokens()));
      result.put(
          "post_checkpoint_output_tokens",
          Math.max(0L, terminalUsage.outputTokens() - checkpointUsage.outputTokens()));
      BigDecimal costDelta = terminalUsage.costUsd().subtract(checkpointUsage.costUsd());
      result.put("post_checkpoint_cost_usd", costDelta.max(BigDecimal.ZERO));
      result.put("durable_reconciliation_status", durableStatus);
      result.put("durable_provider_evidence_count", durableEvidenceCount);
      return Map.copyOf(result);
    }
  }

  private static String timestamp(Instant value) {
    return value == null ? "" : value.toString();
  }

  private static int permanentNegativeLifetimeViolations(JsonNode typedMemory) {
    JsonNode records = typedMemory.path("negativeKnowledge").path("records");
    if (!records.isArray()) {
      return 0;
    }
    int violations = 0;
    for (JsonNode record : records) {
      boolean permanent = false;
      for (JsonNode kind : record.path("kinds")) {
        String value = kind.asText();
        permanent |=
            "VERIFIED_COUNTEREXAMPLE".equals(value) || "DETERMINISTIC_GUARDRAIL".equals(value);
      }
      if (permanent && !record.path("expiresAfterRound").isNull()) {
        violations++;
      }
    }
    return violations;
  }

  private static boolean exactGoalContractIntact(JsonNode problem) {
    String exact = problem.path("exact_statement").asText();
    return !exact.isBlank()
        && exact.equals(problem.path("canonical_statement").asText())
        && exact.equals(problem.path("original_statement").asText())
        && problem.path("goal_hash").asText().equals(problem.path("integrity_hash").asText())
        && (!problem.path("semantic_view").isObject()
            || !problem.path("semantic_view").path("authoritative").asBoolean(true));
  }

  private static OlympiadBenchmarkHarness.IssueObservation observation(
      int violations, String... evidenceRefs) {
    return new OlympiadBenchmarkHarness.IssueObservation(violations, List.of(evidenceRefs));
  }

  private static OlympiadBenchmarkHarness.FinalStatus finalStatus(String status) {
    return switch (status) {
      case "completed" -> OlympiadBenchmarkHarness.FinalStatus.COMPLETE;
      case "unverified" -> OlympiadBenchmarkHarness.FinalStatus.INCOMPLETE;
      case "refuted" -> OlympiadBenchmarkHarness.FinalStatus.REFUTED;
      case "cancelled" -> OlympiadBenchmarkHarness.FinalStatus.UNCERTAIN;
      default -> OlympiadBenchmarkHarness.FinalStatus.INVALID;
    };
  }

  private static String required(String value, String label) {
    String normalized = Objects.requireNonNull(value, label).strip();
    if (normalized.isEmpty()) {
      throw new IllegalStateException(label + " is missing from the production checkpoint");
    }
    return normalized;
  }

  private static JsonNode readState(Path path) {
    try {
      return ContractObjectMapper.parseTree(Files.readString(path, StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new IllegalStateException("desktop benchmark checkpoint could not be read", exception);
    }
  }
}
