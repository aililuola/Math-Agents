package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.api.ResumeRequest;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceBinding;
import io.github.aililuola.mathproofmesh.api.RunApiModels.ApiEvent;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RunView;
import io.github.aililuola.mathproofmesh.api.RunApiService;
import io.github.aililuola.mathproofmesh.api.SolveRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Desktop lifecycle facade. Work is delegated to the server API service, never to the UI. */
public final class DesktopRunManager implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(DesktopRunManager.class);
  private static final DateTimeFormatter RUN_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private final DesktopPaths paths;
  private final SettingsStore settingsStore;
  private final CredentialVault credentials;
  private final DesktopConfigService configService;
  private final DesktopProviderProbe providerProbe;
  private final RunRepository repository;
  private final RunApiService runs;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ConcurrentMap<String, LiveRun> sessions = new ConcurrentHashMap<>();

  public DesktopRunManager(
      DesktopPaths paths,
      SettingsStore settingsStore,
      CredentialVault credentials,
      DesktopConfigService configService,
      RunRepository repository,
      RunApiService runs) {
    this(paths, settingsStore, credentials, configService, null, repository, runs);
  }

  DesktopRunManager(
      DesktopPaths paths,
      SettingsStore settingsStore,
      CredentialVault credentials,
      DesktopConfigService configService,
      DesktopProviderProbe providerProbe,
      RunRepository repository,
      RunApiService runs) {
    this.paths = Objects.requireNonNull(paths, "paths");
    this.settingsStore = Objects.requireNonNull(settingsStore, "settingsStore");
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.configService = Objects.requireNonNull(configService, "configService");
    this.providerProbe = providerProbe;
    this.repository = Objects.requireNonNull(repository, "repository");
    this.runs = Objects.requireNonNull(runs, "runs");
  }

  public DesktopSettings settings() {
    return settingsStore.load();
  }

  public DesktopSettings updateSettings(DesktopSettings settings) {
    settingsStore.save(settings);
    return settings;
  }

  public Map<String, Object> bootstrap() {
    DesktopSettings settings = settings();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("version", "0.8.0");
    result.put("data_root", paths.root().toString());
    result.put("settings", settingsMap(settings));
    result.put("profiles", configService.profileSummaries(settings));
    result.put("credential_status", credentials.statuses());
    result.put("docker_available", configService.dockerAvailable());
    result.put("runs", repository.listRuns());
    result.put(
        "active_run",
        sessions.values().stream()
            .filter(session -> isActive(session.metadata().lifecycle()))
            .map(LiveRun::snapshot)
            .findFirst()
            .orElse(null));
    return Collections.unmodifiableMap(result);
  }

  public synchronized Map<String, Object> start(StartRunRequest request) {
    ensureIdle();
    configService.build(request.profile(), settings());
    String runId = request.runId() == null ? makeRunId(request.problem()) : request.runId();
    if (Files.exists(paths.safeRunDirectory(runId))) {
      throw new IllegalArgumentException("run_id already exists");
    }
    createProblemFiles(runId, request.problem());
    Instant now = Instant.now();
    DesktopRunMetadata metadata =
        new DesktopRunMetadata(
            runId,
            request.profile(),
            "queued",
            now,
            now,
            "solve",
            null,
            ProcessHandle.current().pid());
    LiveRun session = new LiveRun(metadata, request.problem());
    sessions.put(runId, session);
    repository.writeMetadata(metadata);
    session.publish("state", session.snapshot());
    Future<?> task =
        executor.submit(
            () ->
                executeSolve(
                    session,
                    new SolveRequest(
                        request.problem(), runId, null, request.profile())));
    session.task(task);
    return session.snapshot();
  }

  public synchronized Map<String, Object> resume(String runId, ResumeRunRequest request) {
    ensureIdle();
    String safeId = DesktopApiModel.safeRunId(runId);
    if (!Files.isDirectory(paths.safeRunDirectory(safeId))) {
      throw new IllegalArgumentException("run was not found");
    }
    configService.build(request.profile(), settings());
    DesktopRunMetadata prior = repository.readMetadata(safeId);
    Instant now = Instant.now();
    DesktopRunMetadata metadata =
        new DesktopRunMetadata(
            safeId,
            request.profile(),
            "queued",
            prior == null ? now : prior.createdAt(),
            now,
            "resume",
            null,
            ProcessHandle.current().pid());
    LiveRun session = new LiveRun(metadata, "");
    sessions.put(safeId, session);
    repository.writeMetadata(metadata);
    session.publish(
        "state",
        merge(session.snapshot(), Map.of("resume_mode", request.resumeMode())));
    Future<?> task = executor.submit(() -> executeResume(session, request.resumeMode()));
    session.task(task);
    return session.snapshot();
  }

  public synchronized Map<String, Object> cancel(String runId) {
    LiveRun session = requireSession(runId);
    synchronized (session) {
      if (!isActive(session.metadata().lifecycle())) {
        return session.snapshot();
      }
      Future<?> task = session.task();
      if (task != null && !task.isDone()) {
        task.cancel(true);
      }
      updateLifecycle(session, "cancelled", null);
      repository.reconcileCancellation(session.metadata().runId(), session.metadata());
      session.publish("state", session.snapshot());
      session.publish(
          "terminal", Map.of("run_id", session.metadata().runId(), "reason", "cancelled"));
      return session.snapshot();
    }
  }

  public synchronized Map<String, Object> beginClarification(String runId, String requestId) {
    LiveRun session = requireSession(runId);
    session.pendingClarificationId(requestId);
    updateLifecycle(session, "awaiting_confirmation", null);
    session.publish("state", session.snapshot());
    session.publish(
        "clarification",
        Map.of(
            "request_id", requestId,
            "clarification_question", "Confirm the canonical mathematical statement."));
    return session.snapshot();
  }

  public synchronized Map<String, Object> confirmClarification(
      String runId, ClarificationDecisionRequest request) {
    LiveRun session = requireSession(runId);
    if (!request.requestId().equals(session.pendingClarificationId())) {
      throw new IllegalStateException("there is no matching pending clarification");
    }
    session.pendingClarificationId(null);
    updateLifecycle(session, "running", null);
    session.publish(
        "state",
        merge(
            session.snapshot(),
            Map.of(
                "canonical_statement",
                String.valueOf(DesktopApiModel.redact(request.canonicalStatement())))));
    return session.snapshot();
  }

  public LiveRun session(String runId) {
    return sessions.get(DesktopApiModel.safeRunId(runId));
  }

  public List<LiveRun.DesktopEvent> eventsAfter(String runId, long lastId) {
    LiveRun session = requireSession(runId);
    return session.eventsAfter(Math.max(0L, lastId));
  }

  boolean isRunActive(String runId) {
    LiveRun session = session(runId);
    return session != null && isActive(session.metadata().lifecycle());
  }

  public List<Map<String, Object>> probeCredentials() {
    if (providerProbe != null) {
      return providerProbe.probe();
    }
    List<Map<String, Object>> results = new ArrayList<>();
    Map<String, String> statuses = credentials.statuses();
    for (int index = 1; index <= 5; index++) {
      String key = "DEEPSEEK_AGENT_" + index + "_KEY";
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("agent", "deepseek-agent-" + index);
      result.put("provider", "deepseek");
      result.put("model", "deepseek-v4-pro");
      result.put("reasoning_effort", "max");
      boolean configured = !"missing".equals(statuses.get(key));
      result.put("credential_ok", configured);
      result.put("model_visible", configured);
      results.add(Collections.unmodifiableMap(result));
    }
    return List.copyOf(results);
  }

  public synchronized String delete(String runId) {
    String safe = DesktopApiModel.safeRunId(runId);
    LiveRun session = sessions.get(safe);
    if (session != null) {
      Future<?> task = session.task();
      if (task != null && !task.isDone()) {
        throw new IllegalStateException("an active run cannot be deleted");
      }
    }
    repository.moveToRecycleBin(safe);
    sessions.remove(safe);
    return safe;
  }

  @Override
  public void close() {
    for (LiveRun session : sessions.values()) {
      Future<?> task = session.task();
      if (task != null && !task.isDone()) {
        task.cancel(true);
      }
    }
    executor.close();
  }

  private void executeSolve(LiveRun session, SolveRequest request) {
    synchronized (session) {
      if (isCancelled(session)) {
        return;
      }
      updateLifecycle(session, "running", null);
      session.publish("state", session.snapshot());
    }
    AtomicLong latestPublishedApiEvent = new AtomicLong();
    try {
      RunView result =
          runs.solve(
              request,
              event -> publishActivity(session, event, latestPublishedApiEvent));
      publishResult(session, result, latestPublishedApiEvent.get());
    } catch (RuntimeException exception) {
      publishFailure(session, exception);
    }
  }

  private void executeResume(LiveRun session, String resumeMode) {
    synchronized (session) {
      if (isCancelled(session)) {
        return;
      }
      updateLifecycle(session, "running", null);
      session.publish(
          "state", merge(session.snapshot(), Map.of("resume_mode", resumeMode)));
    }
    AtomicLong latestPublishedApiEvent = new AtomicLong();
    try {
      RunView result;
      try {
        result =
            runs.resume(
                new ResumeRequest(session.metadata().runId(), resumeMode, null, null),
                event -> publishActivity(session, event, latestPublishedApiEvent));
      } catch (RunApiService.ApiNotFoundException notLoaded) {
        result =
            runs.solve(
                new SolveRequest(
                    readProblem(session.metadata().runId()),
                    session.metadata().runId(),
                    null,
                    session.metadata().profile()),
                event -> publishActivity(session, event, latestPublishedApiEvent));
      }
      publishResult(session, result, latestPublishedApiEvent.get());
    } catch (RuntimeException exception) {
      publishFailure(session, exception);
    }
  }

  private void publishResult(LiveRun session, RunView result, long latestPublishedApiEvent) {
    synchronized (session) {
      if (isCancelled(session)) {
        return;
      }
      AtomicLong cursor = new AtomicLong(latestPublishedApiEvent);
      for (ApiEvent event : runs.eventsAfter(result.runId(), latestPublishedApiEvent)) {
        publishActivity(session, event, cursor);
      }
      String lifecycle = desktopLifecycle(result);
      String executionStatus = result.executionStatus().name();
      String error = "FAILED".equals(executionStatus) ? result.summary() : null;
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("run_id", result.runId());
      payload.put("status", result.status());
      payload.put("task_status", result.status());
      payload.put(
          "math_status",
          result.mathStatus()
                  == io.github.aililuola.mathproofmesh.runstate.RunMathematicalStatus.VERIFIED
              ? "verified"
              : "unverified");
      payload.put("canonical_math_status", result.mathStatus().name());
      payload.put(
          "execution_status",
          switch (result.executionStatus()) {
            case FAILED -> "failed";
            case INTERRUPTED -> "interrupted";
            case CANCELLED -> "cancelled";
            case QUEUED -> "queued";
            case RUNNING -> "running";
            case SUCCEEDED -> "completed";
          });
      payload.put("canonical_execution_status", executionStatus);
      payload.put("usage_status", result.usageStatus().name());
      payload.put("campaign_status", result.campaignStatus().name());
      payload.put("report_status", result.reportStatus().name());
      payload.put("reconciliation_status", result.reconciliationStatus().name());
      payload.put("terminal_reason", result.terminalReason().name());
      payload.put("recoverable", result.recoverable());
      payload.put("authority_state_hash", result.authorityStateHash());
      payload.put("state_sequence", result.stateSequence());
      payload.put("current_stage", result.currentStage());
      payload.put("summary", result.summary());
      payload.put("result_reference", result.resultReference());
      payload.put("completed_route_ids", result.completedRouteIds());
      payload.put("verified_local_claim_ids", result.verifiedLocalClaimIds());
      payload.put("total_calls", result.providerCalls());
      payload.put("provider_calls", result.providerCalls());
      payload.put("logical_steps", result.logicalSteps());
      Map<String, Object> usage = new LinkedHashMap<>();
      usage.put("input_tokens", result.totalUsage().inputTokens());
      usage.put("output_tokens", result.totalUsage().outputTokens());
      usage.put("total_tokens", result.totalUsage().totalTokens());
      usage.put("estimated_cost_usd", result.totalUsage().estimatedCostUsd());
      usage.put("latency_ms", result.totalUsage().latencyMs());
      payload.put("total_usage", usage);
      payload.put("total_tokens", result.totalUsage().totalTokens());
      payload.put("estimated_cost_usd", result.totalUsage().estimatedCostUsd());
      repository.writeResult(result.runId(), payload);
      DesktopRunMetadata updated = session.metadata().withLifecycle(lifecycle, error);
      session.metadata(updated);
      repository.writeMetadataProjection(updated, result);
      session.publish("state", session.snapshot());
      session.publish("result", payload);
      session.publish(
          "terminal", Map.of("run_id", result.runId(), "reason", lifecycle));
    }
  }

  private static String desktopLifecycle(RunView result) {
    if (result.campaignStatus()
        == io.github.aililuola.mathproofmesh.runstate.RunCampaignStatus.ACTIVE) {
      return "running";
    }
    return switch (result.executionStatus()) {
      case FAILED -> "failed";
      case INTERRUPTED -> "interrupted";
      case CANCELLED -> "cancelled";
      default -> "completed";
    };
  }

  private void publishActivity(
      LiveRun session, ApiEvent event, AtomicLong latestPublishedApiEvent) {
    synchronized (session) {
      if (isCancelled(session)) {
        return;
      }
      String stage = event.stage() == null ? "run" : event.stage();
      boolean agentCall = event.agentId() != null && !event.agentId().isBlank();
      String taskId =
          agentCall
              ? ReasoningTraceBinding.agentTaskId(stage, event.agentId())
              : "computation".equals(event.type())
                  ? "computation:" + stage
                  : "stage:" + stage;
      Map<String, Object> activity = new LinkedHashMap<>();
      activity.put("sequence", event.eventId());
      activity.put("event_type", event.type());
      activity.put("initial_event_type", liveInitialEventType(event, agentCall));
      activity.put("task_id", taskId);
      activity.put("stage", stage);
      activity.put("status", event.status());
      activity.put("title", event.summary());
      activity.put("detail", event.summary());
      activity.put("elapsed_ms", event.elapsedMs());
      activity.put("started_elapsed_ms", event.elapsedMs());
      activity.put("agent_id", event.agentId());
      activity.put("timestamp", Instant.now().toString());
      activity.put("importance", "heartbeat".equals(event.type()) ? "detail" : "normal");
      activity.put(
          "metrics",
          event.resultReference() == null
              ? Map.of()
              : Map.of("result_reference", event.resultReference()));
      session.publish("activity", activity);
      latestPublishedApiEvent.accumulateAndGet(event.eventId(), Math::max);
    }
  }

  private void publishFailure(LiveRun session, RuntimeException exception) {
    synchronized (session) {
      if (isCancelled(session)) {
        return;
      }
      String type = exception.getClass().getSimpleName();
      LOGGER.error("Desktop run failed: {}", type);
      updateLifecycle(session, "failed", type);
      repository.reconcileFailure(session.metadata().runId(), session.metadata(), type);
      session.publish("state", session.snapshot());
      session.publish("error", Map.of("error_type", type, "message", "desktop run failed"));
      session.publish(
          "terminal", Map.of("run_id", session.metadata().runId(), "reason", "failed"));
    }
  }

  private static boolean isCancelled(LiveRun session) {
    return "cancelled".equals(session.metadata().lifecycle());
  }

  private static String liveInitialEventType(ApiEvent event, boolean agentCall) {
    if (agentCall) {
      return "agent_call";
    }
    if ("run_started".equals(event.type())) {
      return "run";
    }
    return "computation".equals(event.type()) ? "computation_experiment" : "stage";
  }

  private synchronized void ensureIdle() {
    boolean active =
        sessions.values().stream()
            .anyMatch(
                session -> {
                  Future<?> task = session.task();
                  return task != null && !task.isDone();
                });
    if (active) {
      throw new IllegalStateException("another desktop run is active");
    }
  }

  private LiveRun requireSession(String runId) {
    LiveRun session = session(runId);
    if (session == null) {
      throw new IllegalArgumentException("desktop run session was not found");
    }
    return session;
  }

  private void updateLifecycle(LiveRun session, String lifecycle, String error) {
    DesktopRunMetadata updated = session.metadata().withLifecycle(lifecycle, error);
    session.metadata(updated);
    repository.writeMetadata(updated);
  }

  private void createProblemFiles(String runId, String problem) {
    try {
      java.nio.file.Path directory = paths.safeRunDirectory(runId);
      Files.createDirectories(directory.resolve("structured"));
      Files.writeString(directory.resolve("problem.txt"), problem, StandardCharsets.UTF_8);
      String escaped =
          problem
              .replace("\\", "\\\\")
              .replace("\"", "\\\"")
              .replace("\r", "\\r")
              .replace("\n", "\\n");
      Files.writeString(
          directory.resolve("structured").resolve("problem_contract.json"),
          "{\"original_statement\":\"" + escaped + "\"}",
          StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("desktop run directory could not be initialized", exception);
    }
  }

  private String readProblem(String runId) {
    try {
      String problem =
          Files.readString(paths.safeRunDirectory(runId).resolve("problem.txt"), StandardCharsets.UTF_8)
              .trim();
      if (problem.isEmpty()) {
        throw new IllegalStateException("stored problem is empty");
      }
      return problem;
    } catch (IOException exception) {
      throw new IllegalStateException("stored problem could not be read", exception);
    }
  }

  private static String makeRunId(String problem) {
    try {
      String digest =
          java.util.HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(problem.getBytes(StandardCharsets.UTF_8)))
              .substring(0, 6);
      return "desktop-" + RUN_TIME.format(Instant.now()) + "-" + digest;
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static boolean isActive(String lifecycle) {
    return "queued".equals(lifecycle)
        || "running".equals(lifecycle)
        || "awaiting_confirmation".equals(lifecycle);
  }

  private static Map<String, Object> settingsMap(DesktopSettings settings) {
    return Map.of(
        "selected_profile",
        settings.selectedProfile(),
        "sandbox_enabled",
        settings.sandboxEnabled(),
        "remember_credentials",
        settings.rememberCredentials());
  }

  private static Map<String, Object> merge(
      Map<String, Object> first, Map<String, Object> second) {
    Map<String, Object> result = new LinkedHashMap<>(first);
    result.putAll(second);
    return Collections.unmodifiableMap(result);
  }
}
