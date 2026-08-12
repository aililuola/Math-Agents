package io.github.aililuola.mathproofmesh.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One provider call stored as one final reasoning paragraph with a live preview. */
public final class ReasoningTraceCall {
  private static final int PREVIEW_CHARACTERS = 16 * 1024;
  private static final long PREVIEW_INTERVAL_NANOS = 500_000_000L;

  private final ReasoningTraceStore store;
  private final String taskId;
  private final String callId = "reasoning_" + UUID.randomUUID().toString().replace("-", "");
  private final int callIndex;
  private final StringBuilder pending = new StringBuilder();
  private final StringBuilder storedText = new StringBuilder();
  private final MessageDigest sourceHash = sha256();
  private final MessageDigest storedHash = sha256();
  private long lastFlushNanos = System.nanoTime();
  private int sequence;
  private long sourceCharacters;
  private long storedCharacters;
  private long previewRevision;
  private boolean redacted;
  private boolean started;
  private boolean finished;

  ReasoningTraceCall(ReasoningTraceStore store, String taskId, int callIndex) {
    this.store = store;
    this.taskId = taskId;
    this.callIndex = callIndex;
  }

  synchronized void start(
      String agentId, String stage, boolean thinkingEnabled, String reasoningEffort) {
    if (started) {
      return;
    }
    started = true;
    Map<String, Object> record = base("start");
    record.put("sequence", 0);
    record.put("format_version", ReasoningTraceStore.FORMAT_VERSION);
    record.put("agent_id", ActivitySanitizer.identifier(agentId, 160));
    record.put("stage", ActivitySanitizer.identifier(stage, 160));
    record.put("thinking_enabled", thinkingEnabled);
    record.put(
        "reasoning_effort",
        reasoningEffort == null ? null : ActivitySanitizer.text(reasoningEffort, 80));
    store.appendRecord(record);
  }

  public synchronized void append(String value) {
    if (finished || value == null || value.isEmpty()) {
      return;
    }
    byte[] source = value.getBytes(StandardCharsets.UTF_8);
    sourceHash.update(source);
    sourceCharacters += value.length();
    pending.append(value);
    if (pending.length() >= PREVIEW_CHARACTERS
        || System.nanoTime() - lastFlushNanos >= PREVIEW_INTERVAL_NANOS) {
      flush(false);
    }
  }

  public synchronized void flushDue() {
    if (!finished
        && !pending.isEmpty()
        && System.nanoTime() - lastFlushNanos >= PREVIEW_INTERVAL_NANOS) {
      flush(false);
    }
  }

  public synchronized void finish(Status status, String errorType) {
    if (finished) {
      return;
    }
    flush(true);
    if (!storedText.isEmpty()) {
      Map<String, Object> paragraph = base("paragraph");
      paragraph.put("sequence", ++sequence);
      paragraph.put("text", storedText.toString());
      store.appendRecord(paragraph);
    }
    ReasoningTraceStore.Redaction safeError =
        store.redact(errorType == null ? "" : errorType.substring(0, Math.min(160, errorType.length())));
    redacted |= safeError.redacted();
    Map<String, Object> record = base("end");
    record.put("sequence", ++sequence);
    record.put("status", status.wireName);
    record.put("error_type", safeError.value().isBlank() ? null : safeError.value());
    record.put("source_characters", sourceCharacters);
    record.put("characters", storedCharacters);
    record.put("source_sha256", hex(sourceHash.digest()));
    record.put("sha256", hex(storedHash.digest()));
    record.put("redacted", redacted);
    store.appendRecord(record);
    store.deletePreview(callId);
    finished = true;
  }

  public void finish(Status status) {
    finish(status, null);
  }

  private void flush(boolean force) {
    if (pending.isEmpty()) {
      lastFlushNanos = System.nanoTime();
      return;
    }
    int cutoff = force ? pending.length() : store.flushCutoff(pending.toString());
    if (cutoff <= 0) {
      return;
    }
    String raw = pending.substring(0, cutoff);
    pending.delete(0, cutoff);
    ReasoningTraceStore.Redaction safe = store.redact(raw);
    redacted |= safe.redacted();
    if (!safe.value().isEmpty()) {
      byte[] stored = safe.value().getBytes(StandardCharsets.UTF_8);
      storedHash.update(stored);
      storedCharacters += safe.value().length();
      storedText.append(safe.value());
      Map<String, Object> preview = base("preview");
      preview.put("revision", ++previewRevision);
      preview.put("characters", storedCharacters);
      preview.put("text", storedText.toString());
      store.writePreview(preview);
    }
    lastFlushNanos = System.nanoTime();
  }

  private Map<String, Object> base(String type) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("timestamp", Instant.now().toString());
    result.put("run_id", store.runId());
    result.put("task_id", taskId);
    result.put("call_id", callId);
    result.put("call_index", callIndex);
    result.put("type", type);
    return result;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String hex(byte[] bytes) {
    return java.util.HexFormat.of().formatHex(bytes);
  }

  public enum Status {
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String wireName;

    Status(String wireName) {
      this.wireName = wireName;
    }
  }
}
