package io.github.aililuola.mathproofmesh.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.api.RunApiModels.ApiEvent;
import io.github.aililuola.mathproofmesh.api.RunApiModels.ArtifactPayload;
import io.github.aililuola.mathproofmesh.api.RunApiModels.ProofGraphView;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RouteView;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RunView;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphSnapshot;
import io.github.aililuola.mathproofmesh.runstate.FileRunStateStore;
import io.github.aililuola.mathproofmesh.runstate.RunCampaignStatus;
import io.github.aililuola.mathproofmesh.runstate.RunReportStatus;
import io.github.aililuola.mathproofmesh.runstate.RunStateSnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunResultProjectionService;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** API-facing application service. The deterministic mock path performs no provider call. */
@Service
public final class RunApiService {
  private final ApiObservability observability;
  private final Path runRoot;
  private final Semaphore runSlots;
  private final RunExecutionBackend executionBackend;
  private final FileRunStateStore runStateStore;
  private final ConcurrentMap<String, StoredRun> runs = new ConcurrentHashMap<>();

  @Autowired
  public RunApiService(
      ApiObservability observability,
      @Value("${mathproofmesh.api.run-root:target/api-runs}") String runRoot,
      @Value("${mathproofmesh.api.max-concurrent-requests:8}") int maxConcurrentRuns,
      ObjectProvider<RunExecutionBackend> executionBackends) {
    this(
        observability,
        runRoot,
        maxConcurrentRuns,
        executionBackends.orderedStream().findFirst().orElse(null));
  }

  public RunApiService(
      ApiObservability observability, String runRoot, int maxConcurrentRuns) {
    this(observability, runRoot, maxConcurrentRuns, (RunExecutionBackend) null);
  }

  public RunApiService(
      ApiObservability observability,
      String runRoot,
      int maxConcurrentRuns,
      RunExecutionBackend executionBackend) {
    this.observability = observability;
    this.runRoot = Path.of(runRoot).toAbsolutePath().normalize();
    this.runSlots = new Semaphore(Math.max(1, Math.min(64, maxConcurrentRuns)), true);
    this.executionBackend = executionBackend;
    this.runStateStore = new FileRunStateStore(this.runRoot);
  }

  public RunView solve(SolveRequest request) {
    return solve(request, null);
  }

  /** Executes a solve while forwarding each committed API event to a local observer. */
  public RunView solve(SolveRequest request, Consumer<ApiEvent> eventObserver) {
    return solve(request, true, eventObserver);
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "Domain failures are recorded in metrics and intentionally preserved for API translation.")
  private RunView solve(
      SolveRequest request, boolean allowConfiguredBackend, Consumer<ApiEvent> eventObserver) {
    String traceId = TraceContext.currentOrCreate();
    Timer.Sample sample = observability.start("solve", traceId);
    if (!runSlots.tryAcquire()) {
      observability.error();
      throw new ApiBusyException();
    }
    try {
      String runId =
          request.runId() == null
              ? "run-" + UUID.randomUUID().toString().replace("-", "")
              : request.runId();
      StoredRun prior = runs.get(runId);
      if (prior != null) {
        return prior.view();
      }
      if (runStateStore.load(runId).isPresent()) {
        StoredRun restored = restoreStoredRun(runId);
        StoredRun winner = runs.putIfAbsent(runId, restored);
        return (winner == null ? restored : winner).view();
      }
      StoredRun created =
          allowConfiguredBackend && executionBackend != null
              ? executeConfigured(request, runId, traceId, eventObserver)
              : executeMock(request, runId, traceId, eventObserver);
      StoredRun winner = runs.putIfAbsent(runId, created);
      return winner == null ? created.view() : winner.view();
    } catch (RuntimeException exception) {
      observability.error();
      throw exception;
    } finally {
      runSlots.release();
      observability.complete(sample);
    }
  }

  public RunView resume(ResumeRequest request) {
    return resume(request, null);
  }

  /** Resumes a configured backend and forwards newly committed events to the supplied observer. */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "Domain failures are recorded in metrics and intentionally preserved for API translation.")
  public RunView resume(ResumeRequest request, Consumer<ApiEvent> eventObserver) {
    String traceId = TraceContext.currentOrCreate();
    Timer.Sample sample = observability.start("resume", traceId);
    if (!runSlots.tryAcquire()) {
      observability.error();
      throw new ApiBusyException();
    }
    StoredRun run = null;
    try {
      run = require(request.runId());
      synchronized (run) {
        if (run.view.campaignStatus() == RunCampaignStatus.TERMINAL) {
          return run.view;
        }
        if (run.resuming) {
          throw new ApiBusyException();
        }
        if (eventObserver != null) {
          run.eventObserver = eventObserver;
        }
        run.add(
            "stage_changed",
            "run_resume",
            null,
            "running",
            "Resuming from the latest committed checkpoint",
            null);
        if (executionBackend == null || run.request == null) {
          run.add(
              "result",
              "report",
              null,
              "completed",
              "Terminal run result is available",
              run.view.resultReference());
          return run.view;
        }
        run.resuming = true;
        run.executionAttemptId = newAttemptId(request.runId());
      }

      RunExecutionBackend.RunExecutionResult result =
          executionBackend.resume(
              run.request,
              request,
              run.traceId,
              runRoot.resolve(request.runId()),
              run::add);
      return applyConfiguredResult(run, request.runId(), run.traceId, result);
    } catch (RuntimeException exception) {
      observability.error();
      throw exception;
    } finally {
      if (run != null) {
        synchronized (run) {
          run.resuming = false;
        }
      }
      runSlots.release();
      observability.complete(sample);
    }
  }

  public RunView status(String runId) {
    return require(RunApiModels.safeRunId(runId)).view();
  }

  public List<ApiEvent> eventsAfter(String runId, long lastEventId) {
    StoredRun run = require(RunApiModels.safeRunId(runId));
    synchronized (run) {
      return run.events.stream().filter(event -> event.eventId() > lastEventId).toList();
    }
  }

  public List<RouteView> routes(String runId) {
    return require(RunApiModels.safeRunId(runId)).routes;
  }

  public ProofGraphView proofGraph(String runId) {
    String safeRunId = RunApiModels.safeRunId(runId);
    StoredRun run = require(safeRunId);
    Path projection = runRoot.resolve(safeRunId).resolve("structured").resolve("proof-graph.json");
    if (Files.isRegularFile(projection)) {
      try {
        ProofGraphSnapshot snapshot =
            ContractObjectMapper.read(
                Files.readString(projection, StandardCharsets.UTF_8), ProofGraphSnapshot.class);
        return projectProofGraph(run, snapshot);
      } catch (IOException | RuntimeException exception) {
        throw new IllegalStateException("proof graph projection could not be read", exception);
      }
    }
    return syntheticProofGraph(run);
  }

  private ProofGraphView projectProofGraph(StoredRun run, ProofGraphSnapshot snapshot) {
    List<Map<String, String>> nodes = new ArrayList<>();
    List<Map<String, String>> edges = new ArrayList<>();
    Set<String> nodeIds = new HashSet<>();
    snapshot.obligations().values().stream()
        .sorted(java.util.Comparator.comparing(value -> value.obligationId()))
        .forEach(
            obligation -> {
              nodes.add(
                  Map.of(
                      "id", obligation.obligationId(),
                      "kind", "obligation",
                      "subtype", obligation.kind().value(),
                      "status", obligation.status(),
                      "label", ActivitySanitizer.text(obligation.statement(), 800)));
              nodeIds.add(obligation.obligationId());
            });
    snapshot.claimNodes().values().stream()
        .sorted(java.util.Comparator.comparing(value -> value.messageId()))
        .forEach(
            claim -> {
              nodes.add(
                  Map.of(
                      "id", claim.messageId(),
                      "kind", "claim",
                      "subtype", claim.evidenceType().value(),
                      "status", claim.verificationStatus().value(),
                      "label", ActivitySanitizer.text(claim.statement(), 800)));
              nodeIds.add(claim.messageId());
            });
    snapshot.edges().values().stream()
        .sorted(java.util.Comparator.comparing(value -> value.edgeId()))
        .filter(edge -> nodeIds.contains(edge.sourceId()) && nodeIds.contains(edge.targetId()))
        .forEach(
            edge ->
                edges.add(
                    Map.of(
                        "id", edge.edgeId(),
                        "from", edge.sourceId(),
                        "to", edge.targetId(),
                        "kind", edge.edgeType().value())));
    for (RouteView route : run.routes) {
      if (nodeIds.add(route.routeId())) {
        nodes.add(
            Map.of(
                "id", route.routeId(),
                "kind", "route",
                "status", route.status(),
                "label", ActivitySanitizer.text(route.summary(), 800)));
      }
      for (String claimId : route.claimIds()) {
        if (nodeIds.contains(claimId)) {
          edges.add(
              Map.of(
                  "id", "route-membership-" + route.routeId() + "-" + claimId,
                  "from", route.routeId(),
                  "to", claimId,
                  "kind", "contains"));
        }
      }
    }
    return new ProofGraphView(run.view().runId(), nodes, edges);
  }

  private ProofGraphView syntheticProofGraph(StoredRun run) {
    List<Map<String, String>> nodes = new ArrayList<>();
    List<Map<String, String>> edges = new ArrayList<>();
    nodes.add(
        Map.of(
            "id", "goal",
            "kind", "goal",
            "status", "completed".equals(run.view().status()) ? "verified" : "unverified"));
    for (RouteView route : run.routes) {
      nodes.add(
          Map.of(
              "id", route.routeId(),
              "kind", "route",
              "status", route.status()));
      edges.add(Map.of("from", route.routeId(), "to", "goal", "kind", "supports"));
      for (String claimId : route.claimIds()) {
        nodes.add(
            Map.of(
                "id", claimId,
                "kind", "claim",
                "status", route.status()));
        edges.add(
            Map.of("from", claimId, "to", route.routeId(), "kind", "supports"));
      }
    }
    return new ProofGraphView(run.view().runId(), nodes, edges);
  }

  public ArtifactPayload artifact(String runId, String hash) {
    StoredRun run = require(RunApiModels.safeRunId(runId));
    String safeHash = RunApiModels.safeHash(hash);
    ArtifactPayload artifact = run.artifacts.get(safeHash);
    if (artifact == null) {
      throw new ApiNotFoundException("artifact was not found");
    }
    return artifact;
  }

  public RunView demo(String requestedRunId) {
    String runId =
        requestedRunId == null || requestedRunId.isBlank() ? "demo-run" : requestedRunId;
    return solve(MockDemoFunctions.request(runId), false, null);
  }

  public Path runRoot() {
    return runRoot;
  }

  private StoredRun executeMock(
      SolveRequest request,
      String runId,
      String traceId,
      Consumer<ApiEvent> eventObserver) {
    long started = System.nanoTime();
    List<ApiEvent> events = new ArrayList<>();
    StoredRun run =
        new StoredRun(
            events, started, traceId, eventObserver, request, newAttemptId(runId));
    persistRequest(runId, request);
    run.add("run_started", "goal_preflight", null, "running", "Run accepted", null);
    run.add(
        "stage_changed",
        "strategy_generation",
        null,
        "running",
        "Generating an independent proof route",
        null);
    run.add(
        "agent_started",
        "independent_exploration",
        "mock-explorer",
        "running",
        "Mock explorer started",
        null);
    run.add(
        "heartbeat",
        "independent_exploration",
        "mock-explorer",
        "running",
        "Agent call remains active",
        null);
    run.add(
        "agent_completed",
        "independent_exploration",
        "mock-explorer",
        "completed",
        "Mock explorer produced a bounded proof route",
        null);
    run.add(
        "route_updated",
        "route_team",
        null,
        "verified",
        "Independent route review passed",
        "artifact://routes/route-1");
    run.add(
        "checkpoint",
        "checkpoint_verification",
        null,
        "committed",
        "Verified checkpoint committed",
        "artifact://checkpoints/checkpoint-1");
    run.add(
        "verification",
        "final_verification",
        "mock-verifier",
        "verified",
        "Blind final review passed",
        null);
    run.add("budget", "report", null, "completed", "Mock budget reconciled", null);

    List<RouteView> routes =
        List.of(
            new RouteView(
                "route-1",
                "verified",
                "Induction route independently reviewed",
                List.of("claim-induction")));
    applyConfiguredResult(
        run,
        runId,
        traceId,
        new RunExecutionBackend.RunExecutionResult(
            "completed",
            "report",
            "Mock proof completed and independently verified.",
            routes,
            List.of("claim-induction"),
            "",
            10));
    observability.recordMockRun();
    return run;
  }

  private StoredRun executeConfigured(
      SolveRequest request,
      String runId,
      String traceId,
      Consumer<ApiEvent> eventObserver) {
    long started = System.nanoTime();
    SolveRequest boundRequest =
        new SolveRequest(
            request.problem(), runId, request.canonicalStatement(), request.profile());
    StoredRun run =
        new StoredRun(
            new ArrayList<>(),
            started,
            traceId,
            eventObserver,
            boundRequest,
            newAttemptId(runId));
    persistRequest(runId, boundRequest);
    run.add("run_started", "goal_preflight", null, "running", "Run accepted", null);
    RunExecutionBackend.RunExecutionResult result =
        executionBackend.execute(
            boundRequest,
            runId,
            traceId,
            runRoot.resolve(runId),
            run::add);
    applyConfiguredResult(run, runId, traceId, result);
    return run;
  }

  private RunView applyConfiguredResult(
      StoredRun run,
      String runId,
      String traceId,
      RunExecutionBackend.RunExecutionResult result) {
    RunStateSnapshot previous = runStateStore.load(runId).orElse(null);
    RunStateSnapshot authorityState =
        RunStateApiProjection.reconcile(
            run.request,
            runId,
            run.executionAttemptId,
            runRoot.resolve(runId),
            result,
            previous);
    long expectedVersion = previous == null ? -1L : previous.authority().version();
    runStateStore.compareAndSet(runId, expectedVersion, authorityState, "api-run-service", 0L);
    var resultReceipt =
        new RunResultProjectionService()
            .project(
                runRoot.resolve(runId),
                authorityState,
                java.util.Map.of(
                    "run_id", runId,
                    "summary", result.summary(),
                    "completed_route_ids", result.routes().stream().map(RouteView::routeId).toList(),
                    "verified_local_claim_ids", result.verifiedLocalClaimIds(),
                    "logical_steps", result.logicalSteps()));
    String resultExpectedStateHash = authorityState.stateHash();
    long resultExpectedProjectionVersion = authorityState.projection().projectionVersion();
    authorityState =
        RunStateApiProjection.withResult(
            authorityState,
            resultReceipt.reference(),
            resultReceipt.artifactHash(),
            resultReceipt.errors());
    runStateStore.compareAndSetProjection(
        runId,
        resultExpectedStateHash,
        resultExpectedProjectionVersion,
        authorityState,
        "api-run-service",
        0L);
    RunView preReport =
        RunStateApiProjection.view(
            runId,
            traceId,
            result.summary(),
            null,
            result.logicalSteps(),
            run.latestEventId(),
            result.routes().stream().map(RouteView::routeId).toList(),
            result.verifiedLocalClaimIds(),
            authorityState);
    RunReportProjectionService.Projection reportProjection =
        new RunReportProjectionService()
            .project(runRoot.resolve(runId), preReport, result.routes(), result.reportBody());
    ReportFunctions.RunReport report = reportProjection.report();
    var receipt = reportProjection.receipt();
    RunStateSnapshot projectedState =
        RunStateApiProjection.withReport(
            authorityState,
            receipt.status(),
            receipt.reference(),
            receipt.artifactHash(),
            receipt.errors());
    runStateStore.compareAndSetProjection(
        runId,
        authorityState.stateHash(),
        authorityState.projection().projectionVersion(),
        projectedState,
        "api-run-service",
        0L);
    String reportReference = report == null ? null : report.reference();
    synchronized (run) {
      if (report != null) {
        run.artifacts.put(
            report.hash(),
            new ArtifactPayload(report.hash(), report.mediaType(), report.bytes()));
      }
      run.add(
          "result",
          result.currentStage(),
          null,
          RunStateApiProjection.compatibleStatus(projectedState),
          "Run result is available",
          reportReference);
      run.routes = result.routes();
      run.view =
          RunStateApiProjection.view(
              runId,
              traceId,
              result.summary(),
              reportReference,
              result.logicalSteps(),
              run.latestEventId(),
              result.routes().stream().map(RouteView::routeId).toList(),
              result.verifiedLocalClaimIds(),
              projectedState);
      return run.view;
    }
  }

  private StoredRun require(String runId) {
    StoredRun run = runs.computeIfAbsent(runId, this::restoreStoredRun);
    if (run == null) {
      throw new ApiNotFoundException("run cannot be resumed or queried");
    }
    return run;
  }

  private StoredRun restoreStoredRun(String runId) {
    RunStateSnapshot state = runStateStore.load(runId).orElse(null);
    if (state == null) {
      return null;
    }
    String traceId = TraceContext.currentOrCreate();
    SolveRequest request = readRequest(runId);
    StoredRun restored =
        new StoredRun(
            new ArrayList<>(),
            System.nanoTime(),
            traceId,
            null,
            request,
            state.authority().executionAttemptId());
    synchronized (restored) {
      restored.view =
          RunStateApiProjection.view(
              runId,
              traceId,
              "Durable run state restored",
              state.projection().reportRef().isBlank() ? null : state.projection().reportRef(),
              0,
              0L,
              List.of(),
              List.of(),
              state);
    }
    return restored;
  }

  private void persistRequest(String runId, SolveRequest request) {
    Path path = runRoot.resolve(runId).resolve("structured").resolve("solve_request.json");
    try {
      Files.createDirectories(Objects.requireNonNull(path.getParent(), "solve request parent"));
      Files.writeString(path, ContractObjectMapper.write(request), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("solve request could not be persisted", exception);
    }
  }

  private SolveRequest readRequest(String runId) {
    Path path = runRoot.resolve(runId).resolve("structured").resolve("solve_request.json");
    if (!Files.isRegularFile(path)) {
      return null;
    }
    try {
      return ContractObjectMapper.read(
          Files.readString(path, StandardCharsets.UTF_8), SolveRequest.class);
    } catch (IOException | RuntimeException exception) {
      return null;
    }
  }

  private static String newAttemptId(String runId) {
    return "execution-attempt-"
        + io.github.aililuola.mathproofmesh.contract.CanonicalJson.stableHash(
                List.of(runId, UUID.randomUUID().toString()))
            .substring(0, 24);
  }

  private static final class StoredRun {
    private final List<ApiEvent> events;
    private final long startedNanos;
    private final String traceId;
    private final SolveRequest request;
    private final Map<String, ArtifactPayload> artifacts = new LinkedHashMap<>();
    private Consumer<ApiEvent> eventObserver;
    private List<RouteView> routes = List.of();
    private RunView view;
    private boolean resuming;
    private String executionAttemptId;

    private StoredRun(
        List<ApiEvent> events,
        long startedNanos,
        String traceId,
        Consumer<ApiEvent> eventObserver,
        SolveRequest request,
        String executionAttemptId) {
      this.events = events;
      this.startedNanos = startedNanos;
      this.traceId = TraceContext.validate(traceId);
      this.eventObserver = eventObserver;
      this.request = request;
      this.executionAttemptId = executionAttemptId;
    }

    private synchronized void add(
        String type,
        String stage,
        String agentId,
        String status,
        String summary,
        String reference) {
      long eventId = events.size() + 1L;
      ApiEvent event =
          new ApiEvent(
              eventId,
              RunApiModels.publicEventType(type),
              stage,
              agentId,
              Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
              status,
              summary,
              reference,
              traceId);
      events.add(event);
      if (eventObserver != null) {
        eventObserver.accept(event);
      }
    }

    private synchronized long latestEventId() {
      return events.isEmpty() ? 0L : events.get(events.size() - 1).eventId();
    }

    private synchronized RunView view() {
      return view;
    }
  }

  public static final class ApiNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ApiNotFoundException(String message) {
      super(message);
    }
  }

  public static final class ApiBusyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ApiBusyException() {
      super("request concurrency limit reached");
    }
  }
}
