package io.github.aililuola.mathproofmesh.desktop;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceStore;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Desktop-only loopback API consumed by the copied workbench. */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "mathproofmesh.desktop.enabled", havingValue = "true")
@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP2", "SPRING_ENDPOINT"},
    justification =
        "Spring owns the injected singleton services; every endpoint is behind the ordered"
            + " loopback session filter and validates run/path tokens at the repository boundary.")
public final class DesktopApiController {
  private static final long STREAM_POLL_MILLIS = 250L;
  private static final long STREAM_HEARTBEAT_MILLIS = 5_000L;

  private final DesktopPaths paths;
  private final CredentialVault credentials;
  private final DesktopConfigService configs;
  private final RunRepository repository;
  private final DesktopRunManager manager;

  public DesktopApiController(
      DesktopPaths paths,
      CredentialVault credentials,
      DesktopConfigService configs,
      RunRepository repository,
      DesktopRunManager manager) {
    this.paths = paths;
    this.credentials = credentials;
    this.configs = configs;
    this.repository = repository;
    this.manager = manager;
  }

  @GetMapping("/bootstrap")
  Map<String, Object> bootstrap() {
    return manager.bootstrap();
  }

  @PutMapping("/settings")
  Map<String, Object> updateSettings(@RequestBody DesktopSettings settings) {
    DesktopSettings saved = manager.updateSettings(settings);
    return Map.of(
        "settings",
        settingsMap(saved),
        "profiles",
        configs.profileSummaries(saved));
  }

  @PutMapping("/credentials")
  Map<String, Object> updateCredentials(@RequestBody CredentialsRequest request) {
    for (String name : request.clear()) {
      credentials.clear(name);
    }
    for (Map.Entry<String, String> entry : request.values().entrySet()) {
      if (entry.getValue() != null && !entry.getValue().isBlank()) {
        credentials.set(entry.getKey(), entry.getValue(), request.persist());
      }
    }
    return Map.of("credential_status", credentials.statuses());
  }

  @DeleteMapping("/credentials")
  Map<String, Object> clearCredentials() {
    credentials.clearAll();
    return Map.of("credential_status", credentials.statuses());
  }

  @PostMapping("/probe")
  Map<String, Object> probe() {
    return Map.of("results", manager.probeCredentials());
  }

  @GetMapping("/runs")
  Map<String, Object> runs() {
    return Map.of("runs", repository.listRuns());
  }

  @PostMapping("/runs")
  Map<String, Object> start(@RequestBody StartRunRequest request) {
    return Map.of("run", manager.start(request));
  }

  @GetMapping("/runs/{runId}")
  Map<String, Object> detail(@PathVariable String runId) {
    return repository.detail(runId);
  }

  @GetMapping("/runs/{runId}/nodes/{taskId}/reasoning")
  Map<String, Object> reasoning(@PathVariable String runId, @PathVariable String taskId) {
    return repository.reasoningSnapshot(runId, taskId);
  }

  @GetMapping("/runs/{runId}/nodes/{taskId}/computation")
  Map<String, Object> computation(@PathVariable String runId, @PathVariable String taskId) {
    return repository.computationSnapshot(runId, taskId);
  }

  @GetMapping(
      value = "/runs/{runId}/nodes/{taskId}/reasoning/events",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter reasoningEvents(
      @PathVariable String runId,
      @PathVariable String taskId,
      @RequestParam(defaultValue = "0") long after,
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
    repository.reasoningSnapshot(runId, taskId);
    long cursor = Math.max(Math.max(0L, after), parseCursor(lastEventId));
    SseEmitter emitter = new SseEmitter(0L);
    AtomicBoolean closed = track(emitter);
    Thread.ofVirtual()
        .name("desktop-reasoning-stream")
        .start(() -> streamReasoningEvents(emitter, closed, runId, taskId, cursor));
    return emitter;
  }

  @DeleteMapping("/runs/{runId}")
  Map<String, Object> delete(@PathVariable String runId) {
    String deleted = manager.delete(runId);
    return Map.of("deleted_run_id", deleted, "runs", repository.listRuns());
  }

  @PostMapping("/runs/{runId}/resume")
  Map<String, Object> resume(
      @PathVariable String runId, @RequestBody ResumeRunRequest request) {
    return Map.of("run", manager.resume(runId, request));
  }

  @PostMapping("/runs/{runId}/clarification")
  Map<String, Object> clarification(
      @PathVariable String runId, @RequestBody ClarificationDecisionRequest request) {
    return Map.of("run", manager.confirmClarification(runId, request));
  }

  @PostMapping("/runs/{runId}/cancel")
  Map<String, Object> cancel(@PathVariable String runId) {
    return Map.of("run", manager.cancel(runId));
  }

  @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter events(
      @PathVariable String runId,
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
    long cursor = parseCursor(lastEventId);
    manager.eventsAfter(runId, cursor);
    SseEmitter emitter = new SseEmitter(0L);
    AtomicBoolean closed = track(emitter);
    Thread.ofVirtual()
        .name("desktop-progress-stream")
        .start(() -> streamRunEvents(emitter, closed, runId, cursor));
    return emitter;
  }

  @PostMapping("/open-path")
  Map<String, Object> openPath(@RequestBody OpenPathRequest request) {
    Path selected =
        switch (request.kind()) {
          case "data" -> paths.root();
          case "runs" -> paths.runs();
          case "logs" -> paths.logs();
          case "run" -> paths.safeRunDirectory(request.runId());
          default -> throw new IllegalArgumentException("unsupported path kind");
        };
    if (!selected.startsWith(paths.root())) {
      throw new IllegalArgumentException("path escapes the desktop root");
    }
    try {
      Files.createDirectories(selected);
      if (!GraphicsEnvironment.isHeadless()
          && Desktop.isDesktopSupported()
          && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        Desktop.getDesktop().open(selected.toFile());
      }
    } catch (IOException exception) {
      throw new IllegalStateException("desktop path could not be opened", exception);
    }
    return Map.of("opened", selected.toString());
  }

  private void streamRunEvents(
      SseEmitter emitter, AtomicBoolean closed, String runId, long initialCursor) {
    long cursor = initialCursor;
    long lastHeartbeat = System.nanoTime();
    if (!sendEvent(emitter, "connected", null, Map.of("run_id", runId, "cursor", cursor))) {
      return;
    }
    while (!closed.get()) {
      List<LiveRun.DesktopEvent> updates = manager.eventsAfter(runId, cursor);
      for (LiveRun.DesktopEvent item : updates) {
        if (!sendEvent(emitter, item.event(), item.id(), item.data())) {
          return;
        }
        cursor = item.id();
        if ("terminal".equals(item.event())) {
          emitter.complete();
          return;
        }
      }
      if (updates.isEmpty() && !manager.isRunActive(runId)) {
        sendEvent(emitter, "terminal", null, Map.of("run_id", runId, "cursor", cursor));
        emitter.complete();
        return;
      }
      long now = System.nanoTime();
      if (updates.isEmpty()
          && now - lastHeartbeat >= STREAM_HEARTBEAT_MILLIS * 1_000_000L) {
        if (!sendEvent(emitter, "heartbeat", null, Map.of("run_id", runId, "cursor", cursor))) {
          return;
        }
        lastHeartbeat = now;
      }
      if (!pauseStream()) {
        emitter.complete();
        return;
      }
    }
  }

  private void streamReasoningEvents(
      SseEmitter emitter,
      AtomicBoolean closed,
      String runId,
      String taskId,
      long initialCursor) {
    long cursor = initialCursor;
    long lastHeartbeat = System.nanoTime();
    Map<String, String> previewRevisions = new LinkedHashMap<>();
    if (!sendEvent(
        emitter,
        "connected",
        null,
        Map.of("run_id", runId, "task_id", taskId, "cursor", cursor))) {
      return;
    }
    while (!closed.get()) {
      ReasoningTraceStore.ReadResult updates =
          repository.readReasoningUpdates(runId, taskId, cursor);
      cursor = updates.nextOffset();
      List<Map<String, Object>> records = updates.records();
      for (int index = 0; index < records.size(); index++) {
        Long eventId = index == records.size() - 1 ? cursor : null;
        if (!sendEvent(
            emitter,
            "reasoning",
            eventId,
            Map.of("record", records.get(index), "cursor", cursor))) {
          return;
        }
      }
      boolean previewSent = false;
      for (Map<String, Object> preview : repository.readReasoningPreviews(runId, taskId)) {
        String callId = String.valueOf(preview.getOrDefault("call_id", ""));
        String revision = String.valueOf(preview.getOrDefault("revision", ""));
        if (callId.isBlank() || revision.equals(previewRevisions.get(callId))) {
          continue;
        }
        previewRevisions.put(callId, revision);
        if (!sendEvent(
            emitter,
            "reasoning",
            null,
            Map.of("record", preview, "cursor", cursor))) {
          return;
        }
        previewSent = true;
      }
      if (!manager.isRunActive(runId)) {
        sendEvent(
            emitter,
            "terminal",
            null,
            Map.of("run_id", runId, "task_id", taskId, "cursor", cursor));
        emitter.complete();
        return;
      }
      long now = System.nanoTime();
      if (records.isEmpty()
          && !previewSent
          && now - lastHeartbeat >= STREAM_HEARTBEAT_MILLIS * 1_000_000L) {
        if (!sendEvent(
            emitter,
            "heartbeat",
            cursor,
            Map.of("run_id", runId, "task_id", taskId, "cursor", cursor))) {
          return;
        }
        lastHeartbeat = now;
      }
      if (!pauseStream()) {
        emitter.complete();
        return;
      }
    }
  }

  private static AtomicBoolean track(SseEmitter emitter) {
    AtomicBoolean closed = new AtomicBoolean();
    emitter.onCompletion(() -> closed.set(true));
    emitter.onTimeout(() -> closed.set(true));
    emitter.onError(ignored -> closed.set(true));
    return closed;
  }

  private static boolean sendEvent(
      SseEmitter emitter, String type, Long id, Map<String, ?> data) {
    SseEmitter.SseEventBuilder event =
        SseEmitter.event()
            .name(type)
            .reconnectTime(1_000L)
            .data(DesktopApiModel.redact(data), MediaType.APPLICATION_JSON);
    if (id != null) {
      event.id(Long.toString(id));
    }
    try {
      emitter.send(event);
      return true;
    } catch (IOException | IllegalStateException exception) {
      return false;
    }
  }

  private static boolean pauseStream() {
    try {
      Thread.sleep(STREAM_POLL_MILLIS);
      return true;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static long parseCursor(String value) {
    if (value == null || value.isBlank()) {
      return 0L;
    }
    try {
      return Math.max(0L, Long.parseLong(value.trim()));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Last-Event-ID must be nonnegative", exception);
    }
  }

  private static Map<String, Object> settingsMap(DesktopSettings settings) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("selected_profile", settings.selectedProfile());
    result.put("sandbox_enabled", settings.sandboxEnabled());
    result.put("remember_credentials", settings.rememberCredentials());
    return result;
  }
}
