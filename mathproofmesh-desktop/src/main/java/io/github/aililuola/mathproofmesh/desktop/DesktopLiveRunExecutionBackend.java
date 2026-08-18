package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.agent.BoundedJsonRepairer;
import io.github.aililuola.mathproofmesh.agent.CallLedger;
import io.github.aililuola.mathproofmesh.agent.PromptFactory;
import io.github.aililuola.mathproofmesh.agent.PromptRedactor;
import io.github.aililuola.mathproofmesh.agent.StructuredAgentRunner;
import io.github.aililuola.mathproofmesh.agent.StructuredCallResult;
import io.github.aililuola.mathproofmesh.api.ActivityImportance;
import io.github.aililuola.mathproofmesh.api.ActivityStatus;
import io.github.aililuola.mathproofmesh.api.ActivityStream;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceBinding;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceStore;
import io.github.aililuola.mathproofmesh.api.RunApiModels.RouteView;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend.ExecutionUsage;
import io.github.aililuola.mathproofmesh.api.SolveRequest;
import io.github.aililuola.mathproofmesh.computation.CompositeExternalComputationHandler;
import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationContext;
import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.computation.ComputationLimits;
import io.github.aililuola.mathproofmesh.computation.ContractsFunctions;
import io.github.aililuola.mathproofmesh.computation.ExternalComputationHandler;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.computation.SandboxSettings;
import io.github.aililuola.mathproofmesh.config.BudgetConfig;
import io.github.aililuola.mathproofmesh.config.ComputationConfig;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.ComputationDecision;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionStatus;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import io.github.aililuola.mathproofmesh.contract.FinalProof;
import io.github.aililuola.mathproofmesh.contract.InitialExplorationAction;
import io.github.aililuola.mathproofmesh.contract.InitialExplorationTurn;
import io.github.aililuola.mathproofmesh.contract.ProofAttempt;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.contract.TriageResult;
import io.github.aililuola.mathproofmesh.contract.VerificationReport;
import io.github.aililuola.mathproofmesh.contract.VerificationStage;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.AgentRuntime;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.provider.ProviderCallRecord;
import io.github.aililuola.mathproofmesh.provider.ProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.ProviderCallState;
import io.github.aililuola.mathproofmesh.provider.ProviderCircuitOpenError;
import io.github.aililuola.mathproofmesh.provider.ProviderErrorKind;
import io.github.aililuola.mathproofmesh.provider.ProviderException;
import io.github.aililuola.mathproofmesh.provider.UsageTotals;
import io.github.aililuola.mathproofmesh.runstate.ProviderCallUsageEvidence;
import io.github.aililuola.mathproofmesh.sidecar.PythonSandboxAstValidator;
import io.github.aililuola.mathproofmesh.sidecar.PythonSidecarComputationHandler;
import io.github.aililuola.mathproofmesh.sidecar.PythonSidecarWorkerPool;
import io.github.aililuola.mathproofmesh.sidecar.SandboxedPythonComputationHandler;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Production desktop solve pipeline backed by isolated live providers and bounded computation. */
final class DesktopLiveRunExecutionBackend implements RunExecutionBackend {
  private static final int MAX_INDEPENDENT_ROUTES = 3;
  private static final int MAX_REPORT_BYTES = 700_000;

  private final SettingsStore settings;
  private final DesktopLiveRuntimeFactory runtimes;
  private final DesktopRuntimeLocator locator;
  private final DockerSandboxPreflight dockerPreflight;
  private final Supplier<ProviderCallRepository> callRepositories;

  DesktopLiveRunExecutionBackend(
      DesktopPaths paths,
      SettingsStore settings,
      DesktopLiveRuntimeFactory runtimes,
      DesktopRuntimeLocator locator,
      DockerSandboxPreflight dockerPreflight) {
    this(
        paths,
        settings,
        runtimes,
        locator,
        dockerPreflight,
        InMemoryProviderCallRepository::new);
  }

  DesktopLiveRunExecutionBackend(
      DesktopPaths paths,
      SettingsStore settings,
      DesktopLiveRuntimeFactory runtimes,
      DesktopRuntimeLocator locator,
      DockerSandboxPreflight dockerPreflight,
      Supplier<ProviderCallRepository> callRepositories) {
    Objects.requireNonNull(paths, "paths");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.locator = Objects.requireNonNull(locator, "locator");
    this.dockerPreflight = Objects.requireNonNull(dockerPreflight, "dockerPreflight");
    this.callRepositories = Objects.requireNonNull(callRepositories, "callRepositories");
  }

  @Override
  public RunExecutionResult execute(
      SolveRequest request,
      String runId,
      String traceId,
      Path runDirectory,
      ProgressSink progress) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(progress, "progress");
    DesktopLiveRuntimeFactory.PreparedRuntime runtime = null;
    ActivityStream activity = null;
    ProgressSink recordedProgress = progress;
    AtomicReference<LiveExecutionContext> liveContext = new AtomicReference<>();
    try {
      Files.createDirectories(runDirectory);
      activity = new ActivityStream(runDirectory, "zh", true, ignored -> {});
      recordedProgress = new AuditedProgressSink(activity, progress);
      runtime = runtimes.prepare(request.profile(), settings.load());
      return executeLive(request, runId, runDirectory, recordedProgress, runtime, liveContext);
    } catch (RuntimeException | java.io.IOException exception) {
      boolean cancelled =
          exception instanceof ProviderException providerException
              && providerException.kind() == ProviderErrorKind.CANCELLED;
      String status = cancelled ? "cancelled" : "failed";
      String failureDetail = safeFailureDetail(exception);
      String failureLocation = cancelled ? "" : safeFailureLocation(exception);
      String summary =
          cancelled
              ? "Live solve cancelled"
              : "Live solve failed: "
                  + exception.getClass().getSimpleName()
                  + (failureDetail.isBlank() ? "" : " - " + failureDetail)
                  + (failureLocation.isBlank() ? "" : " (" + failureLocation + ")");
      if (recordedProgress instanceof AuditedProgressSink audited) {
        audited.failActiveStage(summary);
      }
      recordedProgress.emit(
          cancelled ? "run_cancelled" : "run_failed",
          "report",
          null,
          status,
          summary,
          null);
      String profile = runtime == null ? "unavailable" : runtime.profile();
      ExecutionUsage failureUsage = failureUsage(runDirectory, runId, liveContext.get());
      String body =
          (cancelled ? "### Execution cancelled\n\n" : "### Execution failure\n\n")
              + "The live run stopped before a verified proof was produced. "
              + "No mock answer or host-side arbitrary-code fallback was used.\n\n"
              + "- Profile: `"
              + profile
              + "`\n"
              + (cancelled
                  ? "- The run was stopped by request.\n"
                  : "- Failure type: `"
                      + exception.getClass().getSimpleName()
                      + "`\n"
                      + (failureDetail.isBlank()
                          ? ""
                          : "- Failure detail: `" + failureDetail.replace('`', '\'') + "`\n")
                      + (failureLocation.isBlank()
                          ? ""
                          : "- Failure location: `" + failureLocation + "`\n"));
      return new io.github.aililuola.mathproofmesh.api.RunStateReconciliationService()
          .reconcileFailure(
              runDirectory,
              new RunExecutionResult(
                  status,
                  "report",
                  summary,
                  List.of(),
                  List.of(),
                  body,
                  0,
                  failureUsage,
                  null));
    } finally {
      if (activity != null) {
        try {
          activity.finalizeTimeline();
        } catch (RuntimeException ignored) {
          // The append-only activity log remains authoritative if report projection fails.
        }
      }
    }
  }

  private RunExecutionResult executeLive(
      SolveRequest request,
      String runId,
      Path runDirectory,
      ProgressSink progress,
      DesktopLiveRuntimeFactory.PreparedRuntime runtime,
      AtomicReference<LiveExecutionContext> liveContext)
      throws java.io.IOException {
    SystemConfig config = runtime.config();
    String problemHash = sha256(request.problem());
    progress.emit(
        "stage_changed",
        "goal_preflight",
        null,
        "running",
        "Validated immutable problem and full semantic solve profile",
        null);

    BudgetConfig budgetConfig = config.budget();
    CallLedger ledger =
        new CallLedger(
            budgetConfig.maxTotalCalls(),
            budgetConfig.maxTotalTokens() == null
                ? null
                : budgetConfig.maxTotalTokens().longValue(),
            budgetConfig.maxCostUsd() == null
                ? null
                : BigDecimal.valueOf(budgetConfig.maxCostUsd()));
    ComputationRuntime computation =
        createComputation(runId, runDirectory, config, progress, ledger::remainingCalls);
    ArtifactStore artifacts = new ArtifactStore(runDirectory.resolve("runtime-artifacts"), runId);
    PromptRedactor redactor = new PromptRedactor(new ArrayList<>(runtime.credentials().values()));
    PromptFactory prompts = new PromptFactory(config.runtime().outputLanguage());
    ReasoningTraceStore reasoningTraces =
        new ReasoningTraceStore(runDirectory, runId, runtime.credentials().values());
    ProviderCallRepository providerCalls =
        Objects.requireNonNull(callRepositories.get(), "provider call repository");
    liveContext.set(new LiveExecutionContext(ledger, providerCalls, config));

    try (AgentPool pool = new AgentPool(config, runtimes.openProviders(runtime))) {
      StructuredAgentRunner runner =
          new StructuredAgentRunner(
              pool,
              artifacts,
              providerCalls,
              ledger,
              redactor,
              new BoundedJsonRepairer(1_500_000),
              reasoningTraces,
              config.runtime().parseRetries(),
              config.runtime().jsonRepairMaxOutputTokens());
      boolean resumeRequested =
          config.continuation().processResumeEnabled()
              && Files.isRegularFile(
                  runDirectory.resolve("structured").resolve("desktop-solve-state.json"));
      return new DesktopSolveCoordinator(
              request,
              runId,
              runDirectory,
              runtime,
              runner,
              prompts,
              pool,
              ledger,
              computation.broker(),
              computation.sandboxEnabled(),
              progress,
              problemHash)
          .execute(resumeRequested);
    }
  }

  /** Retained only as a compatibility reference for old persisted test fixtures. */
  @SuppressFBWarnings(
      value = "UPM_UNCALLED_PRIVATE_METHOD",
      justification =
          "The method remains as an executable compatibility reference for old persisted fixtures.")
  private RunExecutionResult executeLegacyLive(
      SolveRequest request,
      String runId,
      Path runDirectory,
      ProgressSink progress,
      DesktopLiveRuntimeFactory.PreparedRuntime runtime)
      throws java.io.IOException {
    SystemConfig config = runtime.config();
    String problemHash = sha256(request.problem());
    progress.emit(
        "stage_changed",
        "goal_preflight",
        null,
        "running",
        "Validated immutable problem and live DeepSeek profile",
        null);

    BudgetConfig budgetConfig = config.budget();
    CallLedger ledger =
        new CallLedger(
            budgetConfig.maxTotalCalls(),
            budgetConfig.maxTotalTokens() == null
                ? null
                : budgetConfig.maxTotalTokens().longValue(),
            budgetConfig.maxCostUsd() == null
                ? null
                : BigDecimal.valueOf(budgetConfig.maxCostUsd()));
    ComputationRuntime computation =
        createComputation(runId, runDirectory, config, progress, ledger::remainingCalls);
    ArtifactStore artifacts = new ArtifactStore(runDirectory.resolve("runtime-artifacts"), runId);
    PromptRedactor redactor = new PromptRedactor(new ArrayList<>(runtime.credentials().values()));
    PromptFactory prompts = new PromptFactory(config.runtime().outputLanguage());
    ReasoningTraceStore reasoningTraces =
        new ReasoningTraceStore(runDirectory, runId, runtime.credentials().values());
    List<ComputationTrace> computationTraces = new ArrayList<>();

    try (AgentPool pool = new AgentPool(config, runtimes.openProviders(runtime))) {
      StructuredAgentRunner runner =
          new StructuredAgentRunner(
              pool,
              artifacts,
              new InMemoryProviderCallRepository(),
              ledger,
              redactor,
              new BoundedJsonRepairer(1_500_000),
              reasoningTraces,
              config.runtime().parseRetries(),
              config.runtime().jsonRepairMaxOutputTokens());

      AgentRuntime planner =
          pool.select("planner", Set.of(), List.of("problem_decomposition"), null, true);
      TriageResult triage =
          callStage(
                  runner,
                  prompts,
                  config,
                  progress,
                  runId,
                  "triage",
                  "triage",
                  TriageResult.class,
                  Map.of(
                      "immutable_problem", request.problem(),
                      "problem_hash", problemHash,
                      "live_model", "deepseek-v4-pro",
                      "reasoning_effort", "max"),
                  planner,
                  "breadth",
                  "Classifying the problem")
              .value();

      StrategySet strategySet =
          callStage(
                  runner,
                  prompts,
                  config,
                  progress,
                  runId,
                  "strategy-generation",
                  "strategy_generation",
                  StrategySet.class,
                  Map.of(
                      "immutable_problem", request.problem(),
                      "problem_hash", problemHash,
                      "triage", triage,
                      "strategies_requested", budgetConfig.strategiesToGenerate(),
                      "registered_computation_contracts",
                          ContractsFunctions.experimentToolCatalog(Set.of()),
                      "sandbox_available", computation.sandboxEnabled(),
                      "independence_requirement",
                          "Generate genuinely distinct routes for isolated explorers."),
                  planner,
                  "breadth",
                  "Generating independent proof strategies")
              .value();

      List<AgentRuntime> explorers =
          pool.agents().stream()
              .filter(agent -> agent.supportsRole("explorer"))
              .limit(MAX_INDEPENDENT_ROUTES)
              .toList();
      if (explorers.isEmpty() || strategySet.strategies().isEmpty()) {
        throw new IllegalStateException("live profile has no exploration route");
      }

      List<RouteWork> routeWork = new ArrayList<>();
      for (int index = 0; index < explorers.size(); index++) {
        AgentRuntime explorer = explorers.get(index);
        StrategyCard strategy =
            strategySet.strategies().get(index % strategySet.strategies().size());
        String routeId = "route-" + (index + 1);
        try {
          RouteWork work =
              exploreRoute(
                  request.problem(),
                  problemHash,
                  runId,
                  routeId,
                  strategy,
                  explorer,
                  runner,
                  prompts,
                  pool,
                  config,
                  computation,
                  computationTraces,
                  progress);
          routeWork.add(work);
        } catch (RuntimeException exception) {
          progress.emit(
              "agent_failed",
              "independent_exploration",
              explorer.id(),
              "failed",
              "Independent route stopped safely: "
                  + exception.getClass().getSimpleName(),
              null);
          routeWork.add(new RouteWork(routeId, explorer, strategy, null, null));
        }
      }

      List<RouteView> routeViews = new ArrayList<>();
      List<RouteWork> admitted = new ArrayList<>();
      for (RouteWork route : routeWork) {
        if (route.attempt() == null) {
          routeViews.add(
              new RouteView(
                  route.routeId(),
                  "unverified",
                  "No complete auditable attempt was submitted",
                  List.of()));
          continue;
        }
        AgentRuntime reviewer =
            pool.selectReviewer(
                "detailed_verifier", route.author().id(), route.strategy().tags());
        StructuredCallResult<VerificationReport> reviewCall =
            callStage(
                runner,
                prompts,
                config,
                progress,
                runId,
                "review-" + route.routeId(),
                "detailed_verification",
                VerificationReport.class,
                Map.of(
                    "immutable_problem", request.problem(),
                    "problem_hash", problemHash,
                    "candidate_attempt", route.attempt(),
                    "required_target_id", route.attempt().attemptId(),
                    "required_target_type", "attempt",
                    "required_stage", "detailed",
                    "author_excluded", route.author().id()),
                reviewer,
                "verification",
                "Independently auditing " + route.routeId());
        VerificationReport review =
            bindReview(
                reviewCall.value(),
                reviewCall,
                reviewer,
                route.attempt().attemptId(),
                "attempt",
                VerificationStage.DETAILED);
        RouteWork reviewed = route.withReview(review);
        boolean passed =
            review.verdict() == VerificationVerdict.PASS && review.problemIntegrityOk();
        routeViews.add(
            new RouteView(
                route.routeId(),
                passed ? "verified" : "unverified",
                review.conciseFeedback(),
                passed ? claimIds(route.routeId(), route.attempt().proofSteps()) : List.of()));
        if (passed) {
          admitted.add(reviewed);
        }
      }

      if (admitted.isEmpty()) {
        String report =
            renderReport(
                runtime,
                null,
                null,
                routeWork,
                routeViews,
                computationTraces,
                ledger);
        return new RunExecutionResult(
            "unverified",
            "report",
            "独立路线均未通过详细验证，因此没有生成最终已验证答案。",
            routeViews,
            verifiedRouteClaims(routeViews),
            boundedUtf8(report, MAX_REPORT_BYTES),
            safeInt(ledger.totals().calls()),
            executionUsage(ledger.totals()));
      }

      AgentRuntime synthesizer =
          pool.select(
              "synthesizer", Set.of(), List.of("proof_synthesis"), null, true);
      StructuredCallResult<FinalProof> synthesisCall =
          callStage(
              runner,
              prompts,
              config,
              progress,
              runId,
              "synthesis",
              "synthesis",
              FinalProof.class,
              Map.of(
                  "immutable_problem", request.problem(),
                  "problem_hash", problemHash,
                  "admitted_attempts", admitted.stream().map(RouteWork::attempt).toList(),
                  "independent_reviews", admitted.stream().map(RouteWork::review).toList(),
                  "computation_evidence",
                      computationTraces.stream().map(ComputationTrace::publicView).toList(),
                  "synthesis_rule",
                      "Use only supported material; preserve every hypothesis and quantifier."),
              synthesizer,
              "synthesis",
              "Synthesizing independently reviewed routes");
      FinalProof finalProof =
          new FinalProof(
              synthesisCall.value().answer(),
              synthesisCall.value().caveats(),
              synthesisCall.value().confidence(),
              synthesisCall.value().dependencies(),
              problemHash,
              synthesisCall.value().proofSteps(),
              admitted.stream().map(route -> route.attempt().attemptId()).toList());

      AgentRuntime finalVerifier =
          pool.selectReviewer(
              "final_verifier", synthesizer.id(), List.of("proof_audit"));
      StructuredCallResult<VerificationReport> finalReviewCall =
          callStage(
              runner,
              prompts,
              config,
              progress,
              runId,
              "final-verification",
              "final_verification",
              VerificationReport.class,
              Map.of(
                  "immutable_problem", request.problem(),
                  "problem_hash", problemHash,
                  "final_proof", finalProof,
                  "required_target_id", "final-proof",
                  "required_target_type", "final_proof",
                  "required_stage", "final",
                  "synthesizer_excluded", synthesizer.id()),
              finalVerifier,
              "verification",
              "Performing independent final verification");
      VerificationReport finalReview =
          bindReview(
              finalReviewCall.value(),
              finalReviewCall,
              finalVerifier,
              "final-proof",
              "final_proof",
              VerificationStage.FINAL);
      boolean completed =
          finalReview.verdict() == VerificationVerdict.PASS
              && finalReview.problemIntegrityOk();
      progress.emit(
          "verification",
          "final_verification",
          finalVerifier.id(),
          completed ? "verified" : "unverified",
          completed
              ? "Independent final verification passed"
              : "Independent final verification did not pass",
          finalReviewCall.responseArtifactRef());

      String report =
          renderReport(
              runtime,
              finalProof,
              finalReview,
              routeWorkWithReviews(routeWork, admitted),
              routeViews,
              computationTraces,
              ledger);
      List<String> verifiedClaims =
          completed
              ? claimIds("final", finalProof.proofSteps())
              : verifiedRouteClaims(routeViews);
      return new RunExecutionResult(
          completed ? "completed" : "unverified",
          "report",
          completed
              ? "DeepSeek V4 Pro 已完成多路线求解并通过独立最终验证。"
              : "已生成候选答案，但独立最终验证未通过，结果保持未验证状态。",
          routeViews,
          verifiedClaims,
          boundedUtf8(report, MAX_REPORT_BYTES),
          safeInt(ledger.totals().calls()),
          executionUsage(ledger.totals()));
    }
  }

  private RouteWork exploreRoute(
      String problem,
      String problemHash,
      String runId,
      String routeId,
      StrategyCard strategy,
      AgentRuntime explorer,
      StructuredAgentRunner runner,
      PromptFactory prompts,
      AgentPool pool,
      SystemConfig config,
      ComputationRuntime computation,
      List<ComputationTrace> computationTraces,
      ProgressSink progress) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("immutable_problem", problem);
    context.put("problem_hash", problemHash);
    context.put("route_id", routeId);
    context.put("assigned_agent_id", explorer.id());
    context.put("assigned_strategy", strategy);
    context.put("registered_computation_contracts", ContractsFunctions.experimentToolCatalog(Set.of()));
    context.put("sandbox_available", computation.sandboxEnabled());
    context.put(
        "action_rule",
        "Return submit_attempt, request_computation, or abandon. Computation is bounded evidence, never proof by itself.");
    StructuredCallResult<InitialExplorationTurn> first =
        callStage(
            runner,
            prompts,
            config,
            progress,
            runId,
            "explore-" + routeId,
            "independent_exploration",
            InitialExplorationTurn.class,
            context,
            explorer,
            "depth",
            "Exploring " + routeId + " in isolation");
    InitialExplorationTurn turn = first.value();
    if (turn.action() == InitialExplorationAction.SUBMIT_ATTEMPT && turn.attempt() != null) {
      return new RouteWork(
          routeId,
          explorer,
          strategy,
          bindAttempt(turn.attempt(), first, explorer, routeId, problemHash),
          null);
    }
    if (turn.action() != InitialExplorationAction.REQUEST_COMPUTATION
        || turn.experimentSpec() == null) {
      return new RouteWork(routeId, explorer, strategy, null, null);
    }

    ExperimentSpec spec = bindExperiment(turn.experimentSpec(), routeId, explorer.id());
    ComputationBroker.PreparedDecision prepared =
        computation.broker().decide(
            spec,
            ComputationContext.initial(routeId, safeInt(computation.remainingCalls().getAsLong())));
    ComputationDecision decision = prepared.decision();
    ExperimentResult result = null;
    if (decision.decision() == ComputationDecisionStatus.ALLOW) {
      ExperimentProgram program = null;
      if (prepared.spec().method() == ComputationMethod.SANDBOXED_PYTHON) {
        AgentRuntime experimenter =
            pool.select(
                "experimenter", Set.of(explorer.id()), List.of(), null, true);
        StructuredCallResult<SandboxProgramDraft> draft =
            callStage(
                runner,
                prompts,
                config,
                progress,
                runId,
                "codegen-" + routeId,
                "experiment_codegen",
                SandboxProgramDraft.class,
                Map.of(
                    "immutable_problem", problem,
                    "experiment", prepared.spec(),
                    "sandbox_input", prepared.spec().arguments().path("input"),
                    "allowed_dependencies",
                        List.of(
                            "collections",
                            "decimal",
                            "fractions",
                            "functools",
                            "itertools",
                            "math"),
                    "program_rules",
                        List.of(
                            "Define exactly one function named run with signature run(data).",
                            "Use only direct public function calls; attribute access is forbidden.",
                            "Do not use files, network, processes, eval, exec, reflection, or private names.",
                            "Return outcome, cases_checked, scope, and exact_arithmetic.",
                            "The dependency list must exactly match imported top-level modules.")),
                experimenter,
                "depth",
                "Generating a bounded sandbox program");
        program = sandboxProgram(prepared.spec(), draft.value());
      }
      result = computation.broker().runExperiment(prepared.spec(), decision, program);
    }
    ComputationTrace trace =
        new ComputationTrace(routeId, prepared.spec(), decision, result);
    computationTraces.add(trace);
    progress.emit(
        "computation",
        "independent_exploration",
        explorer.id(),
        decision.decision() == ComputationDecisionStatus.ALLOW ? "completed" : "rejected",
        decision.reason(),
        null);

    Map<String, Object> continuation = new LinkedHashMap<>(context);
    continuation.put("previous_public_turn", turn);
    continuation.put("computation_decision", decision);
    continuation.put(
        "computation_result", result == null ? "not_executed" : result);
    continuation.put(
        "continuation_rule",
        "Interpret the bounded result. Submit one auditable attempt or abandon; do not request a second computation in this segment.");
    StructuredCallResult<InitialExplorationTurn> interpreted =
        callStage(
            runner,
            prompts,
            config,
            progress,
            runId,
            "interpret-" + routeId,
            "independent_exploration",
            InitialExplorationTurn.class,
            continuation,
            explorer,
            "depth",
            "Interpreting bounded computation for " + routeId);
    InitialExplorationTurn interpretedTurn = interpreted.value();
    ProofAttempt attempt =
        interpretedTurn.action() == InitialExplorationAction.SUBMIT_ATTEMPT
                && interpretedTurn.attempt() != null
            ? bindAttempt(
                interpretedTurn.attempt(), interpreted, explorer, routeId, problemHash)
            : null;
    return new RouteWork(routeId, explorer, strategy, attempt, null);
  }

  private ComputationRuntime createComputation(
      String runId,
      Path runDirectory,
      SystemConfig system,
      ProgressSink progress,
      java.util.function.LongSupplier remainingCalls)
      throws java.io.IOException {
    ComputationConfig config = system.computation();
    ComputationLimits limits =
        new ComputationLimits(
            config.enabled(),
            config.typedToolsEnabled(),
            config.sandboxedPythonEnabled(),
            config.targetedFalsificationFastPath(),
            config.boundedTypedProbeFastPath(),
            config.boundedTypedProbeMaxCases(),
            config.softExperimentsPerPath(),
            config.hardExperimentsPerPath(),
            config.maxTotalCpuSeconds(),
            config.maxCasesPerExperiment(),
            config.maxOutputChars(),
            config.broadSearchAfterStalledRounds(),
            config.broadSearchRequiresMetaReview(),
            config.cacheResults());
    Path python = locator.pythonExecutable();
    PythonSidecarWorkerPool workers =
        new PythonSidecarWorkerPool(
            python, locator.sidecarService(), 2, Duration.ofSeconds(10));
    List<ExternalComputationHandler> external = new ArrayList<>();
    external.add(
        new PythonSidecarComputationHandler(workers, DesktopRuntimeLocator.SIDECAR_TOOL_VERSION));

    boolean sandboxEnabled = config.sandboxedPythonEnabled();
    if (sandboxEnabled) {
      SandboxSettings sandbox = sandboxSettings(config);
      String docker = locator.dockerExecutable();
      DockerSandboxPreflight.Result checked = dockerPreflight.verify(docker, sandbox.image());
      progress.emit(
          "sandbox_preflight",
          "goal_preflight",
          null,
          "completed",
          "Docker sandbox and pinned image are ready (server "
              + checked.serverVersion()
              + ")",
          null);
      Path temp = runDirectory.resolve("sandbox-tmp");
      Files.createDirectories(temp);
      external.add(
          new SandboxedPythonComputationHandler(
              sandbox,
              new PythonSandboxAstValidator(python, locator.sandboxValidator()),
              docker,
              temp));
    }
    ComputationHandlerRegistry registry =
        new ComputationHandlerRegistry(new CompositeExternalComputationHandler(external));
    ArtifactStore computationArtifacts =
        new ArtifactStore(runDirectory.resolve("runtime-artifacts"), runId);
    ComputationBroker broker =
        new ComputationBroker(
            runId,
            limits,
            registry,
            new InMemoryComputationCache(),
            new ArtifactStoreComputationArtifactStore(computationArtifacts));
    return new ComputationRuntime(broker, sandboxEnabled, remainingCalls);
  }

  private static SandboxSettings sandboxSettings(ComputationConfig config) {
    return new SandboxSettings(
        config.sandboxedPythonEnabled(),
        config.sandboxImage(),
        Duration.ofMillis(Math.max(1L, Math.round(config.sandboxTimeoutSeconds() * 1_000.0d))),
        config.sandboxMemoryMb(),
        config.sandboxCpus(),
        config.sandboxPidsLimit(),
        config.maxOutputChars());
  }

  private static ExperimentProgram sandboxProgram(
      ExperimentSpec spec, SandboxProgramDraft draft) {
    ObjectNode input = JsonNodeFactory.instance.objectNode();
    input.put("type", "object");
    input.putObject("properties").putObject("seed").put("type", "integer");
    input.putArray("required").add("seed");
    input.put("additionalProperties", true);

    ObjectNode output = JsonNodeFactory.instance.objectNode();
    output.put("type", "object");
    ObjectNode properties = output.putObject("properties");
    properties.putObject("outcome").put("type", "string");
    properties.putObject("cases_checked").put("type", "integer");
    properties.putObject("scope").put("type", "object");
    properties.putObject("exact_arithmetic").put("type", "boolean");
    properties.putObject("counterexample").put("type", "object");
    properties.putObject("certificate").put("type", "object");
    output
        .putArray("required")
        .add("outcome")
        .add("cases_checked")
        .add("scope")
        .add("exact_arithmetic");
    output.put("additionalProperties", true);
    return new ExperimentProgram(
        null,
        null,
        draft.dependencies(),
        spec.experimentId(),
        input,
        output,
        draft.source());
  }

  private static ExperimentSpec bindExperiment(
      ExperimentSpec source, String routeId, String agentId) {
    return new ExperimentSpec(
        source.arguments(),
        source.assumptions(),
        source.broadSearch(),
        source.decisionIfConfirmed(),
        source.decisionIfRefuted(),
        source.domains(),
        source.exactArithmetic(),
        null,
        source.experimentId(),
        source.maxCases(),
        source.method(),
        source.noncomputationalAlternative(),
        source.parentCheckpointId(),
        routeId,
        source.purpose(),
        source.reasoningBasis(),
        null,
        agentId,
        JsonNodeFactory.instance.objectNode(),
        source.seed(),
        source.targetClaim(),
        source.typedToolGap(),
        source.whyComputationIsNeeded(),
        source.targetClaimId(),
        source.claimEvidenceSemanticBinding());
  }

  private static ProofAttempt bindAttempt(
      ProofAttempt source,
      StructuredCallResult<?> call,
      AgentRuntime author,
      String routeId,
      String problemHash) {
    return new ProofAttempt(
        author.id(),
        "attempt-" + routeId,
        source.candidateConjectures(),
        source.checkpointIds(),
        source.deadEnds(),
        call.attemptedAgents(),
        source.falsificationChecks(),
        source.finalAnswer(),
        source.latestCheckpointId(),
        routeId,
        problemHash,
        source.proofSketch(),
        source.proofSteps(),
        source.proposedLemmas(),
        call.responseArtifactRef(),
        source.resumedFromCheckpointId(),
        0,
        source.segmentCount(),
        source.selfConfidence(),
        source.status(),
        routeId,
        source.unresolvedGaps(),
        call.usage(),
        source.claimSemanticContextManifestVersion() == 1
            ? source.claimSemanticContextBindings()
            : List.of(),
        1);
  }

  private static VerificationReport bindReview(
      VerificationReport source,
      StructuredCallResult<?> call,
      AgentRuntime reviewer,
      String targetId,
      String targetType,
      VerificationStage stage) {
    return new VerificationReport(
        reviewer.id(),
        source.checkedDependencies(),
        source.conciseFeedback(),
        source.confidence(),
        source.failureLevel(),
        source.firstErrorStep(),
        source.issues(),
        source.problemIntegrityOk(),
        call.responseArtifactRef(),
        source.reportId(),
        stage,
        source.structuredIssues(),
        targetId,
        targetType,
        source.toolRequests(),
        source.toolResults(),
        call.usage(),
        source.verdict());
  }

  private static <T> StructuredCallResult<T> callStage(
      StructuredAgentRunner runner,
      PromptFactory prompts,
      SystemConfig config,
      ProgressSink progress,
      String runId,
      String idempotencyKey,
      String stage,
      Class<T> responseType,
      Map<String, ?> context,
      AgentRuntime agent,
      String budgetBucket,
      String summary) {
    progress.emit("agent_started", stage, agent.id(), "running", summary, null);
    StructuredCallResult<T> result =
        runner.call(
            runId,
            idempotencyKey,
            roleForStage(stage),
            prompts.typedStage(
                stage,
                responseType,
                context,
                0.0d,
                outputTokens(config, stage),
                false),
            agent,
            budgetBucket);
    runner.apply(result, "desktop:" + idempotencyKey);
    progress.emit(
        "agent_completed",
        stage,
        agent.id(),
        "completed",
        summary + " completed",
        result.responseArtifactRef());
    return result;
  }

  private static String roleForStage(String stage) {
    return switch (stage) {
      case "triage", "strategy_generation" -> "planner";
      case "independent_exploration" -> "explorer";
      case "detailed_verification" -> "detailed_verifier";
      case "experiment_codegen" -> "experimenter";
      case "synthesis" -> "synthesizer";
      case "final_verification" -> "final_verifier";
      default -> "general";
    };
  }

  private static int outputTokens(SystemConfig config, String stage) {
    return config.runtime().stageOutputTokenLimits().getOrDefault(stage, 16_000);
  }

  private static List<String> claimIds(String prefix, List<ProofStep> steps) {
    List<String> result = new ArrayList<>();
    for (int index = 0; index < steps.size(); index++) {
      result.add(prefix + "-step-" + (index + 1));
    }
    return List.copyOf(result);
  }

  private static List<String> verifiedRouteClaims(List<RouteView> routes) {
    return routes.stream()
        .filter(route -> "verified".equals(route.status()))
        .flatMap(route -> route.claimIds().stream())
        .toList();
  }

  private static List<RouteWork> routeWorkWithReviews(
      List<RouteWork> all, List<RouteWork> admitted) {
    Map<String, RouteWork> reviewed = new LinkedHashMap<>();
    admitted.forEach(route -> reviewed.put(route.routeId(), route));
    return all.stream().map(route -> reviewed.getOrDefault(route.routeId(), route)).toList();
  }

  private static String renderReport(
      DesktopLiveRuntimeFactory.PreparedRuntime runtime,
      FinalProof finalProof,
      VerificationReport finalReview,
      List<RouteWork> routes,
      List<RouteView> routeViews,
      List<ComputationTrace> computations,
      CallLedger ledger) {
    StringBuilder report = new StringBuilder();
    report.append("### Runtime controls\n\n");
    report.append("- Profile: `").append(runtime.profile()).append("`\n");
    report.append("- Provider/model: `deepseek/deepseek-v4-pro`\n");
    report.append("- Reasoning effort: `max`\n");
    report.append("- Credential isolation: `5 DPAPI-backed agent keys`\n");
    report
        .append("- Sandboxed Python: `")
        .append(runtime.sandboxEnabled() ? "enabled" : "disabled")
        .append("`\n\n");

    if (finalProof != null) {
      report.append("### Final answer\n\n").append(finalProof.answer()).append("\n\n");
      report.append("### Proof steps\n\n");
      int index = 1;
      for (ProofStep step : finalProof.proofSteps()) {
        report
            .append(index++)
            .append(". **")
            .append(step.statement())
            .append("**\n\n   ")
            .append(step.justification())
            .append("\n\n");
      }
      if (!finalProof.caveats().isEmpty()) {
        report.append("### Caveats\n\n");
        finalProof.caveats().forEach(value -> report.append("- ").append(value).append("\n"));
        report.append('\n');
      }
    }

    report.append("### Independent routes\n\n");
    Map<String, RouteView> views = new LinkedHashMap<>();
    routeViews.forEach(view -> views.put(view.routeId(), view));
    for (RouteWork route : routes) {
      RouteView view = views.get(route.routeId());
      report
          .append("- `")
          .append(route.routeId())
          .append("` by `")
          .append(route.author().id())
          .append("`: ")
          .append(view == null ? "unverified" : view.status())
          .append(". ")
          .append(view == null ? "No route result." : view.summary())
          .append("\n");
    }
    report.append('\n');

    if (!computations.isEmpty()) {
      report.append("### Bounded computation evidence\n\n");
      for (ComputationTrace computation : computations) {
        report
            .append("- `")
            .append(computation.routeId())
            .append("` requested `")
            .append(computation.spec().method().value())
            .append("`: ")
            .append(computation.decision().decision().value())
            .append(" (`")
            .append(computation.decision().ruleId())
            .append("`)");
        if (computation.result() != null) {
          report
              .append(", outcome `")
              .append(computation.result().outcome().value())
              .append("`, cases ")
              .append(computation.result().casesChecked());
        }
        report.append(".\n");
      }
      report.append('\n');
    }

    report.append("### Final independent verification\n\n");
    if (finalReview == null) {
      report.append("No candidate reached the final verification gate.\n\n");
    } else {
      report
          .append("- Verifier: `")
          .append(finalReview.agentId())
          .append("`\n- Verdict: `")
          .append(finalReview.verdict().value())
          .append("`\n- Problem integrity: `")
          .append(finalReview.problemIntegrityOk())
          .append("`\n- Feedback: ")
          .append(finalReview.conciseFeedback())
          .append("\n\n");
    }
    report
        .append("### Usage\n\n- Provider calls: ")
        .append(ledger.totals().calls())
        .append("\n- Tokens: ")
        .append(ledger.totals().totalTokens())
        .append("\n- Estimated cost (USD): ")
        .append(ledger.totals().costUsd().toPlainString())
        .append('\n');
    return String.valueOf(DesktopApiModel.redact(report.toString()));
  }

  static String boundedUtf8(String value, int maximumBytes) {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    if (encoded.length <= maximumBytes) {
      return value;
    }
    int low = 0;
    int high = value.length();
    while (low < high) {
      int middle = (low + high + 1) >>> 1;
      int bytes = value.substring(0, middle).getBytes(StandardCharsets.UTF_8).length;
      if (bytes <= maximumBytes - 64) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }
    return value.substring(0, low) + "\n\n[REPORT TRUNCATED]\n";
  }

  static String safeFailureDetail(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof ProviderCircuitOpenError circuitOpen) {
        StringBuilder detail =
            new StringBuilder("provider circuit open; retry_after_seconds=")
                .append(Math.max(0L, circuitOpen.retryAfter().toSeconds()));
        Throwable cause = circuitOpen.getCause();
        while (cause != null && !(cause instanceof ProviderException)) {
          cause = cause.getCause();
        }
        if (cause != null) {
          ProviderException providerFailure = (ProviderException) cause;
          detail.append("; provider_error=").append(providerFailure.kind());
          if (providerFailure.statusCode() != null) {
            detail.append("; http_status=").append(providerFailure.statusCode());
          }
        }
        return detail.toString();
      }
      String message = current.getMessage();
      if (message != null
          && (message.startsWith("Docker sandbox ")
              || message.startsWith("checkpoint immutable identity ")
              || message.startsWith("unsupported desktop solve checkpoint schema")
              || message.startsWith("live profile has no ")
              || message.startsWith("no independent final reviewer ")
              || message.startsWith("mandatory negative evidence "))) {
        String singleLine = message.replace('\r', ' ').replace('\n', ' ').strip();
        String bounded = singleLine.length() <= 1_000 ? singleLine : singleLine.substring(0, 1_000);
        return String.valueOf(DesktopApiModel.redact(bounded));
      }
      current = current.getCause();
    }
    return "";
  }

  static String safeFailureLocation(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      for (StackTraceElement frame : current.getStackTrace()) {
        String className = frame.getClassName();
        if (!className.startsWith("io.github.aililuola.mathproofmesh.")) {
          continue;
        }
        String simpleClass = className.substring(className.lastIndexOf('.') + 1);
        String location = simpleClass + "#" + frame.getMethodName();
        return frame.getLineNumber() > 0 ? location + ":" + frame.getLineNumber() : location;
      }
      current = current.getCause();
    }
    return "";
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  static ExecutionUsage executionUsage(UsageTotals totals) {
    Objects.requireNonNull(totals, "totals");
    return new ExecutionUsage(
        totals.calls(),
        totals.inputTokens(),
        totals.outputTokens(),
        totals.costUsd(),
        totals.latencyMs());
  }

  private static ExecutionUsage failureUsage(
      Path runDirectory, String runId, LiveExecutionContext context) {
    if (context == null) {
      return ExecutionUsage.zero();
    }
    UsageTotals liveTotals = context.ledger().totals();
    List<ProviderCallUsageEvidence> repositoryEvidence = List.of();
    try {
      repositoryEvidence = repositoryEvidence(context.providerCalls(), runId);
    } catch (RuntimeException ignored) {
      // The live ledger remains a safe aggregate fallback if repository recovery fails.
    }
    List<ProviderCallUsageEvidence> artifactEvidence = List.of();
    try {
      artifactEvidence = ProviderUsageRecovery.recoverEvidence(runDirectory, context.config());
    } catch (RuntimeException | java.io.IOException ignored) {
      // A damaged optional artifact cannot erase the live ledger's committed usage.
    }
    try {
      List<ProviderCallUsageEvidence> evidence =
          ProviderUsageRecovery.mergeEvidence(repositoryEvidence, artifactEvidence);
      UsageTotals evidenceTotals = ProviderUsageRecovery.totals(evidence);
      if (dominates(evidenceTotals, liveTotals)) {
        return executionUsage(evidenceTotals, evidence);
      }
    } catch (RuntimeException ignored) {
      // Conflicting request evidence is not guessed into a cumulative failure result.
    }
    return executionUsage(liveTotals);
  }

  private static List<ProviderCallUsageEvidence> repositoryEvidence(
      ProviderCallRepository repository, String runId) {
    List<ProviderCallUsageEvidence> evidence = new ArrayList<>();
    for (ProviderCallRecord call : repository.findByRun(runId)) {
      if (call.state() != ProviderCallState.SUCCEEDED
          && call.state() != ProviderCallState.AMBIGUOUS) {
        continue;
      }
      String requestId = call.requestId();
      if (requestId == null || requestId.isBlank()) {
        requestId = "provider-call:" + call.callId();
      }
      evidence.add(
          new ProviderCallUsageEvidence(
              requestId,
              call.inputTokens(),
              call.outputTokens(),
              call.costUsd().add(call.possibleDuplicateCostUsd()),
              call.latencyMs(),
              call.responseArtifactHash()));
    }
    return List.copyOf(evidence);
  }

  private static boolean dominates(UsageTotals candidate, UsageTotals other) {
    return candidate.calls() >= other.calls()
        && candidate.inputTokens() >= other.inputTokens()
        && candidate.outputTokens() >= other.outputTokens()
        && candidate.costUsd().compareTo(other.costUsd()) >= 0
        && candidate.latencyMs() >= other.latencyMs();
  }

  private static ExecutionUsage executionUsage(
      UsageTotals totals, List<ProviderCallUsageEvidence> evidence) {
    Objects.requireNonNull(totals, "totals");
    return new ExecutionUsage(
        totals.calls(),
        totals.inputTokens(),
        totals.outputTokens(),
        totals.costUsd(),
        totals.latencyMs(),
        evidence);
  }

  private static int safeInt(long value) {
    return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
  }

  private static final class AuditedProgressSink implements ProgressSink {
    private final ActivityStream activity;
    private final ProgressSink downstream;
    private final Map<String, String> initialTypes = new LinkedHashMap<>();
    private String activeStage;
    private String terminalParentTaskId;

    private AuditedProgressSink(ActivityStream activity, ProgressSink downstream) {
      this.activity = Objects.requireNonNull(activity, "activity");
      this.downstream = Objects.requireNonNull(downstream, "downstream");
    }

    @Override
    public synchronized void emit(
        String type,
        String stage,
        String agentId,
        String status,
        String summary,
        String reference) {
      String taskId = activityTaskId(type, stage, agentId);
      String initialType =
          initialTypes.computeIfAbsent(
              taskId, ignored -> activityInitialType(type, agentId));
      Map<String, Object> metrics =
          reference == null || reference.isBlank()
              ? Map.of()
              : Map.of("result_reference", reference);
      activity.emit(
          type,
          activityStatus(status),
          activityImportance(type),
          stage,
          taskId,
          isRunTerminal(type) ? terminalParentTaskId : null,
          null,
          initialType,
          summary,
          "",
          agentId,
          null,
          metrics);
      if (("stage_started".equals(type) || "stage_changed".equals(type))
          && "running".equals(status)
          && agentId == null) {
        activeStage = stage;
      } else if (("stage_completed".equals(type) || "stage_failed".equals(type))
          && Objects.equals(activeStage, stage)) {
        activeStage = null;
      }
      downstream.emit(type, stage, agentId, status, summary, reference);
    }

    private synchronized void failActiveStage(String summary) {
      if (activeStage == null || activeStage.isBlank()) {
        return;
      }
      String failedStage = activeStage;
      terminalParentTaskId = activityTaskId("stage_failed", failedStage, null);
      emit("stage_failed", failedStage, null, "failed", summary, null);
    }

    private static String activityInitialType(String type, String agentId) {
      if (agentId != null && !agentId.isBlank()) {
        return "agent_call";
      }
      return "computation".equals(type) ? "computation_experiment" : "stage";
    }

    private static boolean isRunTerminal(String type) {
      return "run_failed".equals(type) || "run_cancelled".equals(type);
    }

    private static String activityTaskId(String type, String stage, String agentId) {
      String safeStage = stage == null || stage.isBlank() ? "run" : stage;
      if (agentId != null && !agentId.isBlank()) {
        return ReasoningTraceBinding.agentTaskId(safeStage, agentId);
      }
      if ("computation".equals(type)) {
        return "computation:" + safeStage;
      }
      return "stage:" + safeStage;
    }

    private static ActivityStatus activityStatus(String status) {
      return switch (status == null ? "" : status) {
        case "running", "queued" -> ActivityStatus.RUNNING;
        case "failed", "cancelled" -> ActivityStatus.FAILED;
        case "warning", "unverified", "rejected" -> ActivityStatus.WARNING;
        case "info" -> ActivityStatus.INFO;
        default -> ActivityStatus.COMPLETED;
      };
    }

    private static ActivityImportance activityImportance(String type) {
      return switch (type) {
        case "stage_changed", "stage_started", "stage_completed", "stage_failed", "run_failed",
                "result", "verification" ->
            ActivityImportance.MAJOR;
        case "heartbeat" -> ActivityImportance.DETAIL;
        default -> ActivityImportance.NORMAL;
      };
    }
  }

  private record ComputationRuntime(
      ComputationBroker broker,
      boolean sandboxEnabled,
      java.util.function.LongSupplier remainingCalls) {}

  private record LiveExecutionContext(
      CallLedger ledger, ProviderCallRepository providerCalls, SystemConfig config) {}

  private record ComputationTrace(
      String routeId,
      ExperimentSpec spec,
      ComputationDecision decision,
      ExperimentResult result) {
    Map<String, Object> publicView() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("route_id", routeId);
      view.put("method", spec.method().value());
      view.put("target_claim", spec.targetClaim());
      view.put("decision", decision.decision().value());
      view.put("policy_rule", decision.ruleId());
      if (result != null) {
        view.put("outcome", result.outcome().value());
        view.put("cases_checked", result.casesChecked());
        view.put("scope", result.scope());
        view.put("verification_notes", result.verificationNotes());
      }
      return Map.copyOf(view);
    }
  }

  private record RouteWork(
      String routeId,
      AgentRuntime author,
      StrategyCard strategy,
      ProofAttempt attempt,
      VerificationReport review) {
    RouteWork withReview(VerificationReport value) {
      return new RouteWork(routeId, author, strategy, attempt, value);
    }
  }
}
