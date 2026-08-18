package io.github.aililuola.mathproofmesh.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.api.RunApiModels.RouteView;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunExecutionBackendTest {
  @TempDir Path temporaryDirectory;

  @Test
  void executionUsageRejectsAnOverflowingTotalTokenCount() {
    RunExecutionBackend.ExecutionUsage usage =
        new RunExecutionBackend.ExecutionUsage(1L, 2L, BigDecimal.ZERO, 0.0d);

    assertEquals(3L, usage.totalTokens());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunExecutionBackend.ExecutionUsage(
                Long.MAX_VALUE, 1L, BigDecimal.ZERO, 0.0d));
  }

  @Test
  void solveUsesConfiguredBackendWhileDemoRemainsExplicitlyProviderFree() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<String> profile = new AtomicReference<>();
    RunExecutionBackend backend =
        (request, runId, traceId, runDirectory, progress) -> {
          calls.incrementAndGet();
          profile.set(request.profile());
          progress.emit(
              "agent_completed",
              "final_verification",
              "real-verifier",
              "unverified",
              "Independent review did not pass",
              null);
          return new RunExecutionBackend.RunExecutionResult(
              "unverified",
              "report",
              "Candidate remains unverified.",
              List.of(
                  new RouteView(
                      "route-live", "unverified", "A real route was reviewed", List.of())),
              List.of(),
              "### Candidate\n\nNo verified result was claimed.",
              3);
        };
    Path runRoot = temporaryDirectory.resolve("runs");
    RunApiService service =
        new RunApiService(
            new ApiObservability(new SimpleMeterRegistry()),
            runRoot.toString(),
            2,
            backend);

    RunApiModels.RunView result =
        service.solve(
            new SolveRequest("Prove the claim.", "live-run", null, "proof_control_active"));

    assertEquals("unverified", result.status());
    assertEquals("proof_control_active", profile.get());
    assertEquals(1, calls.get());
    assertTrue(
        Files.readString(
                runRoot.resolve("live-run/reports/run_report.md"), StandardCharsets.UTF_8)
            .contains("No verified result was claimed"));
    assertEquals("unverified", service.proofGraph("live-run").nodes().getFirst().get("status"));

    Path structured = runRoot.resolve("live-run/structured");
    Files.createDirectories(structured);
    ProofObligation actualGoal =
        new ProofObligation(
            List.of(),
            1.0d,
            "",
            List.of(),
            List.of(),
            List.of(),
            null,
            ObligationKind.MAIN_GOAL,
            "prove the claim",
            "actual-goal",
            1.0d,
            "0".repeat(64),
            List.of(),
            List.of("route-live"),
            "Prove the claim.",
            "open");
    ProofGraphSnapshot snapshot =
        new ProofGraphSnapshot(
            "0".repeat(64),
            false,
            Map.of(actualGoal.obligationId(), actualGoal),
            Map.of(),
            Map.of(),
            Map.of(),
            Set.of(),
            Map.of(actualGoal.obligationId(), 0L),
            List.of());
    Files.writeString(
        structured.resolve("proof-graph.json"),
        ContractObjectMapper.write(snapshot),
        StandardCharsets.UTF_8);
    assertTrue(
        service.proofGraph("live-run").nodes().stream()
            .anyMatch(node -> "actual-goal".equals(node.get("id"))));

    List<RunApiModels.ApiEvent> resumedEvents = new ArrayList<>();
    RunApiModels.RunView resumed =
        service.resume(new ResumeRequest("live-run"), resumedEvents::add);
    assertEquals("unverified", resumed.status());
    assertEquals(2, calls.get());
    assertTrue(
        resumedEvents.stream().anyMatch(event -> "agent_completed".equals(event.type())));

    assertEquals("unverified", service.demo("demo-provider-free").status());
    assertEquals(2, calls.get());
  }

  @Test
  void detailedBackendProgressCannotAbortThePublicRunStream() {
    RunExecutionBackend backend =
        (request, runId, traceId, runDirectory, progress) -> {
          progress.emit(
              "stage_started",
              "freeze_problem",
              null,
              "running",
              "Freezing the submitted problem",
              null);
          progress.emit(
              "problem_frozen",
              "freeze_problem",
              null,
              "completed",
              "Problem identity frozen",
              null);
          progress.emit(
              "stage_completed",
              "freeze_problem",
              null,
              "completed",
              "Problem freeze completed",
              null);
          return new RunExecutionBackend.RunExecutionResult(
              "completed", "report", "completed", List.of(), List.of(), "report", 0);
        };
    RunApiService service =
        new RunApiService(
            new ApiObservability(new SimpleMeterRegistry()),
            temporaryDirectory.resolve("semantic-events").toString(),
            1,
            backend);

    RunApiModels.RunView result =
        service.solve(new SolveRequest("Prove the claim.", "semantic-run", null, "active"));

    assertEquals("unverified", result.status());
    assertEquals(
        3,
        service.eventsAfter("semantic-run", 0).stream()
            .filter(event -> "stage_changed".equals(event.type()))
            .count());
  }
}
