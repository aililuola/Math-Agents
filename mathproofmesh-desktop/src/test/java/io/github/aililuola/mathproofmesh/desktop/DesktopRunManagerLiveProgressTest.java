package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.aililuola.mathproofmesh.api.ReasoningTraceBinding;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend.ExecutionUsage;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class DesktopRunManagerLiveProgressTest {
  @TempDir Path temporaryDirectory;

  @Test
  void publishesBackendProgressBeforeTheSolveCompletes() throws Exception {
    CountDownLatch providerStarted = new CountDownLatch(1);
    CountDownLatch allowCompletion = new CountDownLatch(1);
    RunExecutionBackend backend =
        (request, runId, traceId, runDirectory, progress) -> {
          progress.emit(
              "agent_started",
              "triage",
              "test-planner",
              "running",
              "Classifying the problem",
              null);
          providerStarted.countDown();
          try {
            if (!allowCompletion.await(10, TimeUnit.SECONDS)) {
              throw new IllegalStateException("test backend timed out");
            }
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test backend was interrupted", exception);
          }
          return new RunExecutionBackend.RunExecutionResult(
              "completed", "report", "completed", List.of(), List.of(), "report", 1);
        };

    DesktopPaths paths = DesktopTestSupport.paths(temporaryDirectory.resolve("desktop"));
    try (DesktopRunManager manager = DesktopTestSupport.manager(paths, backend)) {
      manager.start(
          new StartRunRequest(
              "Prove that 1 + 1 = 2.", "smoke", "desktop-live-progress"));
      LiveRun session = manager.session("desktop-live-progress");

      assertTrue(providerStarted.await(5, TimeUnit.SECONDS));
      assertFalse(session.task().isDone());
      assertTrue(
          session.eventsAfter(0).stream()
              .filter(event -> "activity".equals(event.event()))
              .map(LiveRun.DesktopEvent::data)
              .anyMatch(
                  event ->
                      "agent_started".equals(event.get("event_type"))
                          && ReasoningTraceBinding.agentTaskId("triage", "test-planner")
                              .equals(event.get("task_id"))));

      allowCompletion.countDown();
      session.task().get(10, TimeUnit.SECONDS);
      assertTrue(session.eventsAfter(0).stream().anyMatch(event -> "terminal".equals(event.event())));
    } finally {
      allowCompletion.countDown();
    }
  }

  @Test
  void unverifiedMathematicsCompletesTheDesktopExecutionLifecycle() throws Exception {
    RunExecutionBackend backend =
        (request, runId, traceId, runDirectory, progress) ->
            new RunExecutionBackend.RunExecutionResult(
                "unverified",
                "report",
                "No route passed",
                List.of(),
                List.of(),
                "report",
                2,
                new ExecutionUsage(100L, 50L, new BigDecimal("0.125"), 250.0d));
    DesktopPaths paths = DesktopTestSupport.paths(temporaryDirectory.resolve("unverified-data"));

    try (DesktopRunManager manager = DesktopTestSupport.manager(paths, backend)) {
      manager.start(
          new StartRunRequest("Prove that 1 + 1 = 3.", "smoke", "desktop-unverified"));
      LiveRun session = manager.session("desktop-unverified");
      session.task().get(10, TimeUnit.SECONDS);

      assertEquals("completed", session.metadata().lifecycle());
      LiveRun.DesktopEvent result =
          session.eventsAfter(0).stream()
              .filter(event -> "result".equals(event.event()))
              .findFirst()
              .orElseThrow();
      assertEquals("unverified", result.data().get("math_status"));
      assertEquals("completed", result.data().get("execution_status"));
      assertEquals("completed", session.eventsAfter(0).getLast().data().get("reason"));
      Path persistedResult =
          paths
              .safeRunDirectory("desktop-unverified")
              .resolve("structured")
              .resolve("run_result.json");
      assertTrue(Files.isRegularFile(persistedResult));
      Map<String, Object> persisted = DesktopTestSupport.json(Files.readString(persistedResult));
      assertEquals(
          "unverified",
          persisted.get("status"));
      @SuppressWarnings("unchecked")
      Map<String, Object> usage = (Map<String, Object>) persisted.get("total_usage");
      assertEquals(150, ((Number) usage.get("total_tokens")).intValue());
      assertEquals(0, new BigDecimal(String.valueOf(usage.get("estimated_cost_usd"))).compareTo(new BigDecimal("0.125")));
    }
  }

  @Test
  void cancellationCannotBeOverwrittenByALateBackendFailure() throws Exception {
    CountDownLatch backendStarted = new CountDownLatch(1);
    CountDownLatch allowBackendReturn = new CountDownLatch(1);
    RunExecutionBackend backend =
        (request, runId, traceId, runDirectory, progress) -> {
          backendStarted.countDown();
          try {
            allowBackendReturn.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
          return new RunExecutionBackend.RunExecutionResult(
              "failed", "report", "ProviderException", List.of(), List.of(), "report", 0);
        };

    DesktopPaths paths = DesktopTestSupport.paths(temporaryDirectory.resolve("cancel-data"));
    try (DesktopRunManager manager = DesktopTestSupport.manager(paths, backend)) {
      manager.start(
          new StartRunRequest("Prove that 1 + 1 = 2.", "smoke", "desktop-cancel-race"));
      assertTrue(backendStarted.await(5, TimeUnit.SECONDS));

      manager.cancel("desktop-cancel-race");
      allowBackendReturn.countDown();
      Thread.sleep(300L);

      LiveRun session = manager.session("desktop-cancel-race");
      assertEquals("cancelled", session.metadata().lifecycle());
      List<LiveRun.DesktopEvent> terminals =
          session.eventsAfter(0).stream()
              .filter(event -> "terminal".equals(event.event()))
              .toList();
      assertEquals(1, terminals.size());
      assertEquals("cancelled", terminals.getFirst().data().get("reason"));
      assertFalse(
          session.eventsAfter(0).stream()
              .filter(event -> "state".equals(event.event()))
              .anyMatch(event -> "failed".equals(event.data().get("lifecycle"))));
    } finally {
      allowBackendReturn.countDown();
    }
  }

  @Test
  void progressEndpointStreamsEventsUntilTheRunTerminates() throws Exception {
    CountDownLatch providerStarted = new CountDownLatch(1);
    CountDownLatch allowCompletion = new CountDownLatch(1);
    RunExecutionBackend backend =
        (request, runId, traceId, runDirectory, progress) -> {
          progress.emit(
              "agent_started",
              "triage",
              "test-planner",
              "running",
              "Classifying the problem",
              null);
          providerStarted.countDown();
          try {
            allowCompletion.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
          return new RunExecutionBackend.RunExecutionResult(
              "completed", "report", "completed", List.of(), List.of(), "report", 1);
        };

    DesktopPaths paths = DesktopTestSupport.paths(temporaryDirectory.resolve("sse-data"));
    try (DesktopRunManager manager = DesktopTestSupport.manager(paths, backend)) {
      manager.start(
          new StartRunRequest("Prove that 1 + 1 = 2.", "smoke", "desktop-sse-live"));
      assertTrue(providerStarted.await(5, TimeUnit.SECONDS));
      CredentialVault vault = DesktopTestSupport.vault(paths);
      DesktopApiController controller =
          new DesktopApiController(
              paths,
              vault,
              new DesktopConfigService(paths, vault),
              new RunRepository(paths, DesktopTestSupport.MAPPER),
              manager);
      MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

      MvcResult stream =
          mockMvc
              .perform(get("/api/runs/desktop-sse-live/events"))
              .andExpect(request().asyncStarted())
              .andReturn();
      allowCompletion.countDown();
      stream.getAsyncResult(5_000L);

      mockMvc
          .perform(asyncDispatch(stream))
          .andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
          .andExpect(content().string(org.hamcrest.Matchers.containsString("event:connected")))
          .andExpect(content().string(org.hamcrest.Matchers.containsString("event:activity")))
          .andExpect(content().string(org.hamcrest.Matchers.containsString("event:terminal")));
    } finally {
      allowCompletion.countDown();
    }
  }
}
