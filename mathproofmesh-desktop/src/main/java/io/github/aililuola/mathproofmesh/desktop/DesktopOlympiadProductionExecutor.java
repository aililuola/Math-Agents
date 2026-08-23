package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.api.SolveRequest;
import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.desktop.benchmark.BenchmarkSecretSet;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadBenchmarkCostEstimator;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadBenchmarkHarness;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadBenchmarkPlan;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadPromptTransportGuard;
import io.github.aililuola.mathproofmesh.orchestration.PricingSnapshot;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.JdkHttpTransport;
import io.github.aililuola.mathproofmesh.provider.ProviderCallRecord;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Executes one benchmark Run through the production desktop coordinator and provider stack. */
final class DesktopOlympiadProductionExecutor implements OlympiadBenchmarkHarness.RunExecutor {
  private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);
  private static final int ESTIMATED_INPUT_TOKENS_PER_CALL = 16_000;
  private static final int MINIMUM_OUTPUT_TOKENS = 512;

  private final Path projectRoot;
  private final BenchmarkSecretSet secrets;
  private final BigDecimal globalCostCapUsd;
  private final DesktopRuntimeLocator locator;
  private final SystemConfig baseConfig;
  private final PricingSnapshot basePricing;
  private BigDecimal committedCostUsd = BigDecimal.ZERO;

  DesktopOlympiadProductionExecutor(
      Path projectRoot, BenchmarkSecretSet secrets, BigDecimal globalCostCapUsd) {
    this.projectRoot = normalize(projectRoot, "projectRoot");
    this.secrets = Objects.requireNonNull(secrets, "secrets");
    this.globalCostCapUsd = Objects.requireNonNull(globalCostCapUsd, "globalCostCapUsd");
    this.locator = new DesktopRuntimeLocator(this.projectRoot, null);
    this.baseConfig = locator.loadProfile("deepseek-v4-pro.yaml");
    this.basePricing = new DesktopBudgetRuntime("benchmark-pricing", baseConfig).pricing();
    OlympiadBenchmarkCostEstimator.Estimate estimate =
        OlympiadBenchmarkCostEstimator.estimate(
            OlympiadBenchmarkPlan.fullSchedule(), basePricing);
    if (!estimate.coveredBy(globalCostCapUsd)) {
      throw new IllegalStateException("benchmark global cost cap does not cover the frozen plan");
    }
  }

  PricingSnapshot pricing() {
    return basePricing;
  }

  @Override
  public synchronized OlympiadBenchmarkHarness.RunOutcome execute(
      OlympiadBenchmarkHarness.RunRequest request) {
    Objects.requireNonNull(request, "request");
    SystemConfig config = benchmarkConfig(baseConfig, request.spec(), basePricing);
    Map<String, String> credentials = credentials();
    Map<String, String> providerKeyLabels = providerKeyLabels(config);
    OlympiadPromptTransportGuard.Audit promptAudit =
        new OlympiadPromptTransportGuard.Audit(request.problem().sha256());
    InMemoryProviderCallRepository calls = new InMemoryProviderCallRepository();
    BenchmarkRuntimeAccess runtimes =
        new BenchmarkRuntimeAccess(
            config, credentials, request.problem(), providerKeyLabels, promptAudit);
    DesktopPaths paths = DesktopPaths.discover(request.workDirectory().resolve("desktop-data"));
    SettingsStore settings = new SettingsStore(paths.settingsFile(), new ObjectMapper());
    Path runDirectory = request.workDirectory().resolve("production-run");
    RunExecutionBackend.RunExecutionResult result;
    OlympiadBenchmarkHarness.RecoveryEvidence recovery = notApplicableRecovery();

    if (request.spec().kind() == OlympiadBenchmarkPlan.RunKind.CONTROLLED_RECOVERY) {
      StopAtBenchmarkBoundary stop = new StopAtBenchmarkBoundary(request.spec().recoveryBoundary());
      try {
        backend(paths, settings, runtimes, calls, stop)
            .execute(
                solveRequest(request),
                request.runId(),
                request.runId() + "-before-restart",
                runDirectory,
                noProgressOutput());
        throw new IllegalStateException("controlled recovery boundary was not observed");
      } catch (ControlledBenchmarkRestart expected) {
        if (!stop.observed()) {
          throw new IllegalStateException("controlled recovery did not persist its target boundary");
        }
      }
      StateProjection before = stateProjection(runDirectory);
      Set<String> providerCallsBefore = callIds(calls.findByRun(request.runId()));
      ResumeProgressCapture resumeProgress = new ResumeProgressCapture(runDirectory);
      result =
          backend(paths, settings, runtimes, calls, DesktopDurableBoundaryObserver.none())
              .execute(
                  solveRequest(request),
                  request.runId(),
                  request.runId() + "-after-restart",
                  runDirectory,
                  resumeProgress);
      recovery =
          recoveryEvidence(
              before,
              resumeProgress.requireProjection(),
              providerCallsBefore,
              calls.findByRun(request.runId()));
    } else {
      result =
          backend(paths, settings, runtimes, calls, DesktopDurableBoundaryObserver.none())
              .execute(
                  solveRequest(request),
                  request.runId(),
                  request.runId(),
                  runDirectory,
                  noProgressOutput());
    }

    committedCostUsd = committedCostUsd.add(result.usage().estimatedCostUsd());
    if (committedCostUsd.compareTo(globalCostCapUsd) > 0) {
      throw new IllegalStateException("benchmark global cost cap was exceeded");
    }
    OlympiadBenchmarkHarness.RunOutcome outcome =
        DesktopOlympiadEvidenceExporter.export(
            runDirectory,
            request.runId(),
            request.problem().sha256(),
            result,
            calls,
            recovery,
            Instant.now(),
            providerKeyLabels,
            promptAudit,
            redactedConfig(config, providerKeyLabels));
    System.out.println(
        "BENCHMARK_RUN="
            + request.spec().identity()
            + " STATUS="
            + outcome.finalStatus()
            + " CALLS="
            + outcome.usage().calls()
            + " COST_USD="
            + outcome.usage().costUsd().toPlainString());
    return outcome;
  }

  static SystemConfig benchmarkConfig(
      SystemConfig base,
      OlympiadBenchmarkPlan.RunSpec spec,
      PricingSnapshot pricing) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(spec, "spec");
    Objects.requireNonNull(pricing, "pricing");
    ObjectNode root = (ObjectNode) ContractObjectMapper.toTree(base);
    ObjectNode budget = (ObjectNode) root.path("budget");
    budget.put("max_total_calls", spec.tier().maximumCalls());
    budget.put("max_rounds", spec.tier().maximumRounds());
    budget.put("max_total_tokens", spec.tier().maximumTokens());
    budget.put("estimated_input_tokens_per_call", ESTIMATED_INPUT_TOKENS_PER_CALL);
    budget.put("max_cost_usd", maximumTierCost(spec, pricing).doubleValue());
    budget.put("scale_budget_with_difficulty", false);
    capSurpriseBudgetToExploratoryCapacity(root, base, spec.tier().maximumCalls());
    capOutputEnvelopesToFrozenTier(root, spec);
    ((ObjectNode) root.path("runtime")).put("save_raw_provider_responses", false);

    List<String> labels = new ArrayList<>();
    labels.add(spec.coordinationKeyLabel());
    labels.addAll(spec.researchKeyLabels());
    ArrayNode agents = (ArrayNode) root.path("agents");
    if (agents.size() != labels.size()) {
      throw new IllegalStateException("benchmark profile must contain exactly five agents");
    }
    for (int index = 0; index < agents.size(); index++) {
      ObjectNode agent = (ObjectNode) agents.get(index);
      agent.put("api_key_env", OlympiadBenchmarkPlan.keyEnvironmentName(labels.get(index)));
      agent.remove("api_key");
    }
    return ContractObjectMapper.read(root, SystemConfig.class);
  }

  static int benchmarkOutputTokenLimit(OlympiadBenchmarkPlan.RunSpec spec) {
    Objects.requireNonNull(spec, "spec");
    return Math.max(
        MINIMUM_OUTPUT_TOKENS,
        spec.tier().maximumTokens() / spec.tier().maximumCalls()
            - ESTIMATED_INPUT_TOKENS_PER_CALL);
  }

  private static void capOutputEnvelopesToFrozenTier(
      ObjectNode root, OlympiadBenchmarkPlan.RunSpec spec) {
    int outputLimit = benchmarkOutputTokenLimit(spec);
    ArrayNode agents = (ArrayNode) root.path("agents");
    for (JsonNode candidate : agents) {
      ObjectNode agent = (ObjectNode) candidate;
      agent.put(
          "max_output_tokens",
          Math.min(agent.path("max_output_tokens").asInt(), outputLimit));
    }

    ObjectNode stageLimits =
        (ObjectNode) root.path("runtime").path("stage_output_token_limits");
    List<String> stages = new ArrayList<>();
    stageLimits.fieldNames().forEachRemaining(stages::add);
    for (String stage : stages) {
      stageLimits.put(stage, Math.min(stageLimits.path(stage).asInt(), outputLimit));
    }

    ObjectNode continuation = (ObjectNode) root.path("continuation");
    continuation.put(
        "max_output_tokens_per_segment",
        Math.min(continuation.path("max_output_tokens_per_segment").asInt(), outputLimit));
  }

  private static void capSurpriseBudgetToExploratoryCapacity(
      ObjectNode root, SystemConfig base, int maximumCalls) {
    int verificationCalls =
        1
            + base.budget().highRiskVerifierReplicas()
            + base.scheduler().verificationCallSafetyMargin();
    int revisionCycles =
        Math.min(base.scheduler().reserveRevisionCycles(), base.budget().maxRevisions());
    int requestedReserve =
        1
            + verificationCalls
            + revisionCycles * (1 + verificationCalls)
            + base.scheduler().finishTransitionBufferCalls();
    int exploratoryCalls = maximumCalls - Math.min(maximumCalls, requestedReserve);
    int requestedSurpriseCalls = base.topology().inspiration().surpriseBudgetMinCalls();
    ObjectNode inspiration = (ObjectNode) root.path("topology").path("inspiration");
    inspiration.put(
        "surprise_budget_min_calls", Math.min(requestedSurpriseCalls, exploratoryCalls));
  }

  private static BigDecimal maximumTierCost(
      OlympiadBenchmarkPlan.RunSpec spec, PricingSnapshot pricing) {
    if (pricing.billingMode() == PricingSnapshot.BillingMode.BILLING_EXEMPT) {
      return BigDecimal.ZERO;
    }
    return BigDecimal.valueOf(spec.tier().maximumTokens())
        .multiply(pricing.inputPerMillion().max(pricing.outputPerMillion()))
        .divide(ONE_MILLION, 12, RoundingMode.CEILING)
        .stripTrailingZeros();
  }

  private DesktopLiveRunExecutionBackend backend(
      DesktopPaths paths,
      SettingsStore settings,
      DesktopLiveRuntimeAccess runtimes,
      InMemoryProviderCallRepository calls,
      DesktopDurableBoundaryObserver observer) {
    return new DesktopLiveRunExecutionBackend(
        paths,
        settings,
        runtimes,
        locator,
        new DockerSandboxPreflight(),
        () -> calls,
        observer);
  }

  private Map<String, String> credentials() {
    Map<String, String> values = new LinkedHashMap<>();
    for (String label : OlympiadBenchmarkPlan.KEY_LABELS) {
      String environmentName = OlympiadBenchmarkPlan.keyEnvironmentName(label);
      values.put(environmentName, secrets.credential(environmentName));
    }
    return Map.copyOf(values);
  }

  private static Map<String, String> providerKeyLabels(SystemConfig config) {
    Map<String, String> labels = new LinkedHashMap<>();
    for (AgentConfig agent : config.agents()) {
      String environment = agent.apiKeyEnv();
      String label =
          OlympiadBenchmarkPlan.KEY_LABELS.stream()
              .filter(
                  candidate ->
                      OlympiadBenchmarkPlan.keyEnvironmentName(candidate).equals(environment))
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("agent credential is not benchmark-bound"));
      labels.put(agent.id(), label);
    }
    return Map.copyOf(labels);
  }

  private static SolveRequest solveRequest(OlympiadBenchmarkHarness.RunRequest request) {
    return new SolveRequest(
        request.problem().text(), request.runId(), null, "proof_control_active");
  }

  private static RunExecutionBackend.ProgressSink noProgressOutput() {
    return (type, stage, agentId, status, summary, reference) -> {};
  }

  private static OlympiadBenchmarkHarness.RecoveryEvidence notApplicableRecovery() {
    return new OlympiadBenchmarkHarness.RecoveryEvidence(
        0, 0, 0, "not-applicable", "not-applicable");
  }

  private static OlympiadBenchmarkHarness.RecoveryEvidence recoveryEvidence(
      StateProjection before,
      StateProjection after,
      Set<String> providerCallsBefore,
      List<ProviderCallRecord> providerCallsAfter) {
    Set<String> finalCallIds = callIds(providerCallsAfter);
    Set<String> idempotency = new HashSet<>();
    int duplicates = 0;
    for (ProviderCallRecord call : providerCallsAfter) {
      if (!idempotency.add(call.idempotencyKey())) {
        duplicates++;
      }
    }
    if (!finalCallIds.containsAll(providerCallsBefore)) {
      duplicates =
          Math.addExact(
              duplicates,
              Math.toIntExact(
                  providerCallsBefore.stream()
                      .filter(id -> !finalCallIds.contains(id))
                      .count()));
    }
    int taskLosses =
        Math.toIntExact(
            before.taskIds().stream().filter(id -> !after.taskIds().contains(id)).count());
    int stateDrifts = sameHash(before.authorityHash(), after.authorityHash()) ? 0 : 1;
    return new OlympiadBenchmarkHarness.RecoveryEvidence(
        duplicates, taskLosses, stateDrifts, before.authorityHash(), after.authorityHash());
  }

  private static Set<String> callIds(List<ProviderCallRecord> calls) {
    Set<String> ids = new LinkedHashSet<>();
    calls.forEach(call -> ids.add(call.callId()));
    return Set.copyOf(ids);
  }

  private static StateProjection stateProjection(Path runDirectory) {
    Path statePath = runDirectory.resolve("structured/desktop-solve-state.json");
    try {
      JsonNode state =
          ContractObjectMapper.parseTree(Files.readString(statePath, StandardCharsets.UTF_8));
      String rootHash = state.path("problem").path("goal_hash").asText();
      String negativeHash =
          io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadProblemCatalog.sha256(
              ContractObjectMapper.write(
                  state.path("typedMemory").path("negativeKnowledge")));
      String authorityHash =
          io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadProblemCatalog.sha256(
              rootHash + "\u0000" + negativeHash);
      Set<String> taskIds = new LinkedHashSet<>();
      for (JsonNode task : state.path("researchTasks").path("tasks")) {
        String id = task.path("item").path("workItemId").asText();
        if (!id.isBlank()) {
          taskIds.add(id);
        }
      }
      return new StateProjection(authorityHash, Set.copyOf(taskIds));
    } catch (IOException exception) {
      throw new IllegalStateException("benchmark checkpoint could not be inspected", exception);
    }
  }

  private static String redactedConfig(
      SystemConfig config, Map<String, String> providerKeyLabels) {
    ObjectNode root = (ObjectNode) ContractObjectMapper.toTree(config);
    for (JsonNode value : root.path("agents")) {
      ObjectNode agent = (ObjectNode) value;
      agent.remove("api_key");
      agent.put("key_label", providerKeyLabels.get(agent.path("id").asText()));
      agent.put("credential_status", "configured-in-memory");
    }
    root.put("benchmark_id", OlympiadBenchmarkPlan.BENCHMARK_ID);
    root.put("hidden_reasoning_persistence", false);
    return ContractObjectMapper.write(root) + "\n";
  }

  private static Path normalize(Path path, String field) {
    return Objects.requireNonNull(path, field).toAbsolutePath().normalize();
  }

  private static boolean sameHash(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII),
        right.getBytes(StandardCharsets.US_ASCII));
  }

  private record StateProjection(String authorityHash, Set<String> taskIds) {
    private StateProjection {
      authorityHash = Objects.requireNonNull(authorityHash, "authorityHash");
      taskIds = Set.copyOf(Objects.requireNonNull(taskIds, "taskIds"));
    }

    @Override
    public Set<String> taskIds() {
      return Set.copyOf(taskIds);
    }
  }

  private static final class BenchmarkRuntimeAccess implements DesktopLiveRuntimeAccess {
    private final DesktopLiveRuntimeFactory.PreparedRuntime prepared;
    private final io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadProblemCatalog.ProblemPrompt
        problem;
    private final Map<String, String> providerKeyLabels;
    private final OlympiadPromptTransportGuard.Audit promptAudit;

    private BenchmarkRuntimeAccess(
        SystemConfig config,
        Map<String, String> credentials,
        io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadProblemCatalog.ProblemPrompt
            problem,
        Map<String, String> providerKeyLabels,
        OlympiadPromptTransportGuard.Audit promptAudit) {
      this.prepared =
          new DesktopLiveRuntimeFactory.PreparedRuntime(
              "olympiad-five-key-real", config, credentials, true, false);
      this.problem = Objects.requireNonNull(problem, "problem");
      this.providerKeyLabels = Map.copyOf(providerKeyLabels);
      this.promptAudit = Objects.requireNonNull(promptAudit, "promptAudit");
    }

    @Override
    public DesktopLiveRuntimeFactory.PreparedRuntime prepare(
        String requestedProfile, DesktopSettings settings) {
      Objects.requireNonNull(settings, "settings");
      return prepared;
    }

    @Override
    public ProviderClientRegistry openProviders(
        DesktopLiveRuntimeFactory.PreparedRuntime runtime) {
      if (runtime != prepared) {
        throw new IllegalArgumentException("benchmark runtime identity changed");
      }
      return new ProviderClientRegistry(
          prepared.config(),
          Map.of(),
          agent ->
              new OlympiadPromptTransportGuard(
                  new JdkHttpTransport(),
                  problem,
                  Objects.requireNonNull(providerKeyLabels.get(agent.id()), "key label"),
                  promptAudit),
          true,
          prepared.credentials()::get);
    }
  }

  private static final class StopAtBenchmarkBoundary
      implements DesktopDurableBoundaryObserver {
    private final OlympiadBenchmarkPlan.RecoveryBoundary target;
    private boolean observed;

    private StopAtBenchmarkBoundary(OlympiadBenchmarkPlan.RecoveryBoundary target) {
      this.target = Objects.requireNonNull(target, "target");
      if (target == OlympiadBenchmarkPlan.RecoveryBoundary.NONE) {
        throw new IllegalArgumentException("controlled recovery requires a boundary");
      }
    }

    @Override
    public synchronized void afterDurableBoundary(
        DesktopDurableBoundary boundary, Path checkpoint) {
      if (observed || !matches(boundary, checkpoint)) {
        return;
      }
      observed = true;
      throw new ControlledBenchmarkRestart();
    }

    @Override
    public int maximumResearchInFlight(int configuredMaximum) {
      return target == OlympiadBenchmarkPlan.RecoveryBoundary.AFTER_FIRST_RESULT_DURABLE
          ? 1
          : configuredMaximum;
    }

    private synchronized boolean observed() {
      return observed;
    }

    private boolean matches(DesktopDurableBoundary boundary, Path checkpoint) {
      if (target
          == OlympiadBenchmarkPlan.RecoveryBoundary.AFTER_V22_ATOMIC_CHECKPOINT) {
        if (boundary != DesktopDurableBoundary.CHECKPOINT_V22) {
          return false;
        }
        try {
          JsonNode state =
              ContractObjectMapper.parseTree(
                  Files.readString(checkpoint, StandardCharsets.UTF_8));
          for (JsonNode epoch : state.path("researchEpochs").path("epochs")) {
            if ("COMMITTED".equals(epoch.path("status").asText())) {
              return true;
            }
          }
          return false;
        } catch (IOException exception) {
          throw new IllegalStateException("benchmark checkpoint boundary could not be read", exception);
        }
      }
      return switch (target) {
        case AFTER_FIRST_RESULT_DURABLE ->
            boundary == DesktopDurableBoundary.FIRST_RESULT_DURABLE;
        case AFTER_ALL_SETTLED_BEFORE_STABLE_MERGE ->
            boundary == DesktopDurableBoundary.ALL_SETTLED;
        case AFTER_MERGE_PREPARED_BEFORE_AUTHORITY_COMMIT ->
            boundary == DesktopDurableBoundary.MERGE_PREPARED;
        case AFTER_V22_ATOMIC_CHECKPOINT, NONE -> false;
      };
    }
  }

  private static final class ResumeProgressCapture
      implements RunExecutionBackend.ProgressSink {
    private final Path runDirectory;
    private StateProjection projection;

    private ResumeProgressCapture(Path runDirectory) {
      this.runDirectory = normalize(runDirectory, "runDirectory");
    }

    @Override
    public synchronized void emit(
        String type,
        String stage,
        String agentId,
        String status,
        String summary,
        String reference) {
      if (projection == null && "checkpoint_resumed".equals(type)) {
        projection = stateProjection(runDirectory);
      }
    }

    private synchronized StateProjection requireProjection() {
      if (projection == null) {
        throw new IllegalStateException("controlled recovery did not restore a checkpoint");
      }
      return projection;
    }
  }

  private static final class ControlledBenchmarkRestart extends Error {
    private static final long serialVersionUID = 1L;
  }
}
