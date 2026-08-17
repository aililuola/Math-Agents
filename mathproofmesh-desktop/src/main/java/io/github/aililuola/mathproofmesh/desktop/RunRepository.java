package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceStore;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RunView;
import io.github.aililuola.mathproofmesh.runstate.FileRunStateStore;
import io.github.aililuola.mathproofmesh.runstate.RunAuthoritySnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunCampaignStatus;
import io.github.aililuola.mathproofmesh.runstate.RunExecutionStatus;
import io.github.aililuola.mathproofmesh.runstate.RunProjectionSnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunStateEvidenceBundle;
import io.github.aililuola.mathproofmesh.runstate.RunStateReconciler;
import io.github.aililuola.mathproofmesh.runstate.RunTerminalReason;
import io.github.aililuola.mathproofmesh.runstate.RunStateSnapshot;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Read-focused desktop projection and safe recycle boundary for local run directories. */
public final class RunRepository {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");

  private final DesktopPaths paths;
  private final ObjectMapper mapper;
  private final FileRunStateStore runStateStore;
  private final LegacyRunStateMigrator legacyRunStateMigrator;

  public RunRepository(DesktopPaths paths, ObjectMapper mapper) {
    this.paths = Objects.requireNonNull(paths, "paths");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.runStateStore = new FileRunStateStore(paths.runs());
    this.legacyRunStateMigrator = new LegacyRunStateMigrator(paths.runs(), mapper);
  }

  public Path runDirectory(String runId) {
    return paths.safeRunDirectory(runId);
  }

  public void writeMetadata(DesktopRunMetadata metadata) {
    writeMetadataProjection(metadata, null);
  }

  public void writeMetadataProjection(DesktopRunMetadata metadata, RunView view) {
    if (view == null
        && "resume".equals(metadata.mode())
        && "queued".equals(metadata.lifecycle())) {
      queueRecoverableResume(metadata);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("run_id", metadata.runId());
    payload.put("profile", metadata.profile());
    payload.put("lifecycle", metadata.lifecycle());
    payload.put("created_at", metadata.createdAt().toString());
    payload.put("updated_at", metadata.updatedAt().toString());
    payload.put("mode", metadata.mode());
    payload.put("error", metadata.error());
    payload.put("process_id", metadata.processId());
    if (view != null) {
      payload.put("execution_status", view.executionStatus().name());
      payload.put("math_status", view.mathStatus().name());
      payload.put("usage_status", view.usageStatus().name());
      payload.put("campaign_status", view.campaignStatus().name());
      payload.put("report_status", view.reportStatus().name());
      payload.put("authority_state_hash", view.authorityStateHash());
      payload.put("state_sequence", view.stateSequence());
      payload.put("provider_calls", view.providerCalls());
      payload.put("total_tokens", view.totalUsage().totalTokens());
    }
    writeJsonAtomically(metadataPath(metadata.runId()), payload);
  }

  private void queueRecoverableResume(DesktopRunMetadata metadata) {
    RunStateSnapshot previous = runStateStore.load(metadata.runId()).orElse(null);
    if (previous == null
        || previous.authority().campaignStatus() != RunCampaignStatus.RECOVERABLE) {
      return;
    }
    RunAuthoritySnapshot before = previous.authority();
    String attemptId = "desktop-resume-" + UUID.randomUUID().toString().replace("-", "");
    RunStateSnapshot queued =
        new RunStateReconciler()
            .reconcile(
                new RunStateEvidenceBundle(
                    metadata.runId(),
                    before.problemHash(),
                    attemptId,
                    RunExecutionStatus.QUEUED,
                    RunTerminalReason.NONE,
                    "resume",
                    !before.latestSemanticCheckpointRef().isBlank(),
                    false,
                    before.latestSemanticCheckpointRef(),
                    before.latestSemanticCheckpointHash(),
                    before.proofGraphHash(),
                    before.mathematicalProgress(),
                    List.of(),
                    previous,
                    previous.projection(),
                    metadata.updatedAt()))
            .state();
    runStateStore.compareAndSet(
        metadata.runId(), before.version(), queued, "desktop-resume", 0L);
  }

  /** Reconciles a failed execution without erasing durable semantic or usage evidence. */
  public RunStateSnapshot reconcileFailure(
      String runId, DesktopRunMetadata metadata, String failureType) {
    return reconcileExecution(
        runId,
        metadata,
        RunExecutionStatus.FAILED,
        RunTerminalReason.EXECUTION_FAILED,
        "EXECUTION_FAILURE:" + failureType);
  }

  public RunStateSnapshot reconcileCancellation(String runId, DesktopRunMetadata metadata) {
    return reconcileExecution(
        runId,
        metadata,
        RunExecutionStatus.CANCELLED,
        RunTerminalReason.USER_CANCELLED,
        "EXECUTION_CANCELLED");
  }

  private RunStateSnapshot reconcileExecution(
      String runId,
      DesktopRunMetadata metadata,
      RunExecutionStatus executionStatus,
      RunTerminalReason terminalReason,
      String diagnostic) {
    Path directory = checkedExistingRun(runId);
    RunStateSnapshot previous =
        runStateStore.load(runId).orElseGet(() -> legacyRunStateMigrator.migrate(directory, metadata));
    RunAuthoritySnapshot before = previous.authority();
    RunStateSnapshot next =
        new RunStateReconciler()
            .reconcile(
                new RunStateEvidenceBundle(
                    runId,
                    before.problemHash(),
                    before.executionAttemptId(),
                    executionStatus,
                    terminalReason,
                    before.currentStage(),
                    !before.latestSemanticCheckpointRef().isBlank(),
                    before.campaignStatus() == RunCampaignStatus.TERMINAL,
                    before.latestSemanticCheckpointRef(),
                    before.latestSemanticCheckpointHash(),
                    before.proofGraphHash(),
                    before.mathematicalProgress(),
                    List.of(),
                    previous,
                    new RunProjectionSnapshot(
                        before.authorityHash(),
                        previous.projection().reportStatus(),
                        previous.projection().runResultRef(),
                        previous.projection().runResultHash(),
                        previous.projection().desktopMetadataRef(),
                        previous.projection().desktopMetadataHash(),
                        previous.projection().reportRef(),
                        previous.projection().reportHash(),
                        previous.projection().latestActivitySequence(),
                        List.of(diagnostic),
                        null),
                    Instant.now()))
            .state();
    return runStateStore.compareAndSet(
        runId, before.version(), next, "desktop-run-manager", 0L);
  }

  public void writeResult(String runId, Map<String, Object> result) {
    Path destination =
        paths.safeRunDirectory(runId).resolve("structured").resolve("run_result.json");
    writeJsonAtomically(destination, new LinkedHashMap<>(Objects.requireNonNull(result, "result")));
  }

  public DesktopRunMetadata readMetadata(String runId) {
    Map<String, Object> payload = readObject(metadataPath(runId));
    if (payload.isEmpty()) {
      return null;
    }
    try {
      return new DesktopRunMetadata(
          String.valueOf(payload.get("run_id")),
          String.valueOf(payload.getOrDefault("profile", "smoke")),
          String.valueOf(payload.getOrDefault("lifecycle", "interrupted")),
          Instant.parse(String.valueOf(payload.get("created_at"))),
          Instant.parse(String.valueOf(payload.get("updated_at"))),
          String.valueOf(payload.getOrDefault("mode", "solve")),
          payload.get("error") == null ? null : String.valueOf(payload.get("error")),
          number(payload.get("process_id"), 0L));
    } catch (RuntimeException exception) {
      return null;
    }
  }

  public List<Map<String, Object>> listRuns() {
    List<Map<String, Object>> entries = new ArrayList<>();
    try (var stream = Files.list(paths.runs())) {
      stream
          .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          .filter(path -> !Files.isSymbolicLink(path))
          .forEach(
              directory -> {
                try {
                  Path fileName = directory.getFileName();
                  if (fileName == null) {
                    return;
                  }
                  Map<String, Object> summary = summary(fileName.toString());
                  if (!summary.isEmpty()) {
                    entries.add(summary);
                  }
                } catch (IllegalArgumentException ignored) {
                  // An invalid directory name is not part of the run inventory.
                }
              });
    } catch (IOException exception) {
      return List.of();
    }
    entries.sort(
        Comparator.comparing(
                (Map<String, Object> entry) ->
                    String.valueOf(entry.getOrDefault("updated_at", "")))
            .reversed());
    return List.copyOf(entries);
  }

  public Map<String, Object> summary(String runId) {
    Path directory = checkedExistingRun(runId);
    DesktopRunMetadata metadata = readMetadata(runId);
    if (metadata == null) {
      Instant modified;
      try {
        modified = Files.getLastModifiedTime(directory).toInstant();
      } catch (IOException exception) {
        modified = Instant.EPOCH;
      }
      metadata =
          new DesktopRunMetadata(
              runId,
              "smoke",
              "interrupted",
              modified,
              modified,
              "solve",
              null,
              0L);
    }
    DesktopRunMetadata resolvedMetadata = metadata;
    RunStateSnapshot state =
        runStateStore
            .load(runId)
            .orElseGet(() -> legacyRunStateMigrator.migrate(directory, resolvedMetadata));
    RunAuthoritySnapshot authority = state.authority();
    String lifecycle = lifecycle(authority);
    String title = readTitle(directory, runId);
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("run_id", runId);
    summary.put("title", title);
    summary.put("profile", metadata.profile());
    summary.put("lifecycle", lifecycle);
    summary.put("mode", metadata.mode());
    summary.put("created_at", metadata.createdAt().toString());
    summary.put("updated_at", metadata.updatedAt().toString());
    summary.put("status", compatibleStatus(authority));
    summary.put("task_status", compatibleStatus(authority));
    summary.put("math_status", authority.mathStatus().name());
    summary.put("execution_status", authority.executionStatus().name());
    summary.put("usage_status", authority.usageStatus().name());
    summary.put("campaign_status", authority.campaignStatus().name());
    summary.put("report_status", state.projection().reportStatus().name());
    summary.put("reconciliation_status", state.reconciliationStatus().name());
    summary.put("terminal_reason", authority.terminalReason().name());
    summary.put("authority_state_hash", authority.authorityHash());
    summary.put("state_sequence", authority.authoritySequence());
    summary.put("total_calls", authority.usage().providerCalls());
    summary.put("provider_calls", authority.usage().providerCalls());
    summary.put("total_tokens", authority.usage().totalTokens());
    summary.put("estimated_cost_usd", authority.usage().estimatedCostUsd());
    summary.put("resumable", authority.recoverable());
    return Collections.unmodifiableMap(summary);
  }

  public Map<String, Object> detail(String runId) {
    Path directory = checkedExistingRun(runId);
    Map<String, Object> detail = new LinkedHashMap<>();
    Map<String, Object> summary = summary(runId);
    boolean active = SetLike.ACTIVE.contains(String.valueOf(summary.get("lifecycle")));
    detail.put("summary", summary);
    detail.put("run_state", runStateStore.load(runId).orElseThrow());
    Map<String, Object> problem =
        readObject(directory.resolve("structured").resolve("problem_contract.json"));
    detail.put(
        "problem",
        String.valueOf(
            problem.getOrDefault(
                "original_statement", problem.getOrDefault("exact_statement", ""))));
    detail.put(
        "canonical_problem",
        String.valueOf(
            problem.getOrDefault(
                "canonical_statement", problem.getOrDefault("exact_statement", ""))));
    detail.put(
        "result",
        active
            ? Map.of()
            : readObject(directory.resolve("structured").resolve("run_result.json")));
    detail.put(
        "report",
        active
            ? ""
            : readBoundedText(
                directory.resolve("reports").resolve("run_report.md"), 1_000_000));
    detail.put("activity", readActivity(runId, 250));
    return Collections.unmodifiableMap(detail);
  }

  public List<Map<String, Object>> readActivity(String runId, Integer limit) {
    Path path = checkedExistingRun(runId).resolve("activity.jsonl");
    List<Map<String, Object>> events = readJsonLines(path, 10_000);
    List<Map<String, Object>> collapsed = DesktopApiModel.collapseActivity(events);
    if (limit == null || collapsed.size() <= limit) {
      return collapsed;
    }
    return List.copyOf(collapsed.subList(collapsed.size() - limit, collapsed.size()));
  }

  public Map<String, Object> reasoningSnapshot(String runId, String taskId) {
    String safeTask = DesktopApiModel.safeTaskId(taskId);
    Path directory = checkedExistingRun(runId);
    Map<String, Object> activity =
        readActivity(runId, null).stream()
            .filter(item -> safeTask.equals(String.valueOf(item.get("task_id"))))
            .findFirst()
            .orElse(Map.of());
    Path archive = reasoningPath(directory);
    ReasoningTraceStore.ReadResult read = ReasoningTraceStore.readRecords(archive, safeTask, 0L);
    List<Map<String, Object>> reasoningRecords = new ArrayList<>(read.records());
    reasoningRecords.addAll(ReasoningTraceStore.readPreviews(archive, safeTask));
    if (activity.isEmpty() && reasoningRecords.isEmpty()) {
      throw new IllegalArgumentException("reasoning task was not found");
    }

    Map<String, Object> built = ReasoningTraceStore.buildSnapshot(reasoningRecords);
    List<?> calls = built.get("calls") instanceof List<?> list ? list : List.of();
    String lifecycle = String.valueOf(summary(runId).getOrDefault("lifecycle", "interrupted"));
    boolean runActive = SetLike.ACTIVE.contains(lifecycle);
    String eventType =
        String.valueOf(
            activity.getOrDefault(
                "initial_event_type", activity.getOrDefault("event_type", "")));
    boolean recordable =
        "agent_call".equals(eventType)
            || activity.get("agent_id") != null
            || !reasoningRecords.isEmpty();
    boolean nodeRunning = "running".equals(String.valueOf(activity.getOrDefault("status", "")));
    Object latestStatusValue =
        calls.isEmpty() ? null : ((Map<?, ?>) calls.getLast()).get("status");
    String latestStatus = latestStatusValue == null ? "" : String.valueOf(latestStatusValue);
    boolean traceRunning = Boolean.TRUE.equals(built.get("running"));
    boolean hasRecords = Boolean.TRUE.equals(built.get("has_records"));
    boolean hasReasoning = Boolean.TRUE.equals(built.get("has_reasoning"));
    String traceState;
    if (traceRunning) {
      traceState = "running";
    } else if (recordable && runActive && nodeRunning && !hasRecords) {
      traceState = "waiting";
    } else if (hasReasoning) {
      traceState =
          java.util.Set.of("failed", "cancelled").contains(latestStatus)
              ? latestStatus
              : "completed";
    } else if (hasRecords) {
      traceState =
          java.util.Set.of("failed", "cancelled").contains(latestStatus)
              ? latestStatus
              : "no_reasoning";
    } else if (recordable && !runActive) {
      traceState = "legacy_unavailable";
    } else {
      traceState = "unavailable";
    }

    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("task_id", safeTask);
    snapshot.put("activity", activity);
    snapshot.put("recordable", recordable);
    snapshot.put("node_running", nodeRunning);
    snapshot.put("run_active", runActive);
    snapshot.put("run_lifecycle", lifecycle);
    snapshot.put("trace_state", traceState);
    snapshot.put(
        "reasoning_authority",
        Map.of(
            "status", "unverified",
            "label", "未验证推理",
            "premise_eligible", false,
            "description", "这是模型原始推理记录，不是检查点、Broker Fact 或独立验证结论。"));
    snapshot.put("archive", "reports/" + ReasoningTraceStore.FILE_NAME);
    snapshot.put("cursor", read.nextOffset());
    snapshot.putAll(built);
    return Collections.unmodifiableMap(castMap(DesktopApiModel.redact(snapshot)));
  }

  public ReasoningTraceStore.ReadResult readReasoningUpdates(
      String runId, String taskId, long offset) {
    String safeTask = DesktopApiModel.safeTaskId(taskId);
    Path directory = checkedExistingRun(runId);
    return ReasoningTraceStore.readRecords(
        reasoningPath(directory), safeTask, Math.max(0L, offset));
  }

  public List<Map<String, Object>> readReasoningPreviews(String runId, String taskId) {
    String safeTask = DesktopApiModel.safeTaskId(taskId);
    Path directory = checkedExistingRun(runId);
    return ReasoningTraceStore.readPreviews(reasoningPath(directory), safeTask);
  }

  public Map<String, Object> computationSnapshot(String runId, String taskId) {
    String safeTask = DesktopApiModel.safeTaskId(taskId);
    Map<String, Object> activity =
        readActivity(runId, null).stream()
            .filter(item -> safeTask.equals(String.valueOf(item.get("task_id"))))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("computation activity was not found"));
    String initialType =
        String.valueOf(
            activity.getOrDefault(
                "initial_event_type", activity.getOrDefault("event_type", "")));
    if (!safeTask.startsWith("computation:")
        && !java.util.Set.of(
                "python_experiment",
                "computation_experiment",
                "computation_decision",
                "experiment_completed")
            .contains(initialType)) {
      throw new IllegalArgumentException("activity is not a computation");
    }
    Map<String, Object> metrics = mapValue(activity.get("metrics"));
    String requestHash = String.valueOf(metrics.getOrDefault("request_hash", ""));
    if (!HASH.matcher(requestHash).matches()) {
      throw new IllegalArgumentException("computation request hash is invalid");
    }
    Path experiments = checkedExistingRun(runId).resolve("experiments");
    Path experiment = experiments.resolve(requestHash).normalize();
    if (!experiment.startsWith(experiments)) {
      throw new IllegalArgumentException("computation path escapes the run");
    }
    Map<String, Object> spec = readObject(experiment.resolve("spec.json"));
    Map<String, Object> artifactDecision = readObject(experiment.resolve("decision.json"));
    Map<String, Object> decision = artifactDecision;
    if (metrics.get("decision") != null) {
      decision = new LinkedHashMap<>();
      decision.put("experiment_id", first(metrics.get("experiment_id"), artifactDecision.get("experiment_id")));
      decision.put("request_hash", requestHash);
      decision.put("decision", metrics.get("decision"));
      decision.put("reason", first(metrics.get("decision_reason"), artifactDecision.get("reason")));
      decision.put("rule_id", first(metrics.get("rule_id"), artifactDecision.get("rule_id")));
      decision.put("cache_hit", first(metrics.get("cache_hit"), artifactDecision.getOrDefault("cache_hit", false)));
      decision.put(
          "contract_repair_status",
          first(metrics.get("contract_repair_status"), artifactDecision.get("contract_repair_status")));
      decision.put(
          "original_request_hash",
          first(metrics.get("original_request_hash"), artifactDecision.get("original_request_hash")));
      decision.put(
          "contract_repair_reason",
          first(metrics.get("contract_repair_reason"), artifactDecision.get("contract_repair_reason")));
    }
    Map<String, Object> program = readObject(experiment.resolve("program.json"));
    Map<String, Object> execution = readObject(experiment.resolve("execution.json"));
    Map<String, Object> result = readObject(experiment.resolve("result.json"));
    Map<String, Object> output = mapValue(execution.get("output"));
    String phase = String.valueOf(first(metrics.get("phase"), activity.getOrDefault("status", "waiting")));
    Map<String, Object> runtime = new LinkedHashMap<>();
    runtime.put("status", activity.get("status"));
    runtime.put("phase", phase);
    runtime.put("runtime_seconds", first(execution.get("runtime_seconds"), metrics.get("runtime_seconds")));
    runtime.put("outcome", first(result.get("outcome"), metrics.get("outcome")));
    runtime.put(
        "evidence_strength", first(result.get("evidence_strength"), metrics.get("evidence_strength")));
    runtime.put("error", first(execution.get("error"), metrics.get("error")));
    runtime.put("tool_name", execution.get("tool_name"));
    runtime.put("tool_version", execution.get("tool_version"));
    runtime.put("process", output.get("process"));
    Map<String, Object> audit = new LinkedHashMap<>();
    audit.put("request_hash", requestHash);
    audit.put("program_hash", first(execution.get("program_hash"), metrics.get("program_hash")));
    audit.put("input_hash", execution.get("input_hash"));
    audit.put("output_hash", execution.get("output_hash"));
    audit.put("environment_hash", execution.get("environment_hash"));
    audit.put("result_hash", first(execution.get("result_hash"), metrics.get("result_hash")));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("task_id", safeTask);
    payload.put("activity", activity);
    payload.put("running", "running".equals(String.valueOf(activity.get("status"))));
    payload.put("phase", phase);
    payload.put("request_hash", requestHash);
    payload.put("experiment_id", first(spec.get("experiment_id"), metrics.get("experiment_id")));
    payload.put("method", first(spec.get("method"), metrics.get("method")));
    payload.put("target_claim", spec.get("target_claim"));
    payload.put("decision", decision);
    payload.put("contract_repair", readObject(experiment.resolve("contract_repair.json")));
    payload.put("program", program);
    payload.put("input", first(execution.get("input"), spec.get("arguments")));
    payload.put("output", execution.get("output"));
    payload.put("runtime", runtime);
    payload.put("environment", execution.get("environment"));
    payload.put("certificate", readObject(experiment.resolve("computation_certificate.json")));
    payload.put("audit", audit);
    return castMap(DesktopApiModel.redact(payload));
  }

  public Path moveToRecycleBin(String runId) {
    Path directory = checkedExistingRun(runId);
    boolean moved = false;
    if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()) {
      try {
        moved = Desktop.getDesktop().moveToTrash(directory.toFile());
      } catch (UnsupportedOperationException | SecurityException ignored) {
        moved = false;
      }
    }
    if (!moved) {
      Path recycle = paths.root().resolve("recycle-bin");
      try {
        Files.createDirectories(recycle);
        Path destination =
            recycle.resolve(runId + "-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID());
        Files.move(directory, destination, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException exception) {
        try {
          Files.move(
              directory,
              paths.root().resolve("recycle-bin").resolve(runId + "-" + UUID.randomUUID()));
        } catch (IOException nested) {
          throw new IllegalStateException("run directory could not be recycled", nested);
        }
      } catch (IOException exception) {
        throw new IllegalStateException("run directory could not be recycled", exception);
      }
    }
    if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("run directory remains after recycle request");
    }
    return directory;
  }

  private Path checkedExistingRun(String runId) {
    Path directory = runDirectory(runId);
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
      throw new IllegalArgumentException("run was not found or is not a safe directory");
    }
    try {
      Path realRoot = paths.runs().toRealPath();
      Path realDirectory = directory.toRealPath();
      if (!realDirectory.startsWith(realRoot)) {
        throw new IllegalArgumentException("run directory escapes the desktop root");
      }
    } catch (IOException exception) {
      throw new IllegalArgumentException("run directory could not be validated", exception);
    }
    return directory;
  }

  private static Path reasoningPath(Path runDirectory) {
    return runDirectory.resolve("reports").resolve(ReasoningTraceStore.FILE_NAME);
  }

  private Path metadataPath(String runId) {
    return runDirectory(runId).resolve("desktop_run.json");
  }

  private String readTitle(Path directory, String fallback) {
    Map<String, Object> problem =
        readObject(directory.resolve("structured").resolve("problem_contract.json"));
    String statement =
        String.valueOf(
            problem.getOrDefault(
                "original_statement", problem.getOrDefault("exact_statement", "")));
    String title =
        statement.lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst().orElse(fallback);
    return title.length() > 90 ? title.substring(0, 89).stripTrailing() + "..." : title;
  }

  private List<Map<String, Object>> readJsonLines(Path path, int maximum) {
    if (!Files.isRegularFile(path)) {
      return List.of();
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
      lines.limit(maximum).forEach(
          line -> {
            try {
              Map<String, Object> value = mapper.readValue(line, MAP_TYPE);
              rows.add(new LinkedHashMap<>(value));
            } catch (IOException ignored) {
              // A partial trailing JSONL record is ignored.
            }
          });
    } catch (IOException exception) {
      return List.of();
    }
    return List.copyOf(rows);
  }

  private Map<String, Object> readObject(Path path) {
    if (!Files.isRegularFile(path)) {
      return Map.of();
    }
    try {
      if (Files.size(path) > 2_000_000) {
        return Map.of();
      }
      return Collections.unmodifiableMap(
          new LinkedHashMap<>(mapper.readValue(Files.readAllBytes(path), MAP_TYPE)));
    } catch (IOException | RuntimeException exception) {
      return Map.of();
    }
  }

  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_IN",
      justification =
          "Metadata paths are fixed children of a validated DesktopPaths.safeRunDirectory.")
  private void writeJsonAtomically(Path path, Map<String, Object> payload) {
    Path directory = Objects.requireNonNull(path.getParent(), "metadata parent directory");
    Path fileName = Objects.requireNonNull(path.getFileName(), "metadata file name");
    try {
      Files.createDirectories(directory);
      Path temporary = Files.createTempFile(directory, "." + fileName, ".tmp");
      try {
        Files.write(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload));
        try {
          Files.move(
              temporary,
              path,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
          Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("desktop metadata could not be saved", exception);
    }
  }

  private static String readBoundedText(Path path, int maximum) {
    if (!Files.isRegularFile(path)) {
      return "";
    }
    try {
      byte[] bytes = Files.readAllBytes(path);
      int length = Math.min(bytes.length, maximum);
      return new String(bytes, 0, length, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      return "";
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapValue(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  private static long number(Object value, long fallback) {
    return value instanceof Number number ? number.longValue() : fallback;
  }

  private static Object first(Object primary, Object fallback) {
    return primary == null ? fallback : primary;
  }

  private static String lifecycle(RunAuthoritySnapshot authority) {
    return switch (authority.executionStatus()) {
      case QUEUED -> "queued";
      case RUNNING -> "running";
      case SUCCEEDED -> "completed";
      case FAILED -> "failed";
      case INTERRUPTED -> "interrupted";
      case CANCELLED -> "cancelled";
    };
  }

  private static String compatibleStatus(RunAuthoritySnapshot authority) {
    if (authority.campaignStatus() == RunCampaignStatus.ACTIVE) {
      return "running";
    }
    return switch (authority.executionStatus()) {
      case FAILED -> "failed";
      case INTERRUPTED -> "interrupted";
      case CANCELLED -> "cancelled";
      case QUEUED -> "queued";
      default ->
          authority.mathStatus()
                  == io.github.aililuola.mathproofmesh.runstate.RunMathematicalStatus.VERIFIED
              ? "completed"
              : "unverified";
    };
  }

  private static final class SetLike {
    private static final java.util.Set<String> ACTIVE =
        java.util.Set.of("queued", "running", "awaiting_confirmation");
  }
}
