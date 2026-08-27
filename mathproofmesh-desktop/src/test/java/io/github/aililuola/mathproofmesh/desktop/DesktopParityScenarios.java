package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.api.ReasoningTraceCall;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceStore;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

final class DesktopParityScenarios {
  private DesktopParityScenarios() {}

  @FunctionalInterface
  interface Scenario {
    void run(Path directory) throws Exception;
  }

  static void settingsAndCredentialsPersistedWithoutPlaintext(Path directory) throws Exception {
    DesktopPaths paths = DesktopTestSupport.paths(directory.resolve("desktop"));
    SettingsStore store = new SettingsStore(paths.settingsFile(), DesktopTestSupport.MAPPER);
    store.save(new DesktopSettings("formal", false, true));
    assertEquals("formal", store.load().selectedProfile());
    assertFalse(store.load().sandboxEnabled());

    CredentialVault vault = DesktopTestSupport.vault(paths);
    vault.set("DEEPSEEK_AGENT_1_KEY", "top-secret-value", true);
    assertFalse(Files.readString(paths.credentialsFile()).contains("top-secret-value"));
    CredentialVault reloaded = DesktopTestSupport.vault(paths);
    assertEquals("top-secret-value", reloaded.get("DEEPSEEK_AGENT_1_KEY"));
    assertEquals("session", reloaded.statuses().get("DEEPSEEK_AGENT_1_KEY"));
  }

  static void configUsesWritablePathsAndInjectedKeys(Path directory) {
    DesktopPaths paths = DesktopTestSupport.paths(directory.resolve("desktop"));
    CredentialVault vault = DesktopTestSupport.vault(paths);
    IntStream.rangeClosed(1, 5)
        .forEach(
            index ->
                vault.set(
                    "DEEPSEEK_AGENT_" + index + "_KEY", "secret-" + index, false));
    DesktopConfigService.PreparedDesktopConfig prepared =
        new DesktopConfigService(paths, vault)
            .build("smoke", new DesktopSettings("smoke", false, true));
    assertEquals(paths.root(), prepared.projectRoot());
    assertEquals(paths.runs(), prepared.runRoot());
    assertEquals(paths.learning(), prepared.learningRoot());
    assertFalse(prepared.sandboxEnabled());
    assertEquals(5, prepared.injectedCredentials().size());
  }

  static void appRequiresCookieAndServesWorkbench(Path directory) throws Exception {
    DesktopPaths paths = DesktopTestSupport.paths(directory.resolve("desktop"));
    try (DesktopTestSupport.HttpSession session = new DesktopTestSupport.HttpSession(paths)) {
      assertEquals(401, session.getWithoutCookie("/api/bootstrap").statusCode());
      HttpResponse<String> index = session.get("/");
      assertEquals(200, index.statusCode());
      for (String id :
          List.of(
              "topology-view",
              "reasoning-dock",
              "reasoning-authority",
              "topology-context-menu",
              "show-computation-menu-button",
              "clarification-dialog")) {
        assertTrue(index.body().contains("id=\"" + id + "\""), id);
      }
      HttpResponse<String> topology = session.get("/assets/topology.js");
      assertEquals(200, topology.statusCode());
      assertTrue(topology.body().contains("MathProofMeshTopology"));

      Map<String, Object> bootstrap = DesktopTestSupport.json(session.get("/api/bootstrap").body());
      assertEquals("0.8.0", bootstrap.get("version"));
      assertEquals(5, ((List<?>) bootstrap.get("profiles")).size());
      HttpResponse<String> settings =
          session.put(
              "/api/settings",
              """
              {"selected_profile":"formal","sandbox_enabled":false,
               "remember_credentials":false}
              """);
      assertEquals(200, settings.statusCode());
      assertTrue(settings.body().contains("\"selected_profile\":\"formal\""));
      HttpResponse<String> credentials =
          session.put(
              "/api/credentials",
              """
              {"values":{"DEEPSEEK_AGENT_1_KEY":"session-secret"},
               "clear":[],"persist":false}
              """);
      assertEquals(200, credentials.statusCode());
      assertTrue(credentials.body().contains("\"DEEPSEEK_AGENT_1_KEY\":\"session\""));
      assertFalse(credentials.body().contains("session-secret"));
    }
  }

  static void activityReturnsLatestLogicalSnapshot(Path directory) throws Exception {
    DesktopPaths paths = DesktopTestSupport.paths(directory.resolve("desktop"));
    Path run = Files.createDirectories(paths.runs().resolve("logical-steps"));
    writeJsonLines(
        run.resolve("activity.jsonl"),
        List.of(
            event(1, "step-1", "running", "received 1,000 chunks"),
            event(2, "step-1", "running", "received 2,000 chunks"),
            event(3, "step-2", "running", "parallel route B"),
            event(4, "step-1", "completed", "route A completed")));
    List<Map<String, Object>> activity =
        new RunRepository(paths, DesktopTestSupport.MAPPER).readActivity("logical-steps", 250);
    assertEquals(List.of("step-1", "step-2"), activity.stream().map(row -> row.get("task_id")).toList());
    assertEquals(4, ((Number) activity.getFirst().get("sequence")).intValue());
    assertEquals("route A completed", activity.getFirst().get("detail"));
  }

  static void activityReconstructsTopologyMetadata(Path directory) throws Exception {
    DesktopPaths paths = DesktopTestSupport.paths(directory.resolve("desktop"));
    Path run = Files.createDirectories(paths.runs().resolve("topology"));
    List<Map<String, Object>> events = new ArrayList<>();
    events.add(topologyEvent(1, 0, "run", "run", null, "running"));
    events.add(topologyEvent(2, 10, "stage", "stage", "run", "running"));
    events.add(topologyEvent(3, 20, "agent_call", "agent", null, "running"));
    events.add(topologyEvent(4, 40, "agent_call_heartbeat", "agent", null, "running"));
    events.add(topologyEvent(5, 80, "agent_call_completed", "agent", null, "completed"));
    events.add(topologyEvent(6, 90, "task_completed", "stage", "run", "completed"));
    writeJsonLines(run.resolve("activity.jsonl"), events);
    List<Map<String, Object>> activity =
        new RunRepository(paths, DesktopTestSupport.MAPPER).readActivity("topology", null);
    assertEquals(List.of("run", "stage", "agent"), activity.stream().map(row -> row.get("task_id")).toList());
    Map<String, Object> agent = activity.getLast();
    assertEquals("stage", agent.get("parent_task_id"));
    assertEquals(20, ((Number) agent.get("started_elapsed_ms")).intValue());
    assertEquals("agent_call", agent.get("initial_event_type"));
  }

  static void reasoningSnapshotAndCompletedReplay(Path directory) throws Exception {
    DesktopPaths paths = DesktopTestSupport.paths(directory.resolve("desktop"));
    Path run = Files.createDirectories(paths.runs().resolve("reasoning-run"));
    Files.createDirectories(run.resolve("reports"));
    writeJsonLines(
        run.resolve("activity.jsonl"),
        List.of(
            topologyEvent(1, 10, "agent_call", "agent-node", null, "running"),
            topologyEvent(2, 50, "agent_call_completed", "agent-node", null, "completed"),
            topologyEvent(3, 60, "agent_call", "legacy-node", null, "completed")));
    ReasoningTraceCall trace =
        new ReasoningTraceStore(run, "reasoning-run")
            .beginCall("agent-node", "ds-explorer-a", "route_prove", true, "max");
    String reasoning = "first line\nsecond line\n" + "x".repeat(17_000);
    trace.append(reasoning);

    RunRepository repository = new RunRepository(paths, DesktopTestSupport.MAPPER);
    Map<String, Object> liveSnapshot = repository.reasoningSnapshot("reasoning-run", "agent-node");
    assertEquals("running", liveSnapshot.get("trace_state"));
    assertEquals(
        reasoning,
        ((Map<?, ?>) ((List<?>) liveSnapshot.get("calls")).getFirst()).get("text"));

    trace.finish(ReasoningTraceCall.Status.COMPLETED);

    Map<String, Object> snapshot = repository.reasoningSnapshot("reasoning-run", "agent-node");
    assertEquals("completed", snapshot.get("trace_state"));
    assertEquals("reports/reasoning_traces.txt", snapshot.get("archive"));
    assertEquals(false, ((Map<?, ?>) snapshot.get("reasoning_authority")).get("premise_eligible"));
    assertEquals(
        reasoning,
        ((Map<?, ?>) ((List<?>) snapshot.get("calls")).getFirst()).get("text"));

    try (DesktopTestSupport.HttpSession session = new DesktopTestSupport.HttpSession(paths)) {
      HttpResponse<String> replay =
          session.get("/api/runs/reasoning-run/nodes/agent-node/reasoning/events?after=0");
      assertEquals(200, replay.statusCode());
      assertTrue(replay.body().contains("event:reasoning"));
      assertTrue(replay.body().contains("first line"));
      assertTrue(replay.body().contains("event:terminal"));
    }
    assertEquals(
        "legacy_unavailable",
        repository.reasoningSnapshot("reasoning-run", "legacy-node").get("trace_state"));
  }

  static void computationSnapshotCompleteAndRedacted(Path directory) throws Exception {
    DesktopPaths paths = DesktopTestSupport.paths(directory.resolve("desktop"));
    String requestHash = "a".repeat(64);
    Path run = Files.createDirectories(paths.runs().resolve("computation-run"));
    Path experiment = Files.createDirectories(run.resolve("experiments").resolve(requestHash));
    Map<String, Object> activity = new LinkedHashMap<>();
    activity.put("sequence", 4);
    activity.put("elapsed_ms", 2500);
    activity.put("started_elapsed_ms", 100);
    activity.put("event_type", "computation_completed");
    activity.put("initial_event_type", "python_experiment");
    activity.put("task_id", "computation:experiment-a");
    activity.put("status", "completed");
    activity.put(
        "metrics",
        Map.of(
            "phase", "completed",
            "method", "sandboxed_python",
            "experiment_id", "experiment-a",
            "request_hash", requestHash,
            "result_hash", "b".repeat(64),
            "decision", "allow",
            "rule_id", "test"));
    writeJsonLines(run.resolve("activity.jsonl"), List.of(activity));
    writeJson(
        experiment.resolve("spec.json"),
        Map.of(
            "experiment_id", "experiment-a",
            "request_hash", requestHash,
            "method", "sandboxed_python",
            "target_claim", "Check a finite predicate.",
            "arguments", Map.of("input", Map.of("n", 7))));
    writeJson(
        experiment.resolve("decision.json"),
        Map.of("decision", "allow", "reason", "Targeted check admitted.", "rule_id", "test"));
    writeJson(
        experiment.resolve("program.json"),
        Map.of(
            "source",
            "def run(data):\n  marker = 'sk-this-must-never-be-shown'\n  return data"));
    Map<String, Object> execution = new LinkedHashMap<>();
    execution.put("runtime_seconds", 2.4);
    execution.put("error", null);
    execution.put("tool_name", "sandboxed_python");
    execution.put("tool_version", "test");
    execution.put("program_hash", "d".repeat(64));
    execution.put("input", Map.of("sandbox_input", Map.of("n", 7, "seed", 1)));
    execution.put(
        "output",
        Map.of(
            "handler_output", Map.of("outcome", "not_refuted"),
            "process", Map.of("exit_code", 0, "stdout", "{\"outcome\":\"not_refuted\"}")));
    execution.put("result_hash", "b".repeat(64));
    execution.put(
        "environment", Map.of("sandbox", Map.of("network", "none"), "api_key", "plain-secret"));
    writeJson(experiment.resolve("execution.json"), execution);
    writeJson(
        experiment.resolve("result.json"),
        Map.of("outcome", "not_refuted", "evidence_strength", "bounded_evidence"));
    writeJson(
        experiment.resolve("computation_certificate.json"),
        Map.of("request_hash", requestHash, "result_hash", "b".repeat(64)));

    Map<String, Object> snapshot =
        new RunRepository(paths, DesktopTestSupport.MAPPER)
            .computationSnapshot("computation-run", "computation:experiment-a");
    assertEquals("completed", snapshot.get("phase"));
    assertTrue(((Map<?, ?>) snapshot.get("program")).get("source").toString().contains("[REDACTED]"));
    assertEquals("[REDACTED]", ((Map<?, ?>) snapshot.get("environment")).get("api_key"));
    assertEquals(
        0,
        ((Number)
                ((Map<?, ?>) ((Map<?, ?>) snapshot.get("runtime")).get("process"))
                    .get("exit_code"))
            .intValue());
    assertEquals("b".repeat(64), ((Map<?, ?>) snapshot.get("audit")).get("result_hash"));
    assertEquals(
        "Targeted check admitted.", ((Map<?, ?>) snapshot.get("decision")).get("reason"));
    assertEquals(
        7,
        ((Number)
                ((Map<?, ?>) ((Map<?, ?>) snapshot.get("input")).get("sandbox_input")).get("n"))
            .intValue());
  }

  static void deleteMovesRunToRecycleBin(Path directory) throws Exception {
    DesktopPaths paths = DesktopTestSupport.paths(directory.resolve("desktop"));
    Path run = Files.createDirectories(paths.runs().resolve("delete-me"));
    Files.writeString(run.resolve("proof.txt"), "proof");
    new RunRepository(paths, DesktopTestSupport.MAPPER).moveToRecycleBin("delete-me");
    assertFalse(Files.exists(run));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RunRepository(paths, DesktopTestSupport.MAPPER).runDirectory("../outside"));
  }

  static void managerPublishesTerminalState(Path directory) throws Exception {
    DesktopPaths paths = DesktopTestSupport.paths(directory.resolve("desktop"));
    try (DesktopRunManager manager = DesktopTestSupport.manager(paths)) {
      Map<String, Object> started =
          manager.start(new StartRunRequest("Prove that 1 + 1 = 2.", "smoke", "desktop-test"));
      LiveRun session = manager.session(String.valueOf(started.get("run_id")));
      assertNotNull(session);
      assertNotNull(session.task());
      session.task().get(15, TimeUnit.SECONDS);
      assertEquals("completed", session.metadata().lifecycle());
      assertTrue(session.eventsAfter(0).stream().anyMatch(event -> "result".equals(event.event())));
      assertEquals("terminal", session.eventsAfter(0).getLast().event());
    }
  }

  static void resumeDefaultsToNormal(Path directory) {
    assertEquals("normal", new ResumeRunRequest("smoke", null).resumeMode());
    assertEquals("replay_stage", new ResumeRunRequest("smoke", "replay_stage").resumeMode());
    IllegalArgumentException invalid =
        assertThrows(
            IllegalArgumentException.class, () -> new ResumeRunRequest("smoke", "bogus"));
    assertTrue(invalid.getMessage().contains("resume_mode"));
  }

  static void managerThreadsResumeMode(Path directory) throws Exception {
    DesktopPaths paths = DesktopTestSupport.paths(directory.resolve("desktop"));
    Path runDirectory = Files.createDirectories(paths.runs().resolve("resume-test"));
    Files.writeString(runDirectory.resolve("problem.txt"), "Prove that 1 + 1 = 2.");
    try (DesktopRunManager manager = DesktopTestSupport.manager(paths)) {
      Map<String, Object> first =
          manager.resume("resume-test", new ResumeRunRequest("smoke", null));
      LiveRun session = manager.session(String.valueOf(first.get("run_id")));
      assertNotNull(session);
      session.task().get(15, TimeUnit.SECONDS);
      assertEquals("completed", session.metadata().lifecycle());
      Map<String, Object> second =
          manager.resume(
              "resume-test", new ResumeRunRequest("smoke", "reset_stagnation"));
      session = manager.session(String.valueOf(second.get("run_id")));
      session.task().get(15, TimeUnit.SECONDS);
      assertTrue(
          session.eventsAfter(0).stream()
              .map(LiveRun.DesktopEvent::data)
              .anyMatch(data -> "reset_stagnation".equals(data.get("resume_mode"))));
    }
  }

  static void managerClarificationConfirmation(Path directory) throws Exception {
    DesktopPaths paths = DesktopTestSupport.paths(directory.resolve("desktop"));
    try (DesktopRunManager manager = DesktopTestSupport.manager(paths)) {
      manager.start(
          new StartRunRequest(
              "Prove there are infinitely many primes congruent to 1.", "smoke", "clarify"));
      LiveRun session = manager.session("clarify");
      session.task().get(15, TimeUnit.SECONDS);
      manager.beginClarification("clarify", "request-1");
      assertEquals("awaiting_confirmation", session.metadata().lifecycle());
      assertEquals("request-1", session.pendingClarificationId());
      manager.confirmClarification(
          "clarify",
          new ClarificationDecisionRequest(
              "request-1", "Use congruence modulo 4.", 0));
      assertEquals(null, session.pendingClarificationId());
      assertTrue(
          session.eventsAfter(0).stream()
              .anyMatch(event -> "clarification".equals(event.event())));
    }
  }

  private static Map<String, Object> event(
      int sequence, String taskId, String status, String detail) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("sequence", sequence);
    event.put("task_id", taskId);
    event.put("status", status);
    event.put("detail", detail);
    return event;
  }

  private static Map<String, Object> topologyEvent(
      int sequence,
      int elapsed,
      String eventType,
      String taskId,
      String parentTaskId,
      String status) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("sequence", sequence);
    event.put("elapsed_ms", elapsed);
    event.put("event_type", eventType);
    event.put("task_id", taskId);
    if (parentTaskId != null) {
      event.put("parent_task_id", parentTaskId);
    }
    event.put("status", status);
    return event;
  }

  private static void writeJsonLines(Path path, List<Map<String, Object>> rows) throws Exception {
    Files.createDirectories(path.getParent());
    StringBuilder payload = new StringBuilder();
    for (Map<String, Object> row : rows) {
      payload.append(DesktopTestSupport.MAPPER.writeValueAsString(row)).append('\n');
    }
    Files.writeString(path, payload, StandardCharsets.UTF_8);
  }

  private static void writeJson(Path path, Map<String, Object> value) throws Exception {
    Files.writeString(
        path,
        DesktopTestSupport.MAPPER.writeValueAsString(value),
        StandardCharsets.UTF_8);
  }
}
