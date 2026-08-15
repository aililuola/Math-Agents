package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.api.SolveRequest;
import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import io.github.aililuola.mathproofmesh.contract.BlindVerificationReport;
import io.github.aililuola.mathproofmesh.contract.ClaimBlindAdjudicationBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimBlindAdjudicationDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimBlindAdjudicationVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementFalsificationBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementFalsificationDecision;
import io.github.aililuola.mathproofmesh.contract.ComputationContractRepair;
import io.github.aililuola.mathproofmesh.contract.ComputationContractRepairAction;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.Difficulty;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import io.github.aililuola.mathproofmesh.contract.FailureLevel;
import io.github.aililuola.mathproofmesh.contract.FinalProof;
import io.github.aililuola.mathproofmesh.contract.InitialExplorationAction;
import io.github.aililuola.mathproofmesh.contract.InitialExplorationTurn;
import io.github.aililuola.mathproofmesh.contract.MetaReview;
import io.github.aililuola.mathproofmesh.contract.ProblemKind;
import io.github.aililuola.mathproofmesh.contract.ProofAttempt;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import io.github.aililuola.mathproofmesh.contract.Severity;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotProposal;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.contract.StatementFalsificationDisposition;
import io.github.aililuola.mathproofmesh.contract.TriageResult;
import io.github.aililuola.mathproofmesh.contract.ToolAuditReport;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import io.github.aililuola.mathproofmesh.contract.VerificationReport;
import io.github.aililuola.mathproofmesh.contract.VerificationIssue;
import io.github.aililuola.mathproofmesh.contract.VerificationStage;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import io.github.aililuola.mathproofmesh.provider.HttpTransport;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.provider.ProviderCircuitOpenError;
import io.github.aililuola.mathproofmesh.provider.ProviderException;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.FrozenClaimSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopLiveRunExecutionBackendTest {
  private static final String PROFILE = "scripted";
  private static final String RUN_ID = "desktop-live-scripted";

  @TempDir Path temporaryDirectory;

  @Test
  void scriptedExplorationFixtureSatisfiesTheStrictContract() {
    InitialExplorationTurn turn =
        new InitialExplorationTurn(
            InitialExplorationAction.SUBMIT_ATTEMPT,
            attempt(),
            null,
            null,
            "The proof attempt is ready for independent review.");
    assertDoesNotThrow(
        () ->
            ContractObjectMapper.read(
                ContractObjectMapper.write(turn), InitialExplorationTurn.class));
  }

  @Test
  void completesTheLivePipelineAndPersistsAuditedActivity() throws Exception {
    Path runDirectory = temporaryDirectory.resolve("completed-run");
    List<Map<String, Object>> progress = new ArrayList<>();
    RunExecutionBackend.RunExecutionResult result =
        backend(Mode.COMPLETED)
            .execute(
                new SolveRequest("Prove that 1 + 1 = 2.", RUN_ID, null, PROFILE),
                RUN_ID,
                "trace-completed",
                runDirectory,
                sink(progress));

    assertEquals(
        "completed",
        result.status(),
        () -> result.summary() + "\n" + result.reportBody() + "\n" + progress);
    assertEquals(3, result.routes().size());
    assertFalse(
        result.verifiedLocalClaimIds().isEmpty(),
        () -> result.summary() + "\n" + result.reportBody() + "\n" + progress);
    assertTrue(result.reportBody().contains("Final answer"));
    assertTrue(result.logicalSteps() >= 10);
    assertTrue(result.usage().totalTokens() > 0L);
    assertTrue(
        progress.stream()
            .anyMatch(
                event ->
                    "verification".equals(event.get("type"))
                        && "verified".equals(event.get("status"))));

    Path activity = runDirectory.resolve("activity.jsonl");
    assertTrue(Files.isRegularFile(activity));
    String archived = Files.readString(activity);
    assertTrue(archived.contains("agent:triage:ds-planner"));
    assertTrue(archived.contains("final_verification"));

    Path structured = runDirectory.resolve("structured");
    var state =
        ContractObjectMapper.parseTree(
            Files.readString(structured.resolve("desktop-solve-state.json")));
    assertTrue(state.path("terminal").asBoolean());
    assertEquals(DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION, state.path("schemaVersion").asInt());
    assertEquals(result.logicalSteps(), state.path("usageTotals").path("calls").asInt());
    assertTrue(state.path("finalValidationPassed").asBoolean());
    assertEquals("terminal", state.path("workflowCursor").asText());
    assertEquals(12, state.path("completedStages").size());
    assertFalse(
        state.path("completedStages").toString().contains("\"INSPIRATION\""),
        "a dependency-complete proof must not spend budget on an unnecessary inspiration round");
    assertEquals(3, state.path("routes").size());
    state.path("routes")
        .forEach(
            route -> {
              assertTrue(route.path("reviewComplete").asBoolean());
              assertTrue(route.path("checkpointProcessed").asBoolean());
              assertTrue(route.path("integrated").asBoolean());
              assertFalse(route.path("teamResult").isMissingNode());
              assertFalse(route.path("validationExecution").isMissingNode());
            });
    assertTrue(state.path("strategyBlueprints").size() >= 3);
    assertTrue(state.path("strategyArchive").path("lineage").size() >= 3);
    assertTrue(state.path("goalLinks").size() >= 3);
    assertTrue(state.path("finalReviewReports").size() >= 3);
    for (String projection :
        List.of(
            "proof-graph.json",
            "lemma-memory.json",
            "typed-memory.json",
            "message-store.json",
            "strategy-archive.json",
            "strategy-blueprints.json",
            "goal-links.json",
            "meta-pivots.json",
            "computation-audits.json",
            "formalization-coverage.json",
            "final-validation-execution.json",
            "final-review-reports.json")) {
      assertTrue(Files.isRegularFile(structured.resolve(projection)), projection);
    }
  }

  @Test
  void resumesFromThePersistedWorkflowCursorInsteadOfRestartingTheSolve() throws Exception {
    Path runDirectory = temporaryDirectory.resolve("resume-run");
    DesktopLiveRunExecutionBackend backend = backend(Mode.COMPLETED);
    SolveRequest request = new SolveRequest("Prove that 1 + 1 = 2.", RUN_ID, null, PROFILE);
    RunExecutionBackend.RunExecutionResult initial =
        backend.execute(request, RUN_ID, "trace-initial", runDirectory, sink(new ArrayList<>()));
    assertEquals("completed", initial.status());

    Path stateFile = runDirectory.resolve("structured").resolve("desktop-solve-state.json");
    ObjectNode interrupted =
        (ObjectNode) ContractObjectMapper.parseTree(Files.readString(stateFile));
    interrupted.put("terminal", false);
    interrupted.put("workflowCursor", "blind_final_review");
    interrupted.put("finalValidationPassed", false);
    Files.writeString(stateFile, ContractObjectMapper.write(interrupted));
    List<Map<String, Object>> resumedProgress = new ArrayList<>();

    RunExecutionBackend.RunExecutionResult resumed =
        backend.execute(request, RUN_ID, "trace-resume", runDirectory, sink(resumedProgress));

    assertEquals("completed", resumed.status());
    assertTrue(
        resumedProgress.stream()
            .anyMatch(event -> "checkpoint_resumed".equals(event.get("type"))));
    assertTrue(
        resumedProgress.stream()
            .noneMatch(event -> "triage".equals(event.get("stage"))));
    assertTrue(resumed.logicalSteps() >= initial.logicalSteps());
  }

  @Test
  void upgradesSchemaOneCheckpointsByRecoveringCommittedProviderUsage() throws Exception {
    Path runDirectory = temporaryDirectory.resolve("legacy-usage-run");
    DesktopLiveRunExecutionBackend backend = backend(Mode.COMPLETED);
    SolveRequest request = new SolveRequest("Prove that 1 + 1 = 2.", RUN_ID, null, PROFILE);
    RunExecutionBackend.RunExecutionResult initial =
        backend.execute(request, RUN_ID, "trace-legacy-initial", runDirectory, sink(new ArrayList<>()));

    Path stateFile = runDirectory.resolve("structured").resolve("desktop-solve-state.json");
    ObjectNode legacy = (ObjectNode) ContractObjectMapper.parseTree(Files.readString(stateFile));
    legacy.put("schemaVersion", 1);
    legacy.remove("usageTotals");
    Files.writeString(stateFile, ContractObjectMapper.write(legacy));

    RunExecutionBackend.RunExecutionResult resumed =
        backend.execute(request, RUN_ID, "trace-legacy-resume", runDirectory, sink(new ArrayList<>()));

    assertEquals(initial.logicalSteps(), resumed.logicalSteps());
    assertEquals(initial.usage(), resumed.usage());
    ObjectNode upgraded =
        (ObjectNode) ContractObjectMapper.parseTree(Files.readString(stateFile));
    assertEquals(DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION, upgraded.path("schemaVersion").asInt());
    assertEquals(initial.logicalSteps(), upgraded.path("usageTotals").path("calls").asLong());
  }

  @Test
  void recordsRejectedComputationAndReturnsAnUnverifiedReport() throws Exception {
    Path runDirectory = temporaryDirectory.resolve("unverified-run");
    List<Map<String, Object>> progress = new ArrayList<>();
    RunExecutionBackend.RunExecutionResult result =
        backend(Mode.REJECTED_COMPUTATION)
            .execute(
                new SolveRequest("Prove that 2 + 2 = 4.", RUN_ID, null, PROFILE),
                RUN_ID,
                "trace-unverified",
                runDirectory,
                sink(progress));

    assertEquals(
        "unverified",
        result.status(),
        () -> result.summary() + "\n" + result.reportBody() + "\n" + progress);
    assertTrue(result.verifiedLocalClaimIds().isEmpty());
    assertTrue(
        result.reportBody().contains("Bounded computation evidence"),
        () -> result.summary() + "\n" + result.reportBody() + "\n" + progress);
    assertTrue(
        result.reportBody().contains("No candidate reached the final verification gate"));
    assertTrue(
        progress.stream()
            .anyMatch(
                event ->
                    "computation".equals(event.get("type"))
                        && "rejected".equals(event.get("status"))));
    assertTrue(Files.readString(runDirectory.resolve("activity.jsonl")).contains("computation"));
  }

  @Test
  void repairsAnInvalidComputationContractBeforeExecutingIt() throws Exception {
    Path runDirectory = temporaryDirectory.resolve("repaired-computation-run");
    List<Map<String, Object>> progress = new ArrayList<>();
    RunExecutionBackend.RunExecutionResult result =
        backend(Mode.REPAIRED_COMPUTATION)
            .execute(
                new SolveRequest("Prove that n = n for every integer n.", RUN_ID, null, PROFILE),
                RUN_ID,
                "trace-repaired-computation",
                runDirectory,
                sink(progress));

    assertEquals(
        "completed",
        result.status(),
        () -> result.summary() + "\n" + result.reportBody() + "\n" + progress);
    assertTrue(
        progress.stream()
            .anyMatch(
                event ->
                    "computation_contract_repaired".equals(event.get("type"))
                        && "completed".equals(event.get("status"))),
        () -> progress.toString());
    assertTrue(
        progress.stream()
            .anyMatch(
                event ->
                    "computation".equals(event.get("type"))
                        && "completed".equals(event.get("status"))));
    assertTrue(
        Files.readString(runDirectory.resolve("activity.jsonl"))
            .contains("computation_contract_repaired"));
  }

  @Test
  void recordsAReleaseSafeFailureWhenRuntimePreparationStops() throws Exception {
    Path runDirectory = temporaryDirectory.resolve("preparation-failure");
    List<Map<String, Object>> progress = new ArrayList<>();
    DesktopRuntimeLocator locator = new DesktopRuntimeLocator(projectRoot(), null);
    DesktopLiveRuntimeFactory runtimes = mock(DesktopLiveRuntimeFactory.class);
    when(runtimes.prepare(eq(PROFILE), any(DesktopSettings.class)))
        .thenThrow(new IllegalStateException("sensitive diagnostic"));
    DesktopPaths paths = DesktopTestSupport.paths(temporaryDirectory.resolve("failed-data"));
    DesktopLiveRunExecutionBackend backend =
        new DesktopLiveRunExecutionBackend(
            paths,
            new SettingsStore(paths.settingsFile(), DesktopTestSupport.MAPPER),
            runtimes,
            locator,
            new DockerSandboxPreflight());

    RunExecutionBackend.RunExecutionResult result =
        backend.execute(
            new SolveRequest("Prove the target.", RUN_ID, null, PROFILE),
            RUN_ID,
            "trace-failed",
            runDirectory,
            sink(progress));

    assertEquals("failed", result.status());
    assertTrue(result.reportBody().contains("Profile: `unavailable`"));
    assertFalse(result.reportBody().contains("sensitive diagnostic"));
    assertTrue(progress.stream().anyMatch(event -> "run_failed".equals(event.get("type"))));
    assertTrue(Files.readString(runDirectory.resolve("activity.jsonl")).contains("run_failed"));
  }

  @Test
  void exposesOnlyWhitelistedOperationalFailureDetails() {
    assertEquals(
        "Docker sandbox preflight timed out",
        DesktopLiveRunExecutionBackend.safeFailureDetail(
            new IllegalStateException("Docker sandbox preflight timed out")));
    assertEquals(
        "",
        DesktopLiveRunExecutionBackend.safeFailureDetail(
            new IllegalStateException("sensitive diagnostic")));
  }

  @Test
  void exposesOnlyAProjectOwnedFailureLocation() {
    NullPointerException failure = new NullPointerException("sensitive diagnostic");
    failure.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement("java.util.Objects", "requireNonNull", "Objects.java", 233),
          new StackTraceElement(
              "io.github.aililuola.mathproofmesh.desktop.DesktopSolveCoordinator",
              "schedulePendingProofTask",
              "DesktopSolveCoordinator.java",
              3390)
        });

    assertEquals(
        "DesktopSolveCoordinator#schedulePendingProofTask:3390",
        DesktopLiveRunExecutionBackend.safeFailureLocation(failure));
    IllegalStateException outside = new IllegalStateException("outside project");
    outside.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement("java.util.Objects", "requireNonNull", "Objects.java", 233)
        });
    assertEquals("", DesktopLiveRunExecutionBackend.safeFailureLocation(outside));
  }

  @Test
  void reportsCircuitStatusWithoutProviderOrCredentialDetails() {
    ProviderCircuitOpenError failure =
        new ProviderCircuitOpenError(
            "https://provider.example/secret-scope",
            List.of("credential-backed-agent"),
            Duration.ofSeconds(299),
            ProviderException.http(402, null));

    String detail = DesktopLiveRunExecutionBackend.safeFailureDetail(failure);

    assertEquals(
        "provider circuit open; retry_after_seconds=299; "
            + "provider_error=NON_RETRYABLE_HTTP; http_status=402",
        detail);
    assertFalse(detail.contains("provider.example"));
    assertFalse(detail.contains("credential-backed-agent"));
  }

  @Test
  void reportsProviderCancellationAsCancellationInsteadOfFailure() throws Exception {
    Path runDirectory = temporaryDirectory.resolve("cancelled-run");
    List<Map<String, Object>> progress = new ArrayList<>();
    DesktopRuntimeLocator locator = new DesktopRuntimeLocator(projectRoot(), null);
    DesktopLiveRuntimeFactory runtimes = mock(DesktopLiveRuntimeFactory.class);
    when(runtimes.prepare(eq(PROFILE), any(DesktopSettings.class)))
        .thenThrow(ProviderException.cancelled());
    DesktopPaths paths = DesktopTestSupport.paths(temporaryDirectory.resolve("cancelled-data"));
    DesktopLiveRunExecutionBackend backend =
        new DesktopLiveRunExecutionBackend(
            paths,
            new SettingsStore(paths.settingsFile(), DesktopTestSupport.MAPPER),
            runtimes,
            locator,
            new DockerSandboxPreflight());

    RunExecutionBackend.RunExecutionResult result =
        backend.execute(
            new SolveRequest("Prove the target.", RUN_ID, null, PROFILE),
            RUN_ID,
            "trace-cancelled",
            runDirectory,
            sink(progress));

    assertEquals("cancelled", result.status());
    assertEquals("Live solve cancelled", result.summary());
    assertFalse(result.reportBody().contains("ProviderException"));
    assertTrue(progress.stream().anyMatch(event -> "run_cancelled".equals(event.get("type"))));
    assertTrue(Files.readString(runDirectory.resolve("activity.jsonl")).contains("run_cancelled"));
  }

  @Test
  void truncatesOversizedReportsAtAValidUtf8Boundary() {
    String oversized = "\u6570".repeat(200);

    String bounded = DesktopLiveRunExecutionBackend.boundedUtf8(oversized, 256);

    assertTrue(bounded.endsWith("[REPORT TRUNCATED]\n"));
    assertTrue(bounded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 256);
    assertTrue(oversized.startsWith(bounded.substring(0, bounded.indexOf('\n'))));
  }

  @Test
  void appliesTheMigratedPerStageThinkingPolicy() {
    SystemConfig config =
        new DesktopRuntimeLocator(projectRoot(), null)
            .loadProfile("proof-control-active.yaml");

    var triage = DesktopSolveCoordinator.stageThinkingPolicy(config, "triage", 12_000);
    var strategy =
        DesktopSolveCoordinator.stageThinkingPolicy(config, "strategy_generation", 384_000);
    var routineRoute =
        DesktopSolveCoordinator.stageThinkingPolicy(config, "route_prove", 64_000);
    var deepRoute =
        DesktopSolveCoordinator.stageThinkingPolicy(config, "route_prove", 128_000);

    assertEquals(Boolean.FALSE, triage.enabled());
    assertEquals(null, triage.effort());
    assertEquals(Boolean.TRUE, strategy.enabled());
    assertEquals("max", strategy.effort());
    assertEquals("high", routineRoute.effort());
    assertEquals("max", deepRoute.effort());
  }

  private DesktopLiveRunExecutionBackend backend(Mode mode) {
    Path project = projectRoot();
    DesktopRuntimeLocator locator = new DesktopRuntimeLocator(project, null);
    SystemConfig config = mockConfig(locator.loadProfile("proof-control-active.yaml"));
    DesktopLiveRuntimeFactory.PreparedRuntime prepared =
        new DesktopLiveRuntimeFactory.PreparedRuntime(PROFILE, config, Map.of(), false);
    DesktopLiveRuntimeFactory runtimes = mock(DesktopLiveRuntimeFactory.class);
    when(runtimes.prepare(eq(PROFILE), any(DesktopSettings.class))).thenReturn(prepared);
    when(runtimes.openProviders(prepared)).thenAnswer(ignored -> providers(config, mode));

    DesktopPaths paths = DesktopTestSupport.paths(temporaryDirectory.resolve("desktop-data"));
    SettingsStore settings = new SettingsStore(paths.settingsFile(), DesktopTestSupport.MAPPER);
    return new DesktopLiveRunExecutionBackend(
        paths, settings, runtimes, locator, new DockerSandboxPreflight());
  }

  private static RunExecutionBackend.ProgressSink sink(List<Map<String, Object>> events) {
    return (type, stage, agentId, status, summary, reference) -> {
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("type", type);
      event.put("stage", stage);
      event.put("agent_id", agentId);
      event.put("status", status);
      event.put("summary", summary);
      event.put("reference", reference);
      events.add(event);
    };
  }

  private static ProviderClientRegistry providers(SystemConfig config, Mode mode) {
    MockResponder responder = request -> response(request, mode);
    Map<String, MockResponder> responders = new LinkedHashMap<>();
    config.agents().forEach(agent -> responders.put(agent.id(), responder));
    HttpTransport unusedTransport = request -> {
      throw new AssertionError("mock provider attempted network access");
    };
    return new ProviderClientRegistry(
        config, responders, ignored -> unusedTransport, false, ignored -> null);
  }

  private static LLMResponse response(ProviderRequest request, Mode mode) {
    String user = request.messages().getLast().content();
    Object payload =
        switch (request.schemaName()) {
          case "TriageResult" -> triage();
          case "StrategySet" -> strategies();
          case "InitialExplorationTurn" -> exploration(user, mode);
          case "ComputationContractRepair" -> computationRepair(mode);
          case "ClaimStatementFalsificationBatch" -> claimStatementFalsification(user);
          case "ClaimProofAuditBatch" -> claimProofAudit(user);
          case "ClaimBlindAdjudicationBatch" -> claimBlindAdjudication(user);
          case "ClaimReviewBatch" -> claimReview(user);
          case "VerificationReport" -> review(user, mode);
          case "BlindVerificationReport" -> blindReview(user, mode);
          case "ToolAuditReport" -> toolAudit(mode);
          case "FinalProof" -> finalProof();
          case "StrategyCard" -> inspirationStrategy(user);
          case "SemanticPivotProposal" -> textOnlySemanticPivot(user);
          case "MetaReview" -> metaReview(mode);
          default -> throw new AssertionError("unexpected response schema: " + request.schemaName());
        };
    return new LLMResponse(
        ContractObjectMapper.write(payload),
        "scripted-model",
        "mock",
        7,
        11,
        0.5d,
        "scripted-request",
        "stop",
        false,
        JsonNodeFactory.instance.objectNode());
  }

  private static ClaimReviewBatch claimReview(String prompt) {
    JsonNode context = sanitizedContext(prompt);
    List<ClaimReviewDecision> decisions = new ArrayList<>();
    for (JsonNode artifact : context.path("candidate_artifacts")) {
      decisions.add(
          new ClaimReviewDecision(
              artifact.path("claimId").asText(),
              VerificationVerdict.PASS,
              0.99d,
              List.of(),
              true,
              true,
              true,
              true,
              "COUNTEREXAMPLE".equals(artifact.path("kind").asText()),
              List.of(),
              "scripted claim-scoped review passed"));
    }
    return new ClaimReviewBatch(
        null,
        "scripted-claim-reviewer",
        context.path("route_id").asText(),
        context.path("attempt_id").asText(),
        decisions,
        null,
        new UsageRecord());
  }

  private static ClaimStatementFalsificationBatch claimStatementFalsification(String prompt) {
    FrozenClaimSnapshot frozen =
        ContractObjectMapper.read(
            sanitizedContext(prompt).path("frozen_claim"), FrozenClaimSnapshot.class);
    return new ClaimStatementFalsificationBatch(
        "statement-falsification-" + frozen.claimId(),
        "scripted-falsifier",
        frozen.sourceRouteId(),
        frozen.sourceAttemptId(),
        List.of(
            new ClaimStatementFalsificationDecision(
                frozen.claimId(),
                StatementFalsificationDisposition.NO_COUNTEREXAMPLE_FOUND,
                List.of(),
                "No counterexample was found for the frozen statement.")),
        "artifact://statement-falsification/" + frozen.claimId(),
        new UsageRecord());
  }

  private static ClaimProofAuditBatch claimProofAudit(String prompt) {
    FrozenClaimSnapshot frozen =
        ContractObjectMapper.read(
            sanitizedContext(prompt).path("frozen_claim"), FrozenClaimSnapshot.class);
    return new ClaimProofAuditBatch(
        "proof-audit-" + frozen.claimId(),
        "scripted-proof-auditor",
        frozen.sourceRouteId(),
        frozen.sourceAttemptId(),
        List.of(
            new ClaimProofAuditDecision(
                frozen.claimId(),
                ClaimProofAuditVerdict.VALID,
                List.of(),
                "The bounded proof steps establish the frozen statement.")),
        "artifact://proof-audit/" + frozen.claimId(),
        new UsageRecord());
  }

  private static ClaimBlindAdjudicationBatch claimBlindAdjudication(String prompt) {
    JsonNode packet = sanitizedContext(prompt).path("blind_claim_packet");
    String claimId = packet.path("claimId").asText();
    return new ClaimBlindAdjudicationBatch(
        "blind-adjudication-" + claimId,
        "scripted-blind-adjudicator",
        "scripted-route",
        "scripted-attempt",
        List.of(
            new ClaimBlindAdjudicationDecision(
                claimId,
                ClaimBlindAdjudicationVerdict.PASS,
                List.of(),
                "The identity-scrubbed proof independently passes.")),
        "artifact://blind-adjudication/" + claimId,
        new UsageRecord());
  }

  private static JsonNode sanitizedContext(String prompt) {
    String startMarker = "SANITIZED CONTEXT:\n";
    String endMarker = "\n\nOUTPUT LANGUAGE:";
    int start = prompt.indexOf(startMarker);
    if (start < 0) {
      throw new AssertionError("structured prompt has no sanitized context");
    }
    start += startMarker.length();
    int end = prompt.indexOf(endMarker, start);
    if (end <= start) {
      throw new AssertionError("structured prompt has no context terminator");
    }
    return ContractObjectMapper.parseTree(prompt.substring(start, end));
  }

  private static TriageResult triage() {
    return new TriageResult(
        0.95d,
        Difficulty.EASY,
        List.of("check arithmetic definitions"),
        List.of(),
        ProblemKind.PROOF,
        "direct",
        "A direct derivation is sufficient.",
        null,
        3,
        1,
        List.of());
  }

  private static StrategySet strategies() {
    return new StrategySet(
        "Three isolated direct routes cover the target.",
        List.of(),
        List.of(strategy("strategy-a"), strategy("strategy-b"), strategy("strategy-c")));
  }

  private static StrategyCard strategy(String id) {
    String mechanism =
        switch (id) {
          case "strategy-a" -> "Expand addition from the Peano recursive definition.";
          case "strategy-b" -> "Construct the two-element disjoint union and count it.";
          default -> "Assume the equality fails and contradict successor injectivity.";
        };
    int stepCount = "strategy-a".equals(id) ? 1 : "strategy-b".equals(id) ? 2 : 3;
    return DesktopStrategyMetadataTestSupport.complete(
        new StrategyCard(
            null,
            "Establish the exact target using " + mechanism,
            List.of(),
            List.of(),
            List.of(),
            mechanism,
            List.of(),
            0.1d,
            0.95d,
            java.util.stream.IntStream.range(0, stepCount)
                .mapToObj(index -> "Explicit route step " + index + " for " + id + '.')
                .toList(),
            "Try the smallest admissible model against this mechanism.",
            "This route has a distinct proof mechanism and falsification test.",
            null,
            null,
            List.of(),
            List.of(),
            id,
            List.of(id),
            "Independent route " + id));
  }

  private static StrategyCard inspirationStrategy(String prompt) {
    String mechanism =
        prompt.contains("reverse_goal_analysis")
            ? "Work backward from the exact conclusion while keeping forward facts separate."
            : "Switch to a reversible structural representation and preserve every obligation.";
    return DesktopStrategyMetadataTestSupport.complete(
        new StrategyCard(
        null,
        "Test one new bridge for the still-open main obligation.",
        List.of(),
        List.of(),
        List.of(),
        mechanism,
        List.of(),
        0.2d,
        0.55d,
        List.of(),
        "Try to refute the bridge in the smallest model.",
        "The proposal is structurally independent of the admitted direct routes.",
        null,
        null,
        List.of(),
        List.of(),
        "inspired-strategy",
            List.of("inspiration"),
            "Scripted inspiration proposal"));
  }

  private static SemanticPivotProposal textOnlySemanticPivot(String prompt) {
    return new SemanticPivotProposal(
        null,
        "scripted-proposer",
        promptField(prompt, "problem_hash"),
        promptField(prompt, "source_statement_hash"),
        promptField(prompt, "route_id"),
        promptField(prompt, "strategy_id"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        inspirationStrategy(prompt),
        "The scripted fixture proposes only prose and must be rejected as a no-op.",
        null,
        null);
  }

  private static String promptField(String prompt, String field) {
    Matcher matcher =
        Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .matcher(prompt);
    if (!matcher.find()) {
      throw new AssertionError("missing semantic pivot prompt field: " + field);
    }
    return matcher.group(1);
  }

  private static MetaReview metaReview(Mode mode) {
    return new MetaReview(
        List.of(),
        List.of(),
        successful(mode),
        0.95d,
        FailureLevel.NONE,
        successful(mode) ? List.of() : List.of("revise a failed route"),
        null,
        List.of(),
        "Scripted meta review of committed public state.",
        List.of());
  }

  private static InitialExplorationTurn exploration(String user, Mode mode) {
    boolean continuation = !user.contains("\"computation_decision\":\"none\"");
    boolean firstRoute = user.contains("route-1");
    if ((mode == Mode.REJECTED_COMPUTATION || mode == Mode.REPAIRED_COMPUTATION)
        && firstRoute
        && !continuation) {
      return new InitialExplorationTurn(
          InitialExplorationAction.REQUEST_COMPUTATION,
          null,
          null,
          mode == Mode.REPAIRED_COMPUTATION
              ? malformedSandboxExperiment()
              : sandboxExperiment(),
          "Check a bounded custom case before submitting the attempt.");
    }
    if (mode == Mode.REJECTED_COMPUTATION && user.contains("route-2")) {
      return new InitialExplorationTurn(
          InitialExplorationAction.ABANDON,
          null,
          FailureLevel.STRATEGY,
          null,
          "This isolated route intentionally stops.");
    }
    return new InitialExplorationTurn(
        InitialExplorationAction.SUBMIT_ATTEMPT,
        attempt(),
        null,
        null,
        "The proof attempt is ready for independent review.");
  }

  private static ExperimentSpec sandboxExperiment() {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    arguments.putObject("input").put("seed", 17);
    return new ExperimentSpec(
        arguments,
        List.of("integer arithmetic"),
        false,
        "Keep the route if the bounded check agrees.",
        "Abandon the route if a counterexample appears.",
        JsonNodeFactory.instance.objectNode(),
        true,
        null,
        "experiment-route-1",
        32,
        ComputationMethod.SANDBOXED_PYTHON,
        "Verify the same cases by hand.",
        null,
        null,
        ComputationPurpose.FALSIFY_CLAIM,
        "A bounded check can falsify the proposed intermediate claim.",
        null,
        null,
        JsonNodeFactory.instance.objectNode(),
        17,
        "For every tested integer, n equals itself.",
        "No registered typed tool models the custom output shape.",
        "A counterexample would invalidate this route immediately.");
  }

  private static ExperimentSpec malformedSandboxExperiment() {
    ExperimentSpec source = sandboxExperiment();
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    arguments.put("input", "n from 0 through 31");
    return copyExperiment(
        source,
        ComputationMethod.SANDBOXED_PYTHON,
        arguments,
        JsonNodeFactory.instance.objectNode(),
        true,
        source.typedToolGap());
  }

  private static ComputationContractRepair computationRepair(Mode mode) {
    if (mode == Mode.REJECTED_COMPUTATION) {
      return new ComputationContractRepair(
          ComputationContractRepairAction.ABANDON_AS_UNREPRESENTABLE,
          "No enabled typed method represents the requested custom sandbox operation.",
          null,
          null);
    }
    ExperimentSpec original = malformedSandboxExperiment();
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    arguments
        .putObject("target")
        .put("lhs", "n - n")
        .put("rhs", "0")
        .put("relation", "ne");
    arguments.putArray("constraints");
    ObjectNode domains = JsonNodeFactory.instance.objectNode();
    domains.putObject("n").put("min", 0).put("max", 31);
    ExperimentSpec repaired =
        copyExperiment(
            original,
            ComputationMethod.BOUNDED_INTEGER_SEARCH,
            arguments,
            domains,
            true,
            null);
    return new ComputationContractRepair(
        ComputationContractRepairAction.RETRY_WITH_REPAIRED_SPEC,
        "The typed finite search represents the same bounded falsification request.",
        repaired,
        "Both requests search the same integers for a violation of n = n.");
  }

  private static ExperimentSpec copyExperiment(
      ExperimentSpec source,
      ComputationMethod method,
      ObjectNode arguments,
      ObjectNode domains,
      boolean exactArithmetic,
      String typedToolGap) {
    return new ExperimentSpec(
        arguments,
        source.assumptions(),
        source.broadSearch(),
        source.decisionIfConfirmed(),
        source.decisionIfRefuted(),
        domains,
        exactArithmetic,
        null,
        source.experimentId(),
        source.maxCases(),
        method,
        source.noncomputationalAlternative(),
        source.parentCheckpointId(),
        source.pathId(),
        source.purpose(),
        source.reasoningBasis(),
        null,
        source.requestedBy(),
        JsonNodeFactory.instance.objectNode(),
        source.seed(),
        source.targetClaim(),
        typedToolGap,
        source.whyComputationIsNeeded());
  }

  private static ProofAttempt attempt() {
    return new ProofAttempt(
        "scripted-author",
        "scripted-attempt",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of("checked the defining equality"),
        "The target equality follows.",
        null,
        null,
        "placeholder-problem-hash",
        "Apply the definition and simplify.",
        List.of(step()),
        List.of(),
        null,
        null,
        0,
        1,
        0.95d,
        AttemptStatus.COMPLETE,
        "scripted-strategy",
        List.of(),
        new UsageRecord());
  }

  private static ProofStep step() {
    return new ProofStep(
        null,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0.99d,
        List.of(),
        List.of(),
        true,
        "This is the defining arithmetic reduction.",
        "The target reduces to an elementary identity.",
        "step-1",
        "derivation");
  }

  private static VerificationReport review(String user, Mode mode) {
    boolean finalStage = user.contains("final_proof");
    boolean passed = successful(mode);
    List<VerificationIssue> issues =
        passed
            ? List.of()
            : List.of(
                new VerificationIssue(
                    null,
                    "The conclusion is not independently established.",
                    null,
                    "The route does not justify its main reduction.",
                    "missing-justification",
                    "scripted-issue",
                    "detailed_verification",
                    "The target equality is assumed.",
                    "Supply the missing derivation.",
                    Severity.ERROR,
                    "step-1"));
    return new VerificationReport(
        "scripted-reviewer",
        List.of("step-1"),
        passed ? "The argument is complete." : "The route remains insufficiently justified.",
        passed ? 0.99d : 0.4d,
        passed ? FailureLevel.NONE : FailureLevel.STRATEGY,
        passed ? null : "step-1",
        issues,
        true,
        null,
        "scripted-review",
        finalStage ? VerificationStage.FINAL : VerificationStage.DETAILED,
        List.of(),
        finalStage ? "final-proof" : "scripted-attempt",
        finalStage ? "final_proof" : "attempt",
        List.of(),
        List.of(),
        new UsageRecord(),
        passed ? VerificationVerdict.PASS : VerificationVerdict.FAIL);
  }

  private static BlindVerificationReport blindReview(String user, Mode mode) {
    VerificationReport source = review(user, mode);
    return new BlindVerificationReport(
        source.checkedDependencies(),
        source.conciseFeedback(),
        source.confidence(),
        source.failureLevel(),
        source.firstErrorStep(),
        source.issues(),
        source.problemIntegrityOk(),
        source.structuredIssues(),
        source.toolRequests(),
        source.toolResults(),
        source.verdict());
  }

  private static ToolAuditReport toolAudit(Mode mode) {
    boolean passed = successful(mode);
    return new ToolAuditReport(
        "scripted-tool-specialist",
        passed,
        passed ? 0.99d : 0.4d,
        List.of("scripted-experiment"),
        passed ? List.of() : List.of("No admitted computation was available for replay."),
        true,
        passed ? List.of("scripted-replay") : List.of(),
        "scripted-route",
        passed ? "pass" : "fail");
  }

  private static FinalProof finalProof() {
    return new FinalProof(
        "The equality follows from the standard definition of addition.",
        List.of("All quantities are integers."),
        0.99d,
        List.of(),
        "placeholder-problem-hash",
        List.of(step()),
        List.of("scripted-attempt"));
  }

  private static SystemConfig mockConfig(SystemConfig source) {
    List<AgentConfig> agents = source.agents().stream().map(DesktopLiveRunExecutionBackendTest::mockAgent).toList();
    return new SystemConfig(
        source.systemName(),
        agents,
        source.budget(),
        source.scheduler(),
        source.topology(),
        source.verification(),
        source.continuation(),
        source.deepExplorationPolicy(),
        source.computation().withSandboxedPythonEnabled(false),
        source.runtime());
  }

  private static AgentConfig mockAgent(AgentConfig source) {
    return new AgentConfig(
        source.id(),
        "mock",
        "scripted-model",
        null,
        null,
        null,
        source.roles(),
        source.specialties(),
        source.maxConcurrency(),
        source.requestsPerMinute(),
        source.temperature(),
        source.maxOutputTokens(),
        source.providerMaxOutputTokens(),
        source.timeoutSeconds(),
        source.trustPrior(),
        source.enabled(),
        source.pricing(),
        Map.of(),
        null,
        source.thinkingEnabled(),
        source.reasoningEffort(),
        false,
        null);
  }

  private static boolean successful(Mode mode) {
    return mode != Mode.REJECTED_COMPUTATION;
  }

  private static Path projectRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("config/proof-control-active.yaml"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("project root was not found");
  }

  private enum Mode {
    COMPLETED,
    REPAIRED_COMPUTATION,
    REJECTED_COMPUTATION
  }
}
