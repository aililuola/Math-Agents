package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.api.SolveRequest;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceStore;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadBenchmarkHarness;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadProblemCatalog;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadPromptPolicy;
import io.github.aililuola.mathproofmesh.provider.HttpTransport;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class DesktopOlympiadBenchmarkProductionPathTest {
  private static final String RUN_ID = "benchmark-production-path";
  private static final String PROBLEM = "Prove that 1 + 1 = 2.";
  private static final OlympiadProblemCatalog.ProblemPrompt EXPECTED_PROBLEM =
      new OlympiadProblemCatalog.ProblemPrompt(
          "P01", PROBLEM, OlympiadProblemCatalog.sha256(PROBLEM));

  @TempDir Path temporaryDirectory;

  @Test
  void exportsSanitizedEvidenceWithoutMutatingTheAuthoritativeCheckpoint() throws Exception {
    InMemoryProviderCallRepository calls = new InMemoryProviderCallRepository();
    Fixture fixture = fixture(calls, DesktopDurableBoundaryObserver.none());
    Path runDirectory = temporaryDirectory.resolve("export-run");
    RunExecutionBackend.RunExecutionResult result =
        fixture.backend().execute(request(), RUN_ID, "trace-export", runDirectory, sink());
    Path checkpoint = runDirectory.resolve("structured/desktop-solve-state.json");
    byte[] before = Files.readAllBytes(checkpoint);

    OlympiadBenchmarkHarness.RunOutcome exported =
        DesktopOlympiadEvidenceExporter.export(
            runDirectory,
            RUN_ID,
            OlympiadProblemCatalog.sha256(PROBLEM),
            result,
            calls,
            new OlympiadBenchmarkHarness.RecoveryEvidence(0, 0, 0, "not-applicable", "not-applicable"),
            Instant.parse("2026-08-23T00:00:00Z"));
    var checkpointState = ContractObjectMapper.parseTree(Files.readString(checkpoint));

    assertArrayEquals(before, Files.readAllBytes(checkpoint));
    assertEquals(
        0,
        exported.hardViolationCount(),
        () ->
            "issues="
                + exported.issueObservations()
                + "; resultUsage="
                + result.usage()
                + "; checkpointUsage="
                + checkpointState.path("usageTotals")
                + "; checkpointCommitted="
                + checkpointState.path("budgetUsage").path("committed"));
    assertEquals(exported.rootGoalHashInitial(), exported.rootGoalHashFinal());
    assertTrue(exported.evidenceDocuments().containsKey("provider-usage.ndjson"));
    assertTrue(exported.evidenceDocuments().containsKey("proof-graph.json"));
    assertTrue(exported.evidenceDocuments().containsKey("budget-usage.json"));
  }

  @Test
  void stopsAtMergePreparedAndResumesWithoutReplayingDurableResearchResults() throws Exception {
    InMemoryProviderCallRepository calls = new InMemoryProviderCallRepository();
    AtomicInteger stops = new AtomicInteger();
    DesktopDurableBoundaryObserver stopOnce =
        (boundary, checkpoint) -> {
          if (boundary == DesktopDurableBoundary.MERGE_PREPARED
              && stops.getAndIncrement() == 0) {
            throw new ControlledStop();
          }
        };
    Path runDirectory = temporaryDirectory.resolve("resume-run");

    assertThrows(
        ControlledStop.class,
        () ->
            fixture(calls, stopOnce)
                .backend()
                .execute(request(), RUN_ID, "trace-stop", runDirectory, sink()));
    var interrupted =
        ContractObjectMapper.parseTree(
            Files.readString(runDirectory.resolve("structured/desktop-solve-state.json")));
    long durableResults = interrupted.path("researchResults").path("artifacts").size();
    long callsBeforeResume = calls.findByRun(RUN_ID).size();

    RunExecutionBackend.RunExecutionResult resumed =
        fixture(calls, DesktopDurableBoundaryObserver.none())
            .backend()
            .execute(request(), RUN_ID, "trace-resume", runDirectory, sink());

    assertEquals("completed", resumed.status());
    assertTrue(durableResults > 0L);
    assertTrue(calls.findByRun(RUN_ID).size() >= callsBeforeResume);
    assertEquals(1, stops.get());
    assertEquals(
        DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION,
        ContractObjectMapper.parseTree(
                Files.readString(runDirectory.resolve("structured/desktop-solve-state.json")))
            .path("schemaVersion")
            .asInt());
  }

  @Test
  void validationRuntimeDoesNotPersistHiddenReasoningTraces() {
    InMemoryProviderCallRepository calls = new InMemoryProviderCallRepository();
    Path runDirectory = temporaryDirectory.resolve("no-hidden-reasoning-run");

    RunExecutionBackend.RunExecutionResult result =
        fixture(calls, DesktopDurableBoundaryObserver.none(), new ProviderProbe(), false)
            .backend()
            .execute(request(), RUN_ID, "trace-no-hidden-reasoning", runDirectory, sink());

    assertEquals("completed", result.status());
    assertFalse(
        Files.exists(runDirectory.resolve("reports").resolve(ReasoningTraceStore.FILE_NAME)));
    assertFalse(
        Files.exists(
            runDirectory.resolve("reports").resolve(ReasoningTraceStore.LIVE_DIRECTORY_NAME)));
  }

  @ParameterizedTest(name = "restores from production durable boundary {0}")
  @EnumSource(RecoveryBoundary.class)
  void restoresEveryControlledBenchmarkBoundaryWithoutProviderReplay(RecoveryBoundary recovery)
      throws Exception {
    String runId = RUN_ID + '-' + recovery.name().toLowerCase(java.util.Locale.ROOT);
    Path runDirectory = temporaryDirectory.resolve("boundary-" + recovery.name());
    InMemoryProviderCallRepository calls = new InMemoryProviderCallRepository();
    ProviderProbe probe = new ProviderProbe();
    StopAtBoundary stop = new StopAtBoundary(recovery);

    assertThrows(
        ControlledStop.class,
        () ->
            fixture(calls, stop, probe)
                .backend()
                .execute(request(runId), runId, "trace-stop", runDirectory, sink()));

    Path checkpoint = runDirectory.resolve("structured/desktop-solve-state.json");
    var interrupted = ContractObjectMapper.parseTree(Files.readString(checkpoint));
    String problemHash = interrupted.path("problemHash").asText();
    String rootHash = interrupted.path("problem").path("goal_hash").asText();
    long callsBeforeResume = probe.physicalCalls();
    Set<String> durableCallIds = callIds(calls, runId);
    assertEquals(DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION, interrupted.path("schemaVersion").asInt());
    assertTrue(!problemHash.isBlank());
    assertTrue(!rootHash.isBlank());
    assertTrue(stop.observed());

    RunExecutionBackend.RunExecutionResult resumed =
        fixture(calls, DesktopDurableBoundaryObserver.none(), probe)
            .backend()
            .execute(request(runId), runId, "trace-resume", runDirectory, sink());

    var restored = ContractObjectMapper.parseTree(Files.readString(checkpoint));
    assertEquals("completed", resumed.status());
    assertEquals(problemHash, restored.path("problemHash").asText());
    assertEquals(rootHash, restored.path("problem").path("goal_hash").asText());
    assertEquals(DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION, restored.path("schemaVersion").asInt());
    assertTrue(callIds(calls, runId).containsAll(durableCallIds));
    assertEquals(calls.findByRun(runId).size(), callIds(calls, runId).size());
    assertEquals(calls.findByRun(runId).size(), idempotencyKeys(calls, runId).size());
    assertEquals(calls.findByRun(runId).size(), probe.physicalCalls());
    assertTrue(probe.physicalCalls() >= callsBeforeResume);
  }

  private Fixture fixture(
      InMemoryProviderCallRepository calls, DesktopDurableBoundaryObserver observer) {
    return fixture(calls, observer, new ProviderProbe());
  }

  private Fixture fixture(
      InMemoryProviderCallRepository calls,
      DesktopDurableBoundaryObserver observer,
      ProviderProbe probe) {
    return fixture(calls, observer, probe, true);
  }

  private Fixture fixture(
      InMemoryProviderCallRepository calls,
      DesktopDurableBoundaryObserver observer,
      ProviderProbe probe,
      boolean reasoningTracePersistenceEnabled) {
    Path project = DesktopLiveRunExecutionBackendTest.projectRoot();
    DesktopRuntimeLocator locator = new DesktopRuntimeLocator(project, null);
    SystemConfig config =
        DesktopLiveRunExecutionBackendTest.mockConfig(
            locator.loadProfile("proof-control-active.yaml"));
    DesktopLiveRuntimeFactory.PreparedRuntime prepared =
        new DesktopLiveRuntimeFactory.PreparedRuntime(
            "scripted", config, Map.of(), false, reasoningTracePersistenceEnabled);
    DesktopLiveRuntimeFactory runtimes = mock(DesktopLiveRuntimeFactory.class);
    when(runtimes.prepare(eq("proof_control_active"), any(DesktopSettings.class)))
        .thenReturn(prepared);
    when(runtimes.openProviders(prepared)).thenAnswer(ignored -> providers(config, probe));
    DesktopPaths paths = DesktopTestSupport.paths(temporaryDirectory.resolve("desktop-data"));
    SettingsStore settings = new SettingsStore(paths.settingsFile(), DesktopTestSupport.MAPPER);
    DesktopLiveRunExecutionBackend backend =
        new DesktopLiveRunExecutionBackend(
            paths,
            settings,
            runtimes,
            locator,
            new DockerSandboxPreflight(),
            () -> calls,
            observer);
    return new Fixture(backend);
  }

  private static ProviderClientRegistry providers(SystemConfig config, ProviderProbe probe) {
    MockResponder responder =
        request -> {
          String providerPayload =
              request.messages().stream()
                  .map(message -> message.content())
                  .collect(java.util.stream.Collectors.joining("\n"));
          if (providerPayload.contains("_json_repair]")) {
            OlympiadPromptPolicy.validateNoForbiddenMetadata(providerPayload);
          } else {
            OlympiadPromptPolicy.validateProviderPayload(
                providerPayload, EXPECTED_PROBLEM, false);
          }
          long requestId = probe.recordPhysicalCall();
          LLMResponse source =
              DesktopLiveRunExecutionBackendTest.response(
                  request, DesktopLiveRunExecutionBackendTest.Mode.COMPLETED);
          return new LLMResponse(
              source.text(),
              source.model(),
              source.provider(),
              source.inputTokens(),
              source.outputTokens(),
              source.latencyMs(),
              source.requestId() + '-' + requestId,
              source.finishReason(),
              source.streaming(),
              source.metadata());
        };
    Map<String, MockResponder> responders = new LinkedHashMap<>();
    config.agents().forEach(agent -> responders.put(agent.id(), responder));
    HttpTransport noNetwork = request -> {
      throw new AssertionError("benchmark production-path test attempted network access");
    };
    return new ProviderClientRegistry(
        config, responders, ignored -> noNetwork, false, ignored -> null);
  }

  private static SolveRequest request() {
    return request(RUN_ID);
  }

  private static SolveRequest request(String runId) {
    return new SolveRequest(PROBLEM, runId, null, "proof_control_active");
  }

  private static RunExecutionBackend.ProgressSink sink() {
    return (type, stage, agentId, status, summary, reference) -> {};
  }

  private record Fixture(DesktopLiveRunExecutionBackend backend) {}

  private static Set<String> callIds(InMemoryProviderCallRepository calls, String runId) {
    Set<String> values = new HashSet<>();
    calls.findByRun(runId).forEach(call -> values.add(call.callId()));
    return Set.copyOf(values);
  }

  private static Set<String> idempotencyKeys(
      InMemoryProviderCallRepository calls, String runId) {
    Set<String> values = new HashSet<>();
    calls.findByRun(runId).forEach(call -> values.add(call.idempotencyKey()));
    return Set.copyOf(values);
  }

  private enum RecoveryBoundary {
    FIRST_RESULT_DURABLE,
    ALL_SETTLED,
    MERGE_PREPARED,
    CHECKPOINT_V22
  }

  private static final class StopAtBoundary implements DesktopDurableBoundaryObserver {
    private final RecoveryBoundary target;
    private final AtomicInteger stops = new AtomicInteger();

    private StopAtBoundary(RecoveryBoundary target) {
      this.target = target;
    }

    @Override
    public void afterDurableBoundary(DesktopDurableBoundary boundary, Path checkpoint) {
      if (!matches(boundary, checkpoint) || stops.getAndIncrement() != 0) {
        return;
      }
      throw new ControlledStop();
    }

    @Override
    public int maximumResearchInFlight(int configuredMaximum) {
      return target == RecoveryBoundary.FIRST_RESULT_DURABLE ? 1 : configuredMaximum;
    }

    private boolean observed() {
      return stops.get() == 1;
    }

    private boolean matches(DesktopDurableBoundary boundary, Path checkpoint) {
      if (target != RecoveryBoundary.CHECKPOINT_V22) {
        return boundary.name().equals(target.name());
      }
      if (boundary != DesktopDurableBoundary.CHECKPOINT_V22) {
        return false;
      }
      try {
        var state = ContractObjectMapper.parseTree(Files.readString(checkpoint));
        for (var epoch : state.path("researchEpochs").path("epochs")) {
          if ("COMMITTED".equals(epoch.path("status").asText())) {
            return true;
          }
        }
        return false;
      } catch (java.io.IOException exception) {
        throw new IllegalStateException("could not inspect durable benchmark checkpoint", exception);
      }
    }
  }

  private static final class ProviderProbe {
    private final AtomicLong physicalCalls = new AtomicLong();

    private long recordPhysicalCall() {
      return physicalCalls.incrementAndGet();
    }

    private long physicalCalls() {
      return physicalCalls.get();
    }
  }

  private static final class ControlledStop extends Error {
    private static final long serialVersionUID = 1L;
  }
}
