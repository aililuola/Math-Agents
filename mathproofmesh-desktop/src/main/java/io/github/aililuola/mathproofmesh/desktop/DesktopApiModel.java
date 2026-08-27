package io.github.aililuola.mathproofmesh.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Shared validation, redaction, and activity projection for the desktop HTTP boundary. */
public final class DesktopApiModel {
  private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,159}");
  private static final Pattern TASK_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,239}");
  private static final Pattern TOKEN =
      Pattern.compile("(?i)(?:Bearer\\s+|sk-)[A-Za-z0-9._~+/=-]{10,}");
  private static final Pattern KEY_VALUE =
      Pattern.compile(
          "(?i)(api[_ -]?key|authorization|password|secret|access[_-]?token)"
              + "(\\s*[:=]\\s*)[^\\s,;\"']+");

  private DesktopApiModel() {}

  public static String safeRunId(String value) {
    String safe = Objects.requireNonNull(value, "runId").trim();
    if (!RUN_ID.matcher(safe).matches() || safe.contains("..")) {
      throw new IllegalArgumentException("run_id has an invalid format");
    }
    return safe;
  }

  public static String safeTaskId(String value) {
    String safe = Objects.requireNonNull(value, "taskId").trim();
    if (!TASK_ID.matcher(safe).matches() || safe.contains("..")) {
      throw new IllegalArgumentException("task_id has an invalid format");
    }
    return safe;
  }

  public static String safeProfile(String value) {
    String safe = value == null ? "smoke" : value.trim().toLowerCase(Locale.ROOT);
    if (!DesktopSettings.PROFILES.contains(safe)) {
      throw new IllegalArgumentException("unsupported desktop profile");
    }
    return safe;
  }

  public static Object redact(Object value) {
    return redact(value, 0);
  }

  private static Object redact(Object value, int depth) {
    if (depth >= 8) {
      return "[TRUNCATED]";
    }
    if (value instanceof String text) {
      String redacted = TOKEN.matcher(text).replaceAll("[REDACTED]");
      redacted = KEY_VALUE.matcher(redacted).replaceAll("$1$2[REDACTED]");
      return redacted.length() > 120_000
          ? redacted.substring(0, 119_986) + "\n[TRUNCATED]"
          : redacted;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      int count = 0;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (count++ >= 300) {
          result.put("_truncated", true);
          break;
        }
        String key = String.valueOf(entry.getKey());
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "_");
        boolean sensitive =
            normalized.matches(
                "api_?key|authorization|password|secret|access_?token|refresh_?token");
        result.put(
            key.length() > 240 ? key.substring(0, 240) : key,
            sensitive ? "[REDACTED]" : redact(entry.getValue(), depth + 1));
      }
      return result;
    }
    if (value instanceof Iterable<?> iterable) {
      List<Object> result = new ArrayList<>();
      int count = 0;
      for (Object item : iterable) {
        if (count++ >= 300) {
          result.add("[TRUNCATED]");
          break;
        }
        result.add(redact(item, depth + 1));
      }
      return result;
    }
    return value;
  }

  public static List<Map<String, Object>> collapseActivity(
      List<Map<String, Object>> events) {
    Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
    List<String> taskOrder = new ArrayList<>();
    Map<String, String> parents = new LinkedHashMap<>();
    Map<String, Long> started = new LinkedHashMap<>();
    Map<String, String> initialTypes = new LinkedHashMap<>();
    for (int index = 0; index < events.size(); index++) {
      Map<String, Object> event = events.get(index);
      String taskId =
          String.valueOf(
              event.getOrDefault(
                  "task_id",
                  "sequence:" + event.getOrDefault("sequence", index) + ":" + index));
      if (!latest.containsKey(taskId)) {
        taskOrder.add(taskId);
        initialTypes.put(
            taskId,
            String.valueOf(
                event.getOrDefault(
                    "initial_event_type", event.getOrDefault("event_type", "activity"))));
        started.put(taskId, nonnegativeLong(event.get("started_elapsed_ms"),
            nonnegativeLong(event.get("elapsed_ms"), 0L)));
        String explicit = optionalString(event.get("parent_task_id"));
        parents.put(
            taskId,
            explicit == null
                ? activeParent(taskId, taskOrder, latest, initialTypes)
                : explicit);
      }
      Map<String, Object> payload = new LinkedHashMap<>(event);
      payload.put("task_id", taskId);
      payload.putIfAbsent("parent_task_id", parents.get(taskId));
      payload.put("started_elapsed_ms", started.get(taskId));
      payload.put("initial_event_type", initialTypes.get(taskId));
      latest.put(taskId, payload);
    }
    return List.copyOf(latest.values());
  }

  private static String activeParent(
      String taskId,
      List<String> order,
      Map<String, Map<String, Object>> latest,
      Map<String, String> initialTypes) {
    for (int index = order.size() - 1; index >= 0; index--) {
      String candidate = order.get(index);
      Map<String, Object> event = latest.get(candidate);
      if (candidate.equals(taskId)
          || event == null
          || !"running".equals(event.get("status"))
          || "agent_call".equals(initialTypes.get(candidate))) {
        continue;
      }
      return candidate;
    }
    return null;
  }

  private static long nonnegativeLong(Object value, long fallback) {
    return value instanceof Number number ? Math.max(0L, number.longValue()) : fallback;
  }

  private static String optionalString(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }
}
