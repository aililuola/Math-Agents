package io.github.aililuola.mathproofmesh.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Append-only, restart-aware activity timeline with bounded redacted payloads. */
public final class ActivityStream {
  private final Path runDirectory;
  private final Path logPath;
  private final boolean persist;
  private final String language;
  private final long startedNanos;
  private final List<ActivityEvent> events = new ArrayList<>();
  private Consumer<ActivityEvent> listener;
  private volatile long sequence;

  public ActivityStream(Path runDirectory) {
    this(runDirectory, "en", true, ignored -> {});
  }

  public ActivityStream(
      Path runDirectory,
      String language,
      boolean persist,
      Consumer<ActivityEvent> listener) {
    this.runDirectory = Objects.requireNonNull(runDirectory, "runDirectory").toAbsolutePath().normalize();
    this.logPath = this.runDirectory.resolve("activity.jsonl");
    this.persist = persist;
    this.language = language == null ? "en" : language;
    this.listener = listener == null ? ignored -> {} : listener;
    long priorElapsed = loadExistingEvents();
    this.startedNanos = System.nanoTime() - priorElapsed * 1_000_000L;
  }

  public synchronized void setListener(Consumer<ActivityEvent> listener) {
    this.listener = listener == null ? ignored -> {} : listener;
  }

  public synchronized List<ActivityEvent> events() {
    return List.copyOf(events);
  }

  public synchronized String startTask(
      String eventType,
      String title,
      String detail,
      String stage,
      ActivityImportance importance,
      Map<String, ?> metrics,
      String parentTaskId,
      String agentId) {
    String taskId = "activity_" + UUID.randomUUID().toString().replace("-", "");
    long elapsed = elapsedMillis();
    emit(
        eventType,
        ActivityStatus.RUNNING,
        importance,
        stage,
        taskId,
        parentTaskId,
        elapsed,
        eventType,
        title,
        detail,
        agentId,
        null,
        metrics);
    return taskId;
  }

  public synchronized ActivityEvent updateTask(
      String taskId,
      String title,
      String detail,
      ActivityStatus status,
      String stage,
      ActivityImportance importance,
      Map<String, ?> metrics,
      String agentId) {
    return emit(
        "task_updated",
        status == null ? ActivityStatus.RUNNING : status,
        importance,
        stage,
        taskId,
        null,
        null,
        null,
        title,
        detail,
        agentId,
        null,
        metrics);
  }

  public synchronized ActivityEvent completeTask(
      String taskId, String title, String detail, String stage, String agentId) {
    return emit(
        "task_completed",
        ActivityStatus.COMPLETED,
        ActivityImportance.NORMAL,
        stage,
        taskId,
        null,
        null,
        null,
        title,
        detail,
        agentId,
        1.0,
        Map.of());
  }

  public synchronized ActivityEvent heartbeat(String taskId, String stage, String agentId) {
    return emit(
        "agent_call_heartbeat",
        ActivityStatus.RUNNING,
        ActivityImportance.DETAIL,
        stage,
        taskId,
        null,
        null,
        null,
        "Agent call is still active",
        "",
        agentId,
        null,
        Map.of());
  }

  public synchronized ActivityEvent info(String eventType, String title) {
    return emit(
        eventType,
        ActivityStatus.INFO,
        ActivityImportance.NORMAL,
        null,
        "activity_" + UUID.randomUUID().toString().replace("-", ""),
        null,
        elapsedMillis(),
        eventType,
        title,
        "",
        null,
        null,
        Map.of());
  }

  public synchronized ActivityEvent emit(
      String eventType,
      ActivityStatus status,
      ActivityImportance importance,
      String stage,
      String taskId,
      String parentTaskId,
      Long startedElapsedMs,
      String initialEventType,
      String title,
      String detail,
      String agentId,
      Double progress,
      Map<String, ?> metrics) {
    ActivityEvent prior = latest(taskId);
    String inferredParent =
        parentTaskId != null
            ? parentTaskId
            : prior != null ? prior.parentTaskId() : inferParentTaskId(taskId);
    Long retainedStart =
        startedElapsedMs != null
            ? startedElapsedMs
            : prior != null ? prior.startedElapsedMs() : elapsedMillis();
    String retainedInitial =
        initialEventType != null
            ? initialEventType
            : prior != null ? prior.initialEventType() : eventType;
    ActivityEvent event =
        new ActivityEvent(
            ++sequence,
            Instant.now(),
            elapsedMillis(),
            eventType,
            status,
            importance,
            stage,
            taskId,
            inferredParent,
            retainedStart,
            retainedInitial,
            title,
            detail,
            agentId,
            progress,
            ActivitySanitizer.metrics(metrics));
    events.add(event);
    if (persist) {
      append(event);
    }
    listener.accept(event);
    return event;
  }

  public synchronized ReportReferences finalizeTimeline() {
    if (!persist) {
      return new ReportReferences(null, null);
    }
    Path reports = runDirectory.resolve("reports");
    Path json = reports.resolve("activity_timeline.json");
    Path markdown = reports.resolve("activity_timeline.md");
    try {
      Files.createDirectories(reports);
      List<Map<String, Object>> payload = collapse(events).stream().map(ActivityStream::wire).toList();
      writeAtomically(json, ContractObjectMapper.write(payload) + "\n");
      writeAtomically(markdown, markdown(payload));
    } catch (IOException exception) {
      throw new IllegalStateException("activity timeline could not be finalized", exception);
    }
    return new ReportReferences(
        "artifact://reports/activity_timeline.json",
        "artifact://reports/activity_timeline.md");
  }

  public static List<ActivityEvent> collapse(List<ActivityEvent> source) {
    Map<String, ActivityEvent> latest = new LinkedHashMap<>();
    for (ActivityEvent event : source) {
      ActivityEvent prior = latest.get(event.taskId());
      if (prior != null) {
        event =
            event.withTopology(
                prior.parentTaskId(),
                prior.startedElapsedMs(),
                prior.initialEventType() == null ? prior.eventType() : prior.initialEventType());
      }
      latest.put(event.taskId(), event);
    }
    return List.copyOf(latest.values());
  }

  public static String redactText(String value, int limit) {
    return ActivitySanitizer.text(value, limit);
  }

  public static String stageLabel(String stage, String language) {
    String normalized = ActivitySanitizer.text(stage, 120).replaceAll("_json_repair_\\d+$", "");
    Map<String, String> labels =
        Map.ofEntries(
            Map.entry("goal_preflight", "Preflight and freeze the mathematical goal"),
            Map.entry("triage", "Analyze the problem type and main risks"),
            Map.entry("strategy_generation", "Generate independent proof strategies"),
            Map.entry("independent_exploration", "Explore the assigned route independently"),
            Map.entry("structural_verification", "Check structure and theorem integrity"),
            Map.entry("detailed_verification", "Audit the key derivation step by step"),
            Map.entry("synthesis", "Synthesize supported routes into a final proof"),
            Map.entry("final_verification", "Audit the final proof step by step"),
            Map.entry("proof_continuation", "Continue from a verified proof checkpoint"),
            Map.entry("run_resume", "Resume an interrupted multi-agent run"),
            Map.entry("message_broker", "Route typed mathematical messages"),
            Map.entry("route_team", "Run route-local collaboration and independent review"),
            Map.entry("proof_graph", "Update the proof-obligation graph"),
            Map.entry("inspiration", "Run bounded inspiration mechanisms"));
    String label = labels.get(normalized);
    if (label != null) {
      return label;
    }
    String fallback = normalized.replace('_', ' ').strip();
    return fallback.isEmpty()
        ? ""
        : Character.toUpperCase(fallback.charAt(0)) + fallback.substring(1);
  }

  public static String formatElapsed(long totalSeconds) {
    long total = Math.max(0, totalSeconds);
    long hours = total / 3600;
    long minutes = total % 3600 / 60;
    long seconds = total % 60;
    return hours > 0
        ? "%d:%02d:%02d".formatted(hours, minutes, seconds)
        : "%02d:%02d".formatted(minutes, seconds);
  }

  private long loadExistingEvents() {
    if (!persist || !Files.isRegularFile(logPath)) {
      return 0;
    }
    long latestElapsed = 0;
    try {
      for (String line : Files.readAllLines(logPath, StandardCharsets.UTF_8)) {
        if (line.isBlank()) {
          continue;
        }
        ActivityEvent event = parse(line);
        if (event != null) {
          events.add(event);
          sequence = Math.max(sequence, event.sequence());
          latestElapsed = Math.max(latestElapsed, event.elapsedMs());
        }
      }
      return latestElapsed;
    } catch (IOException exception) {
      throw new IllegalStateException("existing activity timeline could not be read", exception);
    }
  }

  private void append(ActivityEvent event) {
    try {
      Files.createDirectories(runDirectory);
      Files.writeString(
          logPath,
          ContractObjectMapper.write(wire(event)) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException exception) {
      throw new IllegalStateException("activity event could not be persisted", exception);
    }
  }

  private static ActivityEvent parse(String line) {
    try {
      JsonNode node = ContractObjectMapper.parseTree(line);
      Map<String, Object> metrics = new LinkedHashMap<>();
      node.path("metrics").properties().forEach(entry -> metrics.put(entry.getKey(), scalar(entry.getValue())));
      return new ActivityEvent(
          node.path("sequence").asLong(),
          Instant.parse(node.path("timestamp").asText()),
          node.path("elapsed_ms").asLong(),
          node.path("event_type").asText(),
          ActivityStatus.valueOf(node.path("status").asText().toUpperCase(Locale.ROOT)),
          ActivityImportance.valueOf(node.path("importance").asText().toUpperCase(Locale.ROOT)),
          nullable(node, "stage"),
          node.path("task_id").asText(),
          nullable(node, "parent_task_id"),
          node.path("started_elapsed_ms").isNumber()
              ? node.path("started_elapsed_ms").asLong()
              : null,
          nullable(node, "initial_event_type"),
          node.path("title").asText(),
          node.path("detail").asText(),
          nullable(node, "agent_id"),
          node.path("progress").isNumber() ? node.path("progress").asDouble() : null,
          metrics);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static Object scalar(JsonNode node) {
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    if (node.isIntegralNumber()) {
      return node.asLong();
    }
    if (node.isFloatingPointNumber()) {
      return node.asDouble();
    }
    if (node.isNull()) {
      return null;
    }
    return ActivitySanitizer.text(node.toString(), 400);
  }

  private static String nullable(JsonNode node, String name) {
    JsonNode value = node.get(name);
    return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
  }

  private ActivityEvent latest(String taskId) {
    for (int index = events.size() - 1; index >= 0; index--) {
      ActivityEvent candidate = events.get(index);
      if (candidate.taskId().equals(taskId)) {
        return candidate;
      }
    }
    return null;
  }

  private String inferParentTaskId(String taskId) {
    for (int index = events.size() - 1; index >= 0; index--) {
      ActivityEvent candidate = events.get(index);
      if (!candidate.taskId().equals(taskId)
          && candidate.status() == ActivityStatus.RUNNING
          && ("stage".equals(candidate.initialEventType())
              || "run".equals(candidate.initialEventType()))) {
        return candidate.taskId();
      }
    }
    return null;
  }

  private long elapsedMillis() {
    return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
  }

  private static Map<String, Object> wire(ActivityEvent event) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sequence", event.sequence());
    result.put("timestamp", event.timestamp().toString());
    result.put("elapsed_ms", event.elapsedMs());
    result.put("event_type", event.eventType());
    result.put("status", event.status().wireName());
    result.put("importance", event.importance().wireName());
    result.put("stage", event.stage());
    result.put("task_id", event.taskId());
    result.put("parent_task_id", event.parentTaskId());
    result.put("started_elapsed_ms", event.startedElapsedMs());
    result.put("initial_event_type", event.initialEventType());
    result.put("title", event.title());
    result.put("detail", event.detail());
    result.put("agent_id", event.agentId());
    result.put("progress", event.progress());
    result.put("metrics", event.metrics());
    return result;
  }

  private String markdown(List<Map<String, Object>> payload) {
    StringBuilder result =
        new StringBuilder()
            .append("# Activity Timeline\n\n")
            .append("This timeline contains concise operational progress only. ")
            .append("It never contains prompts or private model reasoning.\n\n");
    for (Map<String, Object> item : payload) {
      result
          .append("- `")
          .append(item.get("sequence"))
          .append("` **")
          .append(item.get("status"))
          .append("** ")
          .append(item.get("title"));
      Object detail = item.get("detail");
      if (detail != null && !String.valueOf(detail).isBlank()) {
        result.append(": ").append(detail);
      }
      Object stage = item.get("stage");
      if (stage != null && !String.valueOf(stage).isBlank()) {
        result.append(" [").append(stageLabel(String.valueOf(stage), language)).append(']');
      }
      result.append('\n');
    }
    return result.toString();
  }

  private static void writeAtomically(Path target, String content) throws IOException {
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
    Files.writeString(
        temporary,
        content,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
    try {
      Files.move(
          temporary,
          target,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public record ReportReferences(String jsonReference, String markdownReference) {}
}
