package io.github.aililuola.mathproofmesh.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Concise user-visible progress. It must never contain prompts or private reasoning. */
public record ActivityEvent(
    long sequence,
    Instant timestamp,
    long elapsedMs,
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
    Map<String, Object> metrics) {

  public ActivityEvent {
    if (sequence < 1 || elapsedMs < 0) {
      throw new IllegalArgumentException("activity sequence and elapsed time must be nonnegative");
    }
    timestamp = Objects.requireNonNull(timestamp, "timestamp");
    eventType = ActivitySanitizer.identifier(eventType, 120);
    status = Objects.requireNonNull(status, "status");
    importance = importance == null ? ActivityImportance.NORMAL : importance;
    stage = ActivitySanitizer.nullableIdentifier(stage, 120);
    taskId = ActivitySanitizer.identifier(taskId, 160);
    parentTaskId = ActivitySanitizer.nullableIdentifier(parentTaskId, 160);
    initialEventType = ActivitySanitizer.nullableIdentifier(initialEventType, 120);
    title = ActivitySanitizer.text(title, 400);
    detail = ActivitySanitizer.text(detail, 800);
    agentId = ActivitySanitizer.nullableIdentifier(agentId, 160);
    if (progress != null && (progress < 0.0 || progress > 1.0 || !Double.isFinite(progress))) {
      throw new IllegalArgumentException("activity progress must be in [0,1]");
    }
    metrics = ActivitySanitizer.metrics(metrics);
  }

  @Override
  public Map<String, Object> metrics() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
  }

  public ActivityEvent withTopology(
      String retainedParent, Long retainedStart, String retainedInitialType) {
    return new ActivityEvent(
        sequence,
        timestamp,
        elapsedMs,
        eventType,
        status,
        importance,
        stage,
        taskId,
        parentTaskId == null ? retainedParent : parentTaskId,
        startedElapsedMs == null ? retainedStart : startedElapsedMs,
        initialEventType == null ? retainedInitialType : initialEventType,
        title,
        detail,
        agentId,
        progress,
        metrics);
  }
}
