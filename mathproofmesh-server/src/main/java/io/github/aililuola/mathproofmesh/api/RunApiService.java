package io.github.aililuola.mathproofmesh.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.api.RunApiModels.ApiEvent;
import io.github.aililuola.mathproofmesh.api.RunApiModels.ArtifactPayload;
import io.github.aililuola.mathproofmesh.api.RunApiModels.ProofGraphView;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RouteView;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RunView;
import io.github.aililuola.mathproofmesh.api.RunApiModels.UsageView;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphSnapshot;
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
      StoredRun created =
          allowConfiguredBackend && executionBackend != null
              ? executeConfigured(request, runId, traceId, eventObserver)
              : executeMock(runId, traceId, eventObserver);
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
        if ("completed".equals(run.view.status())) {
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
      String runId, String traceId, Consumer<ApiEvent> eventObserver) {
    long started = System.nanoTime();
    List<ApiEvent> events = new ArrayList<>();
    StoredRun run = new StoredRun(events, started, traceId, eventObserver, null);
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
    RunView preReport =
        new RunView(
            runId,
            "completed",
            "report",
            "Mock proof completed and independently verified.",
            null,
            traceId,
            10,
            run.latestEventId(),
            List.of("route-1"),
            List.of("claim-induction"));
    ReportFunctions.RunReport report =
        ReportFunctions.writeRunReport(runRoot.resolve(runId), preReport, routes);
    synchronized (run) {
      run.artifacts.put(
          report.hash(),
          new ArtifactPayload(report.hash(), report.mediaType(), report.bytes()));
      run.add(
          "result",
          "report",
          null,
          "completed",
          "Verified result is available",
          report.reference());
      run.routes = routes;
      run.view =
          new RunView(
              runId,
              "completed",
              "report",
              "Mock proof completed and independently verified.",
              report.reference(),
              traceId,
              10,
              run.latestEventId(),
              List.of("route-1"),
              List.of("claim-induction"));
    }
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
        new StoredRun(new ArrayList<>(), started, traceId, eventObserver, boundRequest);
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
    RunView preReport =
        new RunView(
            runId,
            result.status(),
            result.currentStage(),
            result.summary(),
            null,
            traceId,
            result.logicalSteps(),
            run.latestEventId(),
            result.routes().stream().map(RouteView::routeId).toList(),
            result.verifiedLocalClaimIds(),
            UsageView.from(result.usage()));
    ReportFunctions.RunReport report =
        ReportFunctions.writeRunReport(
            runRoot.resolve(runId), preReport, result.routes(), result.reportBody());
    synchronized (run) {
      run.artifacts.put(
          report.hash(),
          new ArtifactPayload(report.hash(), report.mediaType(), report.bytes()));
      run.add(
          "result",
          result.currentStage(),
          null,
          result.status(),
          "Run result is available",
          report.reference());
      run.routes = result.routes();
      run.view =
          new RunView(
              runId,
              result.status(),
              result.currentStage(),
              result.summary(),
              report.reference(),
              traceId,
              result.logicalSteps(),
              run.latestEventId(),
              result.routes().stream().map(RouteView::routeId).toList(),
              result.verifiedLocalClaimIds(),
              UsageView.from(result.usage()));
      return run.view;
    }
  }

  private StoredRun require(String runId) {
    StoredRun run = runs.get(runId);
    if (run == null) {
      throw new ApiNotFoundException("run cannot be resumed or queried");
    }
    return run;
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

    private StoredRun(
        List<ApiEvent> events,
        long startedNanos,
        String traceId,
        Consumer<ApiEvent> eventObserver,
        SolveRequest request) {
      this.events = events;
      this.startedNanos = startedNanos;
      this.traceId = TraceContext.validate(traceId);
      this.eventObserver = eventObserver;
      this.request = request;
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
