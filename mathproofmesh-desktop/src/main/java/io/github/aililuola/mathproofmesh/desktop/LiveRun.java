package io.github.aililuola.mathproofmesh.desktop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory desktop session projection. Durable run state remains in server/database stores. */
public final class LiveRun {
  private final String problem;
  private final List<DesktopEvent> events = new ArrayList<>();
  private final AtomicLong sequence = new AtomicLong();
  private DesktopRunMetadata metadata;
  private Future<?> task;
  private String pendingClarificationId;

  public LiveRun(DesktopRunMetadata metadata, String problem) {
    this.metadata = Objects.requireNonNull(metadata, "metadata");
    this.problem = problem == null ? "" : problem;
  }

  public synchronized DesktopRunMetadata metadata() {
    return metadata;
  }

  public synchronized void metadata(DesktopRunMetadata next) {
    metadata = Objects.requireNonNull(next, "next");
  }

  public synchronized Future<?> task() {
    return task;
  }

  public synchronized void task(Future<?> next) {
    task = next;
  }

  public synchronized DesktopEvent publish(String event, Map<String, Object> data) {
    DesktopEvent item =
        new DesktopEvent(sequence.incrementAndGet(), event, new LinkedHashMap<>(data));
    events.add(item);
    if (events.size() > 1500) {
      events.remove(0);
    }
    return item;
  }

  public synchronized List<DesktopEvent> eventsAfter(long lastId) {
    return events.stream().filter(event -> event.id() > lastId).toList();
  }

  public synchronized Map<String, Object> snapshot() {
    String title =
        problem.lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst()
            .orElse(metadata.runId());
    if (title.length() > 90) {
      title = title.substring(0, 89).stripTrailing() + "...";
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("run_id", metadata.runId());
    result.put("title", title);
    result.put("profile", metadata.profile());
    result.put("lifecycle", metadata.lifecycle());
    result.put("mode", metadata.mode());
    result.put("created_at", metadata.createdAt().toString());
    result.put("updated_at", metadata.updatedAt().toString());
    result.put("error", metadata.error());
    result.put("clarification_pending", pendingClarificationId != null);
    return Collections.unmodifiableMap(result);
  }

  public synchronized String pendingClarificationId() {
    return pendingClarificationId;
  }

  public synchronized void pendingClarificationId(String value) {
    pendingClarificationId = value;
  }

  public record DesktopEvent(long id, String event, Map<String, Object> data) {
    public DesktopEvent {
      if (id < 1 || event == null || event.isBlank()) {
        throw new IllegalArgumentException("invalid desktop event");
      }
      data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
  }
}
