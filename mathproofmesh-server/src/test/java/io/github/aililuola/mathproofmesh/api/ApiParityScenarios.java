package io.github.aililuola.mathproofmesh.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.api.RunApiModels.RouteView;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RunView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Authority-named activity, trace, report, and server scenarios for phase 14. */
final class ApiParityScenarios {
  private static final String SECRET = "sk-authority-secret-123456789";

  private ApiParityScenarios() {}

  static void verify(String testClass, String authorityFunction, Path temporaryDirectory) {
    assertTrue(authorityFunction.startsWith("test_"));
    switch (authorityFunction) {
      case "test_activity_stream_redacts_and_persists" ->
          activityRedaction(temporaryDirectory);
      case "test_activity_stream_infers_and_preserves_topology_parent" ->
          activityTopology(temporaryDirectory);
      case "test_compact_console_view_prints_one_snapshot_per_task_without_ansi" ->
          compactConsole(temporaryDirectory);
      case "test_long_agent_call_emits_content_free_heartbeat" ->
          contentFreeHeartbeat(temporaryDirectory);
      case "test_activity_stream_continues_existing_timeline_after_restart" ->
          activityRestart(temporaryDirectory);
      case "test_reasoning_trace_is_single_append_only_archive_with_secret_redaction" ->
          reasoningArchive(temporaryDirectory);
      case "test_reasoning_trace_cursor_exposes_live_deltas_once" ->
          reasoningCursor(temporaryDirectory);
      case "test_deepseek_streaming_trace_preserves_reasoning_chunk_order" ->
          reasoningChunkOrder(temporaryDirectory);
      case "test_deepseek_non_streaming_and_failed_stream_trace_lifecycle" ->
          reasoningLifecycle(temporaryDirectory);
      case "test_unverified_report_surfaces_verified_local_claims" ->
          reportVerifiedClaims();
      case "test_progress_summary_distinguishes_verified_claims_from_passed_routes" ->
          reportDistinctCounts();
      case "test_server_exposes_resume_routes_and_health" ->
          serverSurface(temporaryDirectory);
      default -> throw new AssertionError("unmapped phase-14 function: " + authorityFunction);
    }
    assertExpectedClass(testClass, authorityFunction);
  }

  private static void activityRedaction(Path directory) {
    ActivityStream stream = new ActivityStream(directory);
    String task =
        stream.startTask(
            "agent",
            "Call " + SECRET,
            "Bearer abcdefghijklmnop",
            "explore",
            ActivityImportance.NORMAL,
            Map.of("api_key", SECRET, "count", 2),
            null,
            "agent-a");
    stream.completeTask(task, "Done", "credential removed", "explore", "agent-a");
    ActivityStream.ReportReferences references = stream.finalizeTimeline();

    String persisted = read(directory.resolve("activity.jsonl"));
    String collapsed = read(directory.resolve("reports/activity_timeline.json"));
    assertFalse(persisted.contains(SECRET));
    assertFalse(collapsed.contains(SECRET));
    assertTrue(persisted.contains("[REDACTED]"));
    assertEquals(2, stream.events().size());
    assertEquals(1, ActivityStream.collapse(stream.events()).size());
    assertNotNull(references.jsonReference());
    assertNotNull(references.markdownReference());
  }

  private static void activityTopology(Path directory) {
    ActivityStream stream = new ActivityStream(directory);
    String run =
        stream.startTask(
            "run", "Run", "", "goal", ActivityImportance.NORMAL, Map.of(), null, null);
    String stage =
        stream.startTask(
            "stage", "Stage", "", "triage", ActivityImportance.NORMAL, Map.of(), run, null);
    String agent =
        stream.startTask(
            "agent",
            "Agent",
            "",
            "triage",
            ActivityImportance.NORMAL,
            Map.of(),
            null,
            "agent-a");
    ActivityEvent updated =
        stream.updateTask(
            agent,
            "Agent updated",
            "",
            ActivityStatus.RUNNING,
            "triage",
            ActivityImportance.NORMAL,
            Map.of(),
            "agent-a");
    assertEquals(stage, updated.parentTaskId());
    assertEquals("agent", updated.initialEventType());
    assertNotNull(updated.startedElapsedMs());
  }

  private static void compactConsole(Path directory) {
    StringWriter buffer = new StringWriter();
    try (ConsoleActivityView view =
        new ConsoleActivityView(new PrintWriter(buffer), ConsoleActivityView.Mode.COMPACT)) {
      ActivityStream stream = new ActivityStream(directory, "en", false, view);
      String task =
          stream.startTask(
              "agent",
              "Explorer",
              "starting",
              "triage",
              ActivityImportance.NORMAL,
              Map.of(),
              null,
              "agent-a");
      stream.updateTask(
          task,
          "Explorer",
          "private intermediate text",
          ActivityStatus.RUNNING,
          "triage",
          ActivityImportance.DETAIL,
          Map.of(),
          "agent-a");
      stream.completeTask(task, "Explorer", "finished", "triage", "agent-a");
    }
    String output = buffer.toString();
    assertEquals(1, occurrences(output, "Explorer"));
    assertFalse(output.contains("\u001B"));
    assertFalse(output.contains("private intermediate text"));
  }

  private static void contentFreeHeartbeat(Path directory) {
    ActivityStream stream = new ActivityStream(directory);
    String task =
        stream.startTask(
            "agent",
            "Call",
            SECRET,
            "triage",
            ActivityImportance.NORMAL,
            Map.of(),
            null,
            "agent-a");
    ActivityEvent heartbeat = stream.heartbeat(task, "triage", "agent-a");
    assertEquals("agent_call_heartbeat", heartbeat.eventType());
    assertEquals("", heartbeat.detail());
    assertFalse(heartbeat.title().contains(SECRET));
    assertFalse(heartbeat.metrics().containsValue(SECRET));
  }

  private static void activityRestart(Path directory) {
    ActivityStream first = new ActivityStream(directory);
    first.info("run_started", "First");
    ActivityStream second = new ActivityStream(directory);
    ActivityEvent resumed = second.info("checkpoint", "Second");
    assertEquals(2L, resumed.sequence());
    assertEquals(2, second.events().size());
    assertTrue(read(directory.resolve("activity.jsonl")).contains("First"));
    assertTrue(read(directory.resolve("activity.jsonl")).contains("Second"));
  }

  private static void reasoningArchive(Path directory) {
    ReasoningTraceStore store =
        new ReasoningTraceStore(directory, "trace-run", List.of(SECRET));
    ReasoningTraceCall first =
        store.beginCall("task-a", "agent-a", "explore", true, "high");
    first.append("prefix " + SECRET.substring(0, 12));
    first.append(SECRET.substring(12) + " suffix");
    first.finish(ReasoningTraceCall.Status.COMPLETED);
    ReasoningTraceCall second =
        store.beginCall("task-a", "agent-a", "verify", false, null);
    second.append("safe");
    second.finish(ReasoningTraceCall.Status.COMPLETED);

    String archive = read(store.path());
    assertFalse(archive.contains(SECRET));
    assertTrue(archive.contains("[REDACTED]"));
    assertEquals(1, Files.exists(store.path()) ? 1 : 0);
    Map<String, Object> snapshot =
        ReasoningTraceStore.buildSnapshot(
            ReasoningTraceStore.readRecords(store.path(), "task-a", 0).records());
    assertEquals(2, calls(snapshot).size());
    ReasoningTraceStore restarted = new ReasoningTraceStore(directory, "trace-run");
    ReasoningTraceCall third =
        restarted.beginCall("task-a", "agent-a", "verify", false, null);
    third.finish(ReasoningTraceCall.Status.COMPLETED);
    assertEquals(3L, callIndex(calls(snapshot(restarted.path())).get(2)));
  }

  private static void reasoningCursor(Path directory) {
    ReasoningTraceStore store = new ReasoningTraceStore(directory, "cursor-run");
    ReasoningTraceCall call =
        store.beginCall("task-a", "agent-a", "explore", true, "medium");
    ReasoningTraceStore.ReadResult initial =
        ReasoningTraceStore.readRecords(store.path(), "task-a", 0);
    assertEquals(1, initial.records().size());
    String liveParagraph = "live delta ".repeat(2_000);
    call.append(liveParagraph);
    call.flushDue();
    ReasoningTraceStore.ReadResult beforeFinish =
        ReasoningTraceStore.readRecords(store.path(), "task-a", initial.nextOffset());
    assertTrue(beforeFinish.records().isEmpty());
    List<Map<String, Object>> previews = ReasoningTraceStore.readPreviews(store.path(), "task-a");
    assertEquals(1, previews.size());
    assertEquals("preview", previews.getFirst().get("type"));
    assertEquals(liveParagraph, previews.getFirst().get("text"));
    call.finish(ReasoningTraceCall.Status.COMPLETED);
    ReasoningTraceStore.ReadResult completed =
        ReasoningTraceStore.readRecords(store.path(), "task-a", initial.nextOffset());
    assertEquals(List.of("paragraph", "end"),
        completed.records().stream().map(record -> record.get("type")).toList());
    assertEquals(liveParagraph, completed.records().getFirst().get("text"));
    assertTrue(ReasoningTraceStore.readPreviews(store.path(), "task-a").isEmpty());
  }

  private static void reasoningChunkOrder(Path directory) {
    ReasoningTraceStore store = new ReasoningTraceStore(directory, "stream-run");
    ReasoningTraceCall call =
        store.beginCall("task-a", "deepseek", "explore", true, "high");
    call.append("first ");
    call.append("second ");
    call.append("third");
    call.finish(ReasoningTraceCall.Status.COMPLETED);
    List<Map<String, Object>> records =
        ReasoningTraceStore.readRecords(store.path(), "task-a", 0).records();
    assertEquals(
        List.of("start", "paragraph", "end"),
        records.stream().map(record -> record.get("type")).toList());
    Map<String, Object> item = calls(snapshot(store.path())).getFirst();
    assertEquals("first second third", item.get("text"));
    assertEquals("completed", item.get("status"));
  }

  private static void reasoningLifecycle(Path directory) {
    ReasoningTraceStore store = new ReasoningTraceStore(directory, "lifecycle-run");
    ReasoningTraceCall complete =
        store.beginCall("task-a", "deepseek", "explore", false, null);
    complete.append("non-stream response");
    complete.finish(ReasoningTraceCall.Status.COMPLETED);
    ReasoningTraceCall failed =
        store.beginCall("task-b", "deepseek", "verify", true, "high");
    failed.append("partial response");
    failed.finish(ReasoningTraceCall.Status.FAILED, "TransportFailure");
    List<Map<String, Object>> values = calls(snapshot(store.path()));
    assertEquals(List.of("completed", "failed"), values.stream().map(v -> v.get("status")).toList());
    assertEquals("TransportFailure", values.get(1).get("error_type"));
    assertEquals("partial response", values.get(1).get("text"));
  }

  private static void reportVerifiedClaims() {
    RunView run = runView(List.of(), List.of("claim-local"));
    String report = ReportFunctions.render(run, List.of());
    assertTrue(report.contains("Verified local claims: 1"));
    assertTrue(report.contains("Independently passed routes: 0"));
  }

  private static void reportDistinctCounts() {
    RunView run = runView(List.of(), List.of("claim-local"));
    List<RouteView> routes =
        List.of(new RouteView("route-a", "unverified", "not independently passed", List.of()));
    String report = ReportFunctions.render(run, routes);
    assertTrue(report.contains("Verified local claims: 1"));
    assertTrue(report.contains("Independently passed routes: 0"));
    assertFalse(report.contains("Independently passed routes: 1"));
  }

  private static void serverSurface(Path directory) {
    Map<String, Object> health = new HealthController().health();
    assertEquals(Boolean.TRUE, health.get("ok"));
    assertEquals("/resume", health.get("resume_endpoint"));
    assertEquals("/resume/stream", health.get("resume_stream"));
    RunApiService service =
        new RunApiService(
            new ApiObservability(new SimpleMeterRegistry()), directory.toString(), 2);
    assertThrows(
        RunApiService.ApiNotFoundException.class,
        () -> service.resume(new ResumeRequest("missing-run")));
    assertThrows(
        RunApiService.ApiNotFoundException.class, () -> service.routes("missing-run"));
  }

  private static RunView runView(List<String> routes, List<String> claims) {
    return new RunView(
        "report-run",
        "unverified",
        "report",
        "Local work is useful but the complete route is not verified.",
        null,
        "0123456789abcdef0123456789abcdef",
        5,
        2,
        routes,
        claims);
  }

  private static void assertExpectedClass(String testClass, String function) {
    boolean expected =
        switch (testClass) {
          case "ActivityParityTest" ->
              function.contains("activity")
                  || function.contains("console")
                  || function.contains("heartbeat");
          case "ReasoningTraceParityTest" ->
              function.contains("reasoning_trace") || function.contains("deepseek");
          case "ResearchProgressReportingParityTest" ->
              function.contains("report") || function.contains("progress_summary");
          case "ServerParityTest" -> function.contains("server");
          default -> false;
        };
    assertTrue(expected, () -> "authority function mapped to unexpected class " + testClass);
  }

  private static Map<String, Object> snapshot(Path path) {
    return ReasoningTraceStore.buildSnapshot(
        ReasoningTraceStore.readRecords(path, null, 0).records());
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> calls(Map<String, Object> snapshot) {
    return (List<Map<String, Object>>) snapshot.get("calls");
  }

  private static long callIndex(Map<String, Object> call) {
    return ((Number) call.get("call_index")).longValue();
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (java.io.IOException exception) {
      throw new AssertionError("test artifact could not be read", exception);
    }
  }

  private static int occurrences(String value, String needle) {
    int count = 0;
    int offset = 0;
    while ((offset = value.indexOf(needle, offset)) >= 0) {
      count++;
      offset += needle.length();
    }
    return count;
  }
}
