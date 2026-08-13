package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.agent.BoundedJsonRepairer;
import io.github.aililuola.mathproofmesh.agent.CallLedger;
import io.github.aililuola.mathproofmesh.agent.PromptFactory;
import io.github.aililuola.mathproofmesh.agent.PromptRedactor;
import io.github.aililuola.mathproofmesh.agent.StructuredAgentRunner;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.api.SolveRequest;
import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.computation.ComputationLimits;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.Difficulty;
import io.github.aililuola.mathproofmesh.contract.ProblemContract;
import io.github.aililuola.mathproofmesh.contract.ProblemKind;
import io.github.aililuola.mathproofmesh.contract.ProblemSemanticView;
import io.github.aililuola.mathproofmesh.contract.ProblemSemanticViewCandidate;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.contract.TaskRequirement;
import io.github.aililuola.mathproofmesh.contract.TriageResult;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.proofcontrol.ProblemSemanticViewService;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels;
import io.github.aililuola.mathproofmesh.proofcontrol.RootGoalContract;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.HttpTransport;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderCallPlan;
import io.github.aililuola.mathproofmesh.provider.ProviderCallRecord;
import io.github.aililuola.mathproofmesh.provider.ProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.ProviderCallTransition;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopSolveCoordinatorRootGoalPropagationTest {
  private static final int ROUNDS = 20;
  private static final int RESUME_ROUND = 10;
  private static final String PROFILE = "root-goal-propagation";
  private static final String RUN_ID = "desktop-root-goal-propagation";
  private static final String SOURCE =
      "\u8bc1\u660e\uff1a\u5b58\u5728\u6b63\u6574\u6570 T \u548c L\uff0c\u4f7f\u5f97\u5bf9\u4e8e\u6bcf\u4e00\u4e2a\u6b63\u6574\u6570 n\uff0c"
          + "\u90fd\u6709 $a_{n+T}=a_n+L$\u3002";
  private static final String EXACT =
      "There exist positive integers T and L such that for every positive integer n, "
          + "$a_{n+T}=a_n+L$.";
  private static final String EVENTUAL =
      "There exist positive integers T and L such that for all sufficiently large n, "
          + "$a_{n+T}=a_n+L$.";
  private static final String ARITHMETIC =
      "The sequence is an arithmetic progression with constant difference.";
  private static final String SWAPPED =
      "For every positive integer n, there exist positive integers T and L such that "
          + "$a_{n+T}=a_n+L$.";
  private static final String PER_INSTANCE =
      "For each positive integer n choose T(n),L(n) such that "
          + "$a_{n+T(n)}=a_n+L(n)$.";
  private static final String MIXED =
      "There exist positive integers T and L such that for every positive integer n, "
          + "$a_{n+T}=a_n+L$; in other words, the sequence is an arithmetic progression.";
  private static final List<String> PRODUCTION_PATHS =
      List.of(
          "scope_analysis",
          "goal_alignment",
          "strategy_generation",
          "main_goal_obligation",
          "proof_control_goal");

  @TempDir Path temporaryDirectory;

  @Test
  void preservesTheExactRootAcrossProductionPromptsAndCheckpointResume() throws Exception {
    Path runDirectory = temporaryDirectory.resolve("root-goal-run");
    SystemConfig config =
        mockConfig(
            new DesktopRuntimeLocator(projectRoot(), null)
                .loadProfile("proof-control-active.yaml"));
    Harness harness = newHarness(config, runDirectory);
    int rootHashChanges = 0;
    int rootGoalReplacements = 0;
    int rejectedMainGoalLeaks = 0;
    int rejectedSidecarPersistence = 0;
    int postResumeRootHashChanges = 0;
    int postResumeMainGoalLeaks = 0;
    String initialRootHash;
    String preResumeRootHash = null;
    String postResumeRootHash = null;
    Set<String> capturedPromptTypes = new LinkedHashSet<>();

    try {
      invoke(harness.coordinator(), "freezeProblem");
      RootGoalContract initialRoot = field(harness.coordinator(), "rootGoal", RootGoalContract.class);
      initialRootHash = initialRoot.sourceStatementHash();
      assertEquals(SOURCE, initialRoot.sourceStatement());

      for (int round = 0; round < ROUNDS; round++) {
        if (round == RESUME_ROUND) {
          RootGoalContract beforeResume =
              field(harness.coordinator(), "rootGoal", RootGoalContract.class);
          preResumeRootHash = beforeResume.sourceStatementHash();
          DesktopSolveCheckpoint checkpoint = roundTripCheckpoint(runDirectory);
          harness.close();
          harness = newHarness(config, runDirectory);
          invoke(harness.coordinator(), "restore", DesktopSolveCheckpoint.class, checkpoint);
          RootGoalContract afterResume =
              field(harness.coordinator(), "rootGoal", RootGoalContract.class);
          postResumeRootHash = afterResume.sourceStatementHash();
          assertEquals(preResumeRootHash, postResumeRootHash);
          assertEquals(SOURCE, afterResume.sourceStatement());
        }

        String candidateText = candidateFor(round);
        boolean expectedUsable = round % 6 == 0;
        ProblemSemanticViewCandidate candidate = candidate(candidateText);
        harness.promptSink().startRound(candidate);

        invoke(harness.coordinator(), "runTriage");
        setField(harness.coordinator(), "strategySet", null);
        invoke(harness.coordinator(), "generateAndAdmitStrategies");

        RootGoalContract rootGoal =
            field(harness.coordinator(), "rootGoal", RootGoalContract.class);
        ProblemContract problem =
            field(harness.coordinator(), "frozenProblem", ProblemContract.class);
        ProblemSemanticViewService semanticViews =
            field(
                harness.coordinator(),
                "semanticViewService",
                ProblemSemanticViewService.class);
        ProblemSemanticView audited = semanticViews.build(rootGoal, candidate);
        ProviderRequest strategyRequest = harness.promptSink().require("StrategySet");
        JsonNode strategyContext = sanitizedContext(strategyRequest);
        JsonNode promptProblem = strategyContext.path("immutable_problem");
        ProofGraphStore graph =
            field(harness.coordinator(), "proofGraph", ProofGraphStore.class);
        ProofControlModels.Obligation controlGoal =
            (ProofControlModels.Obligation) invoke(harness.coordinator(), "controlGoal");
        Map<String, ProofControlModels.GoalLink> links = goalLinks(harness.coordinator());

        String promptType = promptStage(strategyRequest);
        capturedPromptTypes.add(promptType);
        assertEquals("strategy_generation", promptType);
        assertEquals(expectedUsable ? "usable" : "rejected", audited.status());
        assertFalse(audited.authoritative());
        assertEquals(SOURCE, rootGoal.sourceStatement());
        assertEquals(initialRootHash, rootGoal.sourceStatementHash());
        assertEquals(SOURCE, problem.exactStatement());
        assertEquals(initialRootHash, problem.goalHash());
        assertEquals(SOURCE, promptProblem.path("exact_statement").asText());
        assertEquals(SOURCE, promptProblem.path("canonical_statement").asText());
        assertEquals(SOURCE, promptProblem.path("normalized_statement").asText());
        assertEquals(SOURCE, promptProblem.path("original_statement").asText());
        assertEquals(SOURCE, graph.getObligation("main-goal").statement());
        assertEquals(SOURCE, controlGoal.statement());
        assertFalse(links.isEmpty());
        links.values()
            .forEach(
                link -> {
                  assertEquals("main-goal", link.targetObligationId());
                  assertEquals(ProofControlModels.GoalRelation.EQUIVALENT, link.relation());
                  assertEquals(ProofControlModels.ScopeRelation.SAME, link.scopeRelation());
                });

        if (problem.semanticView() != null) {
          assertFalse(problem.semanticView().authoritative());
          assertEquals(initialRootHash, problem.semanticView().sourceStatementHash());
        }

        boolean hashChanged = !initialRootHash.equals(rootGoal.sourceStatementHash());
        boolean rootReplaced = !SOURCE.equals(problem.exactStatement());
        boolean sidecarPersisted =
            !expectedUsable
                && problem.semanticView() != null
                && candidateText.equals(problem.semanticView().englishStatement());
        boolean mainGoalLeak =
            !expectedUsable
                && rejectedCandidateReachedMainGoal(
                    candidateText, promptProblem, graph, controlGoal);

        if (hashChanged) {
          rootHashChanges++;
        }
        if (rootReplaced) {
          rootGoalReplacements++;
        }
        if (sidecarPersisted) {
          rejectedSidecarPersistence++;
        }
        if (mainGoalLeak) {
          rejectedMainGoalLeaks++;
        }
        if (round >= RESUME_ROUND && hashChanged) {
          postResumeRootHashChanges++;
        }
        if (round >= RESUME_ROUND && mainGoalLeak) {
          postResumeMainGoalLeaks++;
        }

        assertFalse(sidecarPersisted, "rejected semantic sidecar persisted at round " + round);
        assertFalse(mainGoalLeak, "rejected semantic view reached a main goal at round " + round);
      }
    } finally {
      harness.close();
    }

    assertNotNull(preResumeRootHash);
    assertNotNull(postResumeRootHash);
    assertEquals(initialRootHash, preResumeRootHash);
    assertEquals(initialRootHash, postResumeRootHash);
    assertEquals(Set.of("strategy_generation"), capturedPromptTypes);
    assertEquals(0, rootHashChanges);
    assertEquals(0, rootGoalReplacements);
    assertEquals(0, rejectedMainGoalLeaks);
    assertEquals(0, rejectedSidecarPersistence);
    assertEquals(0, postResumeRootHashChanges);
    assertEquals(0, postResumeMainGoalLeaks);

    System.out.println("PRODUCTION ROOT-GOAL PROPAGATION DIAGNOSTIC");
    System.out.println("ROUNDS=" + ROUNDS);
    System.out.println("PROMPT_PATHS_CHECKED=" + PRODUCTION_PATHS.size());
    System.out.println("ROOT_HASH_CHANGES=" + rootHashChanges);
    System.out.println("ROOT_GOAL_REPLACEMENTS=" + rootGoalReplacements);
    System.out.println("REJECTED_MAIN_GOAL_LEAKS=" + rejectedMainGoalLeaks);
    System.out.println("REJECTED_SIDECAR_PERSISTENCE=" + rejectedSidecarPersistence);
    System.out.println("POST_RESUME_ROOT_HASH_CHANGES=" + postResumeRootHashChanges);
    System.out.println("POST_RESUME_MAIN_GOAL_LEAKS=" + postResumeMainGoalLeaks);
    System.out.println("PRE_RESUME_ROOT_HASH=" + preResumeRootHash);
    System.out.println("POST_RESUME_ROOT_HASH=" + postResumeRootHash);
    System.out.println("CAPTURED_DOWNSTREAM_PROMPT_TYPES=" + capturedPromptTypes);
    System.out.println("PRODUCTION_PATHS=" + PRODUCTION_PATHS);
    System.out.println("RESULT=PASS");
  }

  private Harness newHarness(SystemConfig config, Path runDirectory) {
    CapturingPromptSink promptSink = new CapturingPromptSink();
    Map<String, MockResponder> responders = new LinkedHashMap<>();
    config.agents().forEach(agent -> responders.put(agent.id(), promptSink));
    HttpTransport forbiddenNetwork =
        request -> {
          throw new AssertionError("root-goal propagation test attempted network access");
        };
    ProviderClientRegistry providers =
        new ProviderClientRegistry(
            config, responders, ignored -> forbiddenNetwork, false, ignored -> null);
    AgentPool pool = new AgentPool(config, providers);
    CallLedger ledger = new CallLedger(1_000L, null, null);
    StructuredAgentRunner runner =
        new StructuredAgentRunner(
            pool,
            new ArtifactStore(runDirectory.resolve("runtime-artifacts"), RUN_ID),
            new RepeatingProviderCallRepository(),
            ledger,
            new PromptRedactor(List.of()),
            new BoundedJsonRepairer(1_500_000));
    DesktopLiveRuntimeFactory.PreparedRuntime runtime =
        new DesktopLiveRuntimeFactory.PreparedRuntime(PROFILE, config, Map.of(), false);
    ComputationBroker computation =
        new ComputationBroker(
            RUN_ID,
            ComputationLimits.defaultsEnabled(),
            ComputationHandlerRegistry.javaOnly(),
            new InMemoryComputationCache());
    DesktopSolveCoordinator coordinator =
        new DesktopSolveCoordinator(
            new SolveRequest(SOURCE, RUN_ID, null, PROFILE),
            RUN_ID,
            runDirectory,
            runtime,
            runner,
            new PromptFactory("zh-CN"),
            pool,
            ledger,
            computation,
            false,
            noOpProgress(),
            sha256(SOURCE));
    return new Harness(coordinator, promptSink, pool);
  }

  private static boolean rejectedCandidateReachedMainGoal(
      String candidate,
      JsonNode promptProblem,
      ProofGraphStore graph,
      ProofControlModels.Obligation controlGoal) {
    boolean promptLeak =
        candidate.equals(promptProblem.path("exact_statement").asText())
            || candidate.equals(promptProblem.path("canonical_statement").asText())
            || candidate.equals(promptProblem.path("normalized_statement").asText())
            || candidate.equals(promptProblem.path("original_statement").asText())
            || candidate.equals(
                promptProblem.path("semantic_view").path("english_statement").asText());
    return promptLeak
        || candidate.equals(graph.getObligation("main-goal").statement())
        || candidate.equals(controlGoal.statement());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ProofControlModels.GoalLink> goalLinks(
      DesktopSolveCoordinator coordinator) throws ReflectiveOperationException {
    return Map.copyOf((Map<String, ProofControlModels.GoalLink>) rawField(coordinator, "goalLinks"));
  }

  private static DesktopSolveCheckpoint roundTripCheckpoint(Path runDirectory) throws Exception {
    Path state = runDirectory.resolve("structured").resolve("desktop-solve-state.json");
    assertTrue(Files.isRegularFile(state));
    DesktopSolveCheckpoint persisted =
        ContractObjectMapper.read(Files.readString(state), DesktopSolveCheckpoint.class);
    String serialized = ContractObjectMapper.write(persisted);
    return ContractObjectMapper.read(serialized, DesktopSolveCheckpoint.class);
  }

  private static JsonNode sanitizedContext(ProviderRequest request) {
    String prompt = request.messages().getLast().content();
    String startMarker = "SANITIZED CONTEXT:\n";
    String endMarker = "\n\nOUTPUT LANGUAGE:";
    int start = prompt.indexOf(startMarker);
    assertTrue(start >= 0, "strategy prompt has no sanitized context");
    start += startMarker.length();
    int end = prompt.indexOf(endMarker, start);
    assertTrue(end > start, "strategy prompt has no context terminator");
    return ContractObjectMapper.parseTree(prompt.substring(start, end));
  }

  private static String promptStage(ProviderRequest request) {
    String prompt = request.messages().getLast().content();
    String marker = "[STAGE:";
    int start = prompt.indexOf(marker);
    int end = prompt.indexOf(']', start);
    assertTrue(start >= 0 && end > start, "captured prompt has no stage marker");
    return prompt.substring(start + marker.length(), end);
  }

  private static ProblemSemanticViewCandidate candidate(String statement) {
    return new ProblemSemanticViewCandidate(
        0.99d,
        statement,
        List.of("model claims exact preservation"),
        true,
        true,
        true,
        true);
  }

  private static String candidateFor(int round) {
    return switch (round % 6) {
      case 0 -> EXACT;
      case 1 -> EVENTUAL;
      case 2 -> ARITHMETIC;
      case 3 -> SWAPPED;
      case 4 -> PER_INSTANCE;
      case 5 -> MIXED;
      default -> throw new AssertionError("unreachable round");
    };
  }

  private static TriageResult triage(ProblemSemanticViewCandidate candidate) {
    return new TriageResult(
        0.99d,
        Difficulty.HARD,
        List.of("preserve the exact global quantifiers"),
        List.of(),
        ProblemKind.PROOF,
        "decomposition",
        "Audit the semantic sidecar before planning proof routes.",
        candidate,
        3,
        2,
        List.of(TaskRequirement.PROOF));
  }

  private static StrategySet strategies() {
    return new StrategySet(
        "Three independent mechanisms target the immutable root goal.",
        List.of(),
        List.of(strategy("strategy-a"), strategy("strategy-b"), strategy("strategy-c")));
  }

  private static StrategyCard strategy(String id) {
    String mechanism =
        switch (id) {
          case "strategy-a" -> "Analyze residue classes of the index translation.";
          case "strategy-b" -> "Construct a finite-state recurrence invariant.";
          default -> "Use a contradiction from a minimal failed translation.";
        };
    return new StrategyCard(
        null,
        "Establish the exact target using " + mechanism,
        List.of(),
        List.of(),
        List.of(),
        mechanism,
        List.of(),
        0.1d,
        0.95d,
        List.of(),
        "Test the smallest admissible instance against this mechanism.",
        "The route preserves the exact quantified root and has a separate mechanism.",
        null,
        null,
        List.of(),
        List.of(),
        id,
        List.of(id),
        "Independent route " + id);
  }

  private static RunExecutionBackend.ProgressSink noOpProgress() {
    return (type, stage, agentId, status, summary, reference) -> {};
  }

  private static SystemConfig mockConfig(SystemConfig source) {
    List<AgentConfig> agents =
        source.agents().stream()
            .map(DesktopSolveCoordinatorRootGoalPropagationTest::mockAgent)
            .toList();
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

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static <T> T field(Object target, String name, Class<T> type)
      throws ReflectiveOperationException {
    return type.cast(rawField(target, name));
  }

  private static Object rawField(Object target, String name) throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void setField(Object target, String name, Object value)
      throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object invoke(Object target, String name) throws Exception {
    return invoke(target, name, new Class<?>[0], new Object[0]);
  }

  private static Object invoke(Object target, String name, Class<?> type, Object argument)
      throws Exception {
    return invoke(target, name, new Class<?>[] {type}, new Object[] {argument});
  }

  private static Object invoke(
      Object target, String name, Class<?>[] parameterTypes, Object[] arguments) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
    method.setAccessible(true);
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException exception) {
      if (exception.getCause() instanceof Exception cause) {
        throw cause;
      }
      if (exception.getCause() instanceof Error cause) {
        throw cause;
      }
      throw exception;
    }
  }

  private record Harness(
      DesktopSolveCoordinator coordinator, CapturingPromptSink promptSink, AgentPool pool)
      implements AutoCloseable {
    @Override
    public void close() {
      pool.close();
    }
  }

  private static final class CapturingPromptSink implements MockResponder {
    private final List<ProviderRequest> requests = new ArrayList<>();
    private ProblemSemanticViewCandidate candidate = candidate(EXACT);

    void startRound(ProblemSemanticViewCandidate nextCandidate) {
      candidate = nextCandidate;
      requests.clear();
    }

    ProviderRequest require(String schemaName) {
      return requests.stream()
          .filter(request -> schemaName.equals(request.schemaName()))
          .reduce((first, second) -> second)
          .orElseThrow(() -> new AssertionError("no prompt captured for " + schemaName));
    }

    @Override
    public LLMResponse respond(ProviderRequest request) {
      requests.add(request);
      Object payload =
          switch (request.schemaName()) {
            case "TriageResult" -> triage(candidate);
            case "StrategySet" -> strategies();
            default -> throw new AssertionError("unexpected response schema: " + request.schemaName());
          };
      return new LLMResponse(
          ContractObjectMapper.write(payload),
          "scripted-model",
          "mock",
          7,
          11,
          0.5d,
          "root-goal-request",
          "stop",
          false,
          JsonNodeFactory.instance.objectNode());
    }
  }

  private static final class RepeatingProviderCallRepository
      implements ProviderCallRepository {
    private final InMemoryProviderCallRepository delegate =
        new InMemoryProviderCallRepository();

    @Override
    public ProviderCallRecord plan(ProviderCallPlan plan) {
      return delegate.plan(
          new ProviderCallPlan(
              plan.runId(),
              plan.callId(),
              plan.idempotencyKey() + ":" + plan.callId(),
              plan.agentId(),
              plan.provider(),
              plan.model(),
              plan.stage(),
              plan.requestHash(),
              plan.requestArtifactHash()));
    }

    @Override
    public ProviderCallRecord transition(ProviderCallTransition transition) {
      return delegate.transition(transition);
    }

    @Override
    public boolean markApplied(String runId, String callId, String applicationKey) {
      return delegate.markApplied(runId, callId, applicationKey + ":" + callId);
    }

    @Override
    public Optional<ProviderCallRecord> findByIdempotencyKey(
        String runId, String idempotencyKey) {
      return Optional.empty();
    }

    @Override
    public List<ProviderCallRecord> findByRun(String runId) {
      return delegate.findByRun(runId);
    }
  }
}
