package io.github.aililuola.mathproofmesh.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Compact run archive plus replace-in-place previews for provider-emitted reasoning. */
public final class ReasoningTraceStore {
  public static final String FILE_NAME = "reasoning_traces.txt";
  public static final String LIVE_DIRECTORY_NAME = "reasoning_live";
  static final int FORMAT_VERSION = 3;
  private static final int READ_BUFFER_BYTES = 64 * 1024;

  private final String runId;
  private final Path path;
  private final List<String> secrets;
  private final Map<String, Integer> callCounts = new LinkedHashMap<>();

  public ReasoningTraceStore(Path runDirectory, String runId, Collection<String> secrets) {
    Objects.requireNonNull(runDirectory, "runDirectory");
    this.runId = ActivitySanitizer.identifier(runId, 160);
    this.path = runDirectory.toAbsolutePath().normalize().resolve("reports").resolve(FILE_NAME);
    this.secrets =
        (secrets == null ? List.<String>of() : secrets.stream().filter(Objects::nonNull).toList())
            .stream()
            .filter(secret -> secret.length() >= 8)
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
    loadCallCounts();
  }

  public ReasoningTraceStore(Path runDirectory, String runId) {
    this(runDirectory, runId, List.of());
  }

  public synchronized ReasoningTraceCall beginCall(
      String taskId,
      String agentId,
      String stage,
      boolean thinkingEnabled,
      String reasoningEffort) {
    return beginCall(
        taskId,
        agentId,
        stage,
        "unbound-provider-call",
        thinkingEnabled,
        reasoningEffort);
  }

  public synchronized ReasoningTraceCall beginCall(
      String taskId,
      String agentId,
      String stage,
      String providerCallId,
      boolean thinkingEnabled,
      String reasoningEffort) {
    String safeTask = ActivitySanitizer.identifier(taskId, 240);
    String safeProviderCall = ActivitySanitizer.identifier(providerCallId, 160);
    if (safeProviderCall.isBlank()) {
      throw new IllegalArgumentException("providerCallId is required");
    }
    int callIndex = callCounts.merge(safeTask, 1, Integer::sum);
    ReasoningTraceCall call =
        new ReasoningTraceCall(this, safeTask, safeProviderCall, callIndex);
    call.start(agentId, stage, thinkingEnabled, reasoningEffort);
    return call;
  }

  public String runId() {
    return runId;
  }

  public Path path() {
    return path;
  }

  public synchronized java.util.Optional<CallArchive> findByProviderCallId(
      String providerCallId) {
    String expected = ActivitySanitizer.identifier(providerCallId, 160);
    if (expected.isBlank() || !Files.isRegularFile(path)) {
      return java.util.Optional.empty();
    }
    List<Map<String, Object>> matching = new ArrayList<>();
    try {
      scanRecords(
          path,
          0L,
          record -> {
            if (expected.equals(string(record.get("provider_call_id")))) {
              matching.add(record);
            }
          });
    } catch (IOException exception) {
      return java.util.Optional.empty();
    }
    if (matching.isEmpty()) {
      return java.util.Optional.empty();
    }
    Map<String, Object> snapshot = buildSnapshot(matching);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> calls = (List<Map<String, Object>>) snapshot.get("calls");
    List<Map<String, Object>> completed =
        calls.stream()
            .filter(call -> "completed".equals(string(call.get("status"))))
            .toList();
    if (completed.size() != 1) {
      return java.util.Optional.empty();
    }
    Map<String, Object> call = completed.getFirst();
    return java.util.Optional.of(
        new CallArchive(
            expected,
            string(matching.getFirst().get("task_id")),
            string(call.get("call_id")),
            string(call.get("agent_id")),
            string(call.get("stage")),
            string(call.get("status")),
            string(call.get("text")),
            string(call.get("sha256")),
            number(call.get("characters"))));
  }

  public synchronized CallArchive readCall(String providerCallId) {
    return findByProviderCallId(providerCallId)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "reasoning trace provider call was not found: " + providerCallId));
  }

  synchronized void appendRecord(Map<String, Object> record) {
    try {
      Files.createDirectories(
          Objects.requireNonNull(path.getParent(), "reasoning trace parent directory"));
      Files.writeString(
          path,
          ContractObjectMapper.write(record) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException exception) {
      throw new IllegalStateException("reasoning trace could not be appended", exception);
    }
  }

  synchronized void writePreview(Map<String, Object> record) {
    String callId = ActivitySanitizer.identifier(string(record.get("call_id")), 160);
    if (callId.isBlank()) {
      return;
    }
    Path liveDirectory = liveDirectory(path);
    Path target = liveDirectory.resolve(callId + ".json").normalize();
    if (!target.startsWith(liveDirectory) || target.equals(liveDirectory)) {
      throw new IllegalArgumentException("reasoning preview escaped its directory");
    }
    Path temporary = null;
    try {
      Files.createDirectories(liveDirectory);
      temporary = Files.createTempFile(liveDirectory, "reasoning-", ".tmp");
      Files.writeString(
          temporary,
          ContractObjectMapper.write(record) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING);
      try {
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("reasoning preview could not be updated", exception);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // The next preview scan ignores temporary files.
        }
      }
    }
  }

  synchronized void deletePreview(String callId) {
    String safeCallId = ActivitySanitizer.identifier(callId, 160);
    if (safeCallId.isBlank()) {
      return;
    }
    try {
      Files.deleteIfExists(liveDirectory(path).resolve(safeCallId + ".json"));
    } catch (IOException ignored) {
      // The compact archive is authoritative; stale previews are ignored once the run is closed.
    }
  }

  Redaction redact(String value) {
    String result = value == null ? "" : value;
    boolean redacted = false;
    for (String secret : secrets) {
      if (result.contains(secret)) {
        result = result.replace(secret, "[REDACTED]");
        redacted = true;
      }
    }
    String generic = ActivitySanitizer.redactSecretsPreservingWhitespace(result);
    redacted |= !generic.equals(result);
    return new Redaction(generic, redacted);
  }

  int flushCutoff(String value) {
    if (value.isEmpty() || secrets.isEmpty()) {
      return value.length();
    }
    int tail = Math.min(512, secrets.stream().mapToInt(String::length).max().orElse(0));
    int cutoff = Math.max(0, value.length() - tail);
    for (String secret : secrets) {
      int searchFrom = Math.max(0, cutoff - secret.length());
      int start = value.indexOf(secret, searchFrom);
      while (start >= 0) {
        int end = start + secret.length();
        if (start < cutoff && cutoff < end) {
          cutoff = start;
          break;
        }
        start = value.indexOf(secret, start + 1);
      }
    }
    return cutoff;
  }

  public static ReadResult readRecords(Path path, String taskId, long offset) {
    if (!Files.isRegularFile(path)) {
      return new ReadResult(List.of(), 0L);
    }
    List<Map<String, Object>> result = new ArrayList<>();
    try {
      long nextOffset =
          scanRecords(
              path,
              offset,
              record -> {
                if (taskId == null || taskId.equals(String.valueOf(record.get("task_id")))) {
                  result.add(record);
                }
              });
      return new ReadResult(result, nextOffset);
    } catch (IOException exception) {
      return new ReadResult(List.of(), Math.max(0L, offset));
    }
  }

  public static List<Map<String, Object>> readPreviews(Path archivePath, String taskId) {
    Path directory = liveDirectory(archivePath);
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    List<Map<String, Object>> result = new ArrayList<>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
      for (Path file : files) {
        try {
          JsonNode node = ContractObjectMapper.parseTree(Files.readString(file, StandardCharsets.UTF_8));
          Map<String, Object> record = jsonObject(node);
          if ("preview".equals(string(record.get("type")))
              && (taskId == null || taskId.equals(string(record.get("task_id"))))) {
            result.add(record);
          }
        } catch (IOException | RuntimeException ignored) {
          // An in-flight atomic replacement or corrupt preview cannot hide other calls.
        }
      }
    } catch (IOException ignored) {
      return List.of();
    }
    result.sort(
        Comparator.comparingLong((Map<String, Object> record) -> number(record.get("call_index")))
            .thenComparing(record -> string(record.get("call_id"))));
    return List.copyOf(result);
  }

  public static Map<String, Object> buildSnapshot(List<Map<String, Object>> records) {
    Map<String, Map<String, Object>> calls = new LinkedHashMap<>();
    Map<String, StringBuilder> parts = new LinkedHashMap<>();
    for (Map<String, Object> record : records) {
      String callId = string(record.get("call_id"));
      if (callId.isBlank()) {
        continue;
      }
      Map<String, Object> call =
          calls.computeIfAbsent(
              callId,
              ignored -> {
                Map<String, Object> initial = new LinkedHashMap<>();
                initial.put("call_id", callId);
                initial.put("provider_call_id", string(record.get("provider_call_id")));
                initial.put("call_index", number(record.get("call_index")));
                initial.put("agent_id", "");
                initial.put("stage", "");
                initial.put("thinking_enabled", null);
                initial.put("reasoning_effort", null);
                initial.put("status", "running");
                initial.put("started_at", null);
                initial.put("finished_at", null);
                initial.put("characters", 0L);
                initial.put("sha256", null);
                initial.put("redacted", false);
                return initial;
              });
      String type = string(record.get("type"));
      if ("start".equals(type)) {
        call.put("agent_id", string(record.get("agent_id")));
        call.put("stage", string(record.get("stage")));
        call.put("thinking_enabled", record.get("thinking_enabled"));
        call.put("reasoning_effort", record.get("reasoning_effort"));
        call.put("started_at", record.get("timestamp"));
      } else if ("delta".equals(type)) {
        parts.computeIfAbsent(callId, ignored -> new StringBuilder())
            .append(string(record.get("text")));
      } else if ("paragraph".equals(type) || "preview".equals(type)) {
        StringBuilder text = parts.computeIfAbsent(callId, ignored -> new StringBuilder());
        text.setLength(0);
        text.append(string(record.get("text")));
      } else if ("end".equals(type)) {
        call.put("status", string(record.get("status")));
        call.put("finished_at", record.get("timestamp"));
        call.put("characters", number(record.get("characters")));
        call.put("sha256", record.get("sha256"));
        call.put("redacted", Boolean.TRUE.equals(record.get("redacted")));
        call.put("error_type", record.get("error_type"));
      }
    }
    long characters = 0;
    boolean running = false;
    boolean hasReasoning = false;
    for (Map.Entry<String, Map<String, Object>> entry : calls.entrySet()) {
      String text = parts.getOrDefault(entry.getKey(), new StringBuilder()).toString();
      entry.getValue().put("text", text);
      long stored = number(entry.getValue().get("characters"));
      if (stored == 0) {
        stored = text.length();
        entry.getValue().put("characters", stored);
      }
      characters += stored;
      hasReasoning |= !text.isEmpty();
      running |= "running".equals(entry.getValue().get("status"));
    }
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("calls", List.copyOf(calls.values()));
    snapshot.put("has_records", !calls.isEmpty());
    snapshot.put("has_reasoning", hasReasoning);
    snapshot.put("running", running);
    snapshot.put("characters", characters);
    return snapshot;
  }

  private void loadCallCounts() {
    if (!Files.isRegularFile(path)) {
      return;
    }
    try {
      scanRecords(
          path,
          0L,
          record -> {
            if (!"start".equals(record.get("type"))) {
              return;
            }
            String taskId = string(record.get("task_id"));
            int index = (int) number(record.get("call_index"));
            if (!taskId.isBlank() && index > 0) {
              callCounts.merge(taskId, index, Math::max);
            }
          });
    } catch (IOException ignored) {
      // A missing or temporarily unreadable archive starts with empty in-memory counts.
    }
  }

  private static long scanRecords(
      Path path, long requestedOffset, Consumer<Map<String, Object>> consumer)
      throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      long length = channel.size();
      long cursor = Math.max(0L, requestedOffset);
      if (cursor > length) {
        cursor = 0L;
      }
      channel.position(cursor);
      long position = cursor;
      long lineStart = cursor;
      ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
      ByteArrayOutputStream line = new ByteArrayOutputStream(4_096);
      while (true) {
        int count = channel.read(buffer);
        if (count < 0) {
          return line.size() == 0 ? position : lineStart;
        }
        if (count == 0) {
          continue;
        }
        buffer.flip();
        while (buffer.hasRemaining()) {
          byte value = buffer.get();
          position++;
          if (value == '\n') {
            acceptCompleteRecord(line, consumer);
            line.reset();
            lineStart = position;
          } else {
            line.write(value);
          }
        }
        buffer.clear();
      }
    }
  }

  private static void acceptCompleteRecord(
      ByteArrayOutputStream encodedLine, Consumer<Map<String, Object>> consumer) {
    byte[] bytes = encodedLine.toByteArray();
    int length = bytes.length;
    if (length > 0 && bytes[length - 1] == '\r') {
      length--;
    }
    try {
      JsonNode node =
          ContractObjectMapper.parseTree(new String(bytes, 0, length, StandardCharsets.UTF_8));
      consumer.accept(jsonObject(node));
    } catch (RuntimeException ignored) {
      // A corrupt complete record is isolated; later append-log records remain readable.
    }
  }

  private static Map<String, Object> jsonObject(JsonNode node) {
    Map<String, Object> result = new LinkedHashMap<>();
    node.properties().forEach(entry -> result.put(entry.getKey(), jsonValue(entry.getValue())));
    return result;
  }

  private static Object jsonValue(JsonNode node) {
    if (node.isNull()) {
      return null;
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    if (node.isIntegralNumber()) {
      return node.asLong();
    }
    if (node.isFloatingPointNumber()) {
      return node.asDouble();
    }
    return node.asText();
  }

  private static Path liveDirectory(Path archivePath) {
    Path normalized = archivePath.toAbsolutePath().normalize();
    return Objects.requireNonNull(normalized.getParent(), "reasoning archive parent directory")
        .resolve(LIVE_DIRECTORY_NAME)
        .normalize();
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static long number(Object value) {
    if (value instanceof Number number) {
      return Math.max(0L, number.longValue());
    }
    try {
      return Math.max(0L, Long.parseLong(string(value)));
    } catch (NumberFormatException ignored) {
      return 0L;
    }
  }

  record Redaction(String value, boolean redacted) {}

  public record ReadResult(List<Map<String, Object>> records, long nextOffset) {
    public ReadResult {
      records = List.copyOf(records);
      nextOffset = Math.max(0L, nextOffset);
    }
  }

  public record CallArchive(
      String providerCallId,
      String taskId,
      String reasoningTraceCallId,
      String agentId,
      String stage,
      String status,
      String text,
      String sha256,
      long characters) {

    public CallArchive {
      providerCallId = string(providerCallId);
      taskId = string(taskId);
      reasoningTraceCallId = string(reasoningTraceCallId);
      agentId = string(agentId);
      stage = string(stage);
      status = string(status);
      text = string(text);
      sha256 = string(sha256);
      characters = Math.max(0L, characters);
    }
  }
}
