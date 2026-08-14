package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.agent.BoundedJsonRepairer;
import io.github.aililuola.mathproofmesh.agent.CallLedger;
import io.github.aililuola.mathproofmesh.agent.PromptFactory;
import io.github.aililuola.mathproofmesh.agent.PromptRedactor;
import io.github.aililuola.mathproofmesh.agent.StructuredAgentRunner;
import io.github.aililuola.mathproofmesh.api.SolveRequest;
import io.github.aililuola.mathproofmesh.computation.ComputationBroker;
import io.github.aililuola.mathproofmesh.computation.ComputationHandlerRegistry;
import io.github.aililuola.mathproofmesh.computation.ComputationLimits;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.InspirationContextMode;
import io.github.aililuola.mathproofmesh.contract.InspirationMechanism;
import io.github.aililuola.mathproofmesh.contract.InspirationProposal;
import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotProposal;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewBatch;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.memory.GreedyGcdNegativeKnowledgeSeeds;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.proofcontrol.MetaPivotController;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlFacade;
import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotRecord;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Drives the semantic-pivot proposal and review through real structured provider calls. */
final class DesktopSemanticPivotProviderTestHarness implements AutoCloseable {
  private final DesktopSolveCoordinator coordinator;
  private final AgentPool pool;
  private final InMemoryProviderCallRepository providerCalls;
  private final ScriptedPivotResponder responder;
  private final List<String> progressEvents;

  private DesktopSemanticPivotProviderTestHarness(
      DesktopSolveCoordinator coordinator,
      AgentPool pool,
      InMemoryProviderCallRepository providerCalls,
      ScriptedPivotResponder responder,
      List<String> progressEvents) {
    this.coordinator = coordinator;
    this.pool = pool;
    this.providerCalls = providerCalls;
    this.responder = responder;
    this.progressEvents = progressEvents;
  }

  static DesktopSemanticPivotProviderTestHarness open(Path directory, String runId)
      throws Exception {
    SystemConfig source =
        new DesktopRuntimeLocator(projectRoot(), null).loadProfile("proof-control-active.yaml");
    SystemConfig config = mockConfig(source);
    ScriptedPivotResponder responder = new ScriptedPivotResponder();
    List<String> progressEvents = new ArrayList<>();
    Map<String, MockResponder> responders = new LinkedHashMap<>();
    config.agents().forEach(agent -> responders.put(agent.id(), responder));
    ProviderClientRegistry providers =
        new ProviderClientRegistry(
            config,
            responders,
            ignored -> request -> {
              throw new AssertionError("semantic-pivot test attempted network access");
            },
            false,
            ignored -> null);
    AgentPool pool = new AgentPool(config, providers);
    InMemoryProviderCallRepository calls = new InMemoryProviderCallRepository();
    CallLedger ledger = new CallLedger(10_000L, null, null);
    StructuredAgentRunner runner =
        new StructuredAgentRunner(
            pool,
            new ArtifactStore(directory.resolve("runtime-artifacts"), runId),
            calls,
            ledger,
            new PromptRedactor(List.of()),
            new BoundedJsonRepairer(1_500_000));
    DesktopLiveRuntimeFactory.PreparedRuntime runtime =
        new DesktopLiveRuntimeFactory.PreparedRuntime(
            "semantic-pivot-provider-test", config, Map.of(), false);
    ComputationBroker computation =
        new ComputationBroker(
            runId,
            ComputationLimits.defaultsEnabled(),
            ComputationHandlerRegistry.javaOnly(),
            new InMemoryComputationCache());
    DesktopSolveCoordinator coordinator =
        new DesktopSolveCoordinator(
            new SolveRequest(
                DesktopNegativeKnowledgeTestHarness.SOURCE,
                runId,
                null,
                "semantic-pivot-provider-test"),
            runId,
            directory,
            runtime,
            runner,
            new PromptFactory("zh-CN"),
            pool,
            ledger,
            computation,
            false,
            (type, stage, agentId, status, summary, reference) ->
                progressEvents.add(type + ":" + status + ":" + summary),
            DesktopNegativeKnowledgeTestHarness.PROBLEM_HASH);
    DesktopSemanticPivotProviderTestHarness harness =
        new DesktopSemanticPivotProviderTestHarness(
            coordinator, pool, calls, responder, progressEvents);
    harness.prepareRoute();
    return harness;
  }

  SemanticPivotRecord runProductionCycle() throws Exception {
    String proposerId = pool.agents().getFirst().id();
    ProofControlFacade proofControl = field("proofControl", ProofControlFacade.class);
    MetaPivotController.Pivot intent =
        proofControl
            .metaPivot()
            .request(
                "route-1", 0, List.of(InspirationMechanism.REPRESENTATION_SWITCH.value()));
    InspirationProposal inspiration =
        new InspirationProposal(
            null,
            null,
            null,
            InspirationContextMode.LOCAL,
            1,
            EvidenceType.UNVERIFIED_IDEA,
            0.9d,
            List.of("Replace the local prefix object by a global support family."),
            null,
            InspirationMechanism.REPRESENTATION_SWITCH,
            null,
            0.95d,
            new NoveltySignature(),
            "semantic-pivot-provider-source",
            0,
            "A trusted obstruction motivates a typed representation change.",
            null,
            null,
            proposerId,
            "Switch from local prefix stability to a global support object.",
            List.of("route-1"),
            "semantic-pivot-provider-task",
            "semantic-pivot-provider-trigger");
    Method method =
        DesktopSolveCoordinator.class.getDeclaredMethod(
            "runSemanticPivotCycle", MetaPivotController.Pivot.class, List.class);
    method.setAccessible(true);
    return (SemanticPivotRecord) invoke(method, intent, List.of(inspiration));
  }

  List<String> responseSchemas() {
    return responder.schemas();
  }

  List<String> providerStages(String runId) {
    return providerCalls.findByRun(runId).stream().map(call -> call.stage()).toList();
  }

  List<String> progressEvents() {
    return List.copyOf(progressEvents);
  }

  long appliedMetaPivotCount() {
    return field("proofControl", ProofControlFacade.class).metaPivot().snapshot().pivots().values()
        .stream()
        .filter(pivot -> "APPLIED".equals(pivot.status().name()))
        .count();
  }

  private void prepareRoute() throws Exception {
    invoke("freezeProblem");
    setField(
        "strategySet",
        new StrategySet(
            "Keep one valid route while the typed pivot is reviewed.",
            List.of(),
            List.of(validStrategy(), invalidStrategy())));
    invoke("generateAndAdmitStrategies");
    invoke("ensureInitialRoutes");
    Object route = ((List<?>) rawField("routes")).getFirst();
    Method seed =
        DesktopSolveCoordinator.class.getDeclaredMethod("ensureSeedCheckpoint", route.getClass());
    seed.setAccessible(true);
    invoke(seed, route);
  }

  @Override
  public void close() {
    pool.close();
  }

  private Object rawField(String name) {
    try {
      Field field = DesktopSolveCoordinator.class.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(coordinator);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private <T> T field(String name, Class<T> type) {
    return type.cast(rawField(name));
  }

  private void setField(String name, Object value) throws ReflectiveOperationException {
    Field field = DesktopSolveCoordinator.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(coordinator, value);
  }

  private Object invoke(String name) throws Exception {
    Method method = DesktopSolveCoordinator.class.getDeclaredMethod(name);
    method.setAccessible(true);
    return invoke(method);
  }

  private Object invoke(Method method, Object... arguments) throws Exception {
    try {
      return method.invoke(coordinator, arguments);
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

  private static StrategyCard validStrategy() {
    return new StrategyCard(
        null,
        "Prove bounded gaps from multiples of Q=rad(a_1).",
        List.of(),
        List.of(),
        List.of(),
        "Use admissible multiples of Q and a separately proved finite-state bridge.",
        List.of(),
        0.2d,
        0.9d,
        List.of("Establish bounded gaps directly from the recurrence."),
        "Test the bridge on the first exact recurrence states.",
        "The recurrence and the bridge are explicit independent obligations.",
        null,
        null,
        List.of(),
        List.of(),
        "valid-production-strategy",
        List.of("bounded_gaps", "finite_state_bridge"),
        "Valid bounded-gap route");
  }

  private static StrategyCard invalidStrategy() {
    String statement = GreedyGcdNegativeKnowledgeSeeds.finitePrimeSupport().trustedAliases().getFirst();
    return new StrategyCard(
        null,
        "Derive the target after establishing a load-bearing shortcut.",
        List.of(),
        List.of(),
        List.of(),
        statement,
        List.of(),
        0.2d,
        0.95d,
        List.of("Use the proposed shortcut to derive the translation law."),
        "Search for a counterexample to the shortcut.",
        "The proposal claims a different route mechanism.",
        null,
        null,
        List.of(),
        List.of(),
        "setup-invalid-strategy",
        List.of("candidate_shortcut"),
        "Rejected setup shortcut");
  }

  private static SystemConfig mockConfig(SystemConfig source) {
    List<AgentConfig> agents = source.agents().stream().map(DesktopSemanticPivotProviderTestHarness::mockAgent).toList();
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

  private static final class ScriptedPivotResponder implements MockResponder {
    private final List<String> schemas = new ArrayList<>();
    private JsonNode proposalContext;
    private JsonNode reviewContext;

    @Override
    public synchronized LLMResponse respond(ProviderRequest request) {
      schemas.add(request.schemaName());
      String response =
          switch (request.schemaName()) {
            case "SemanticPivotProposal" -> {
              if (proposalContext == null) {
                proposalContext = promptContext(request);
              }
              yield proposal(proposalContext);
            }
            case "SemanticPivotReviewBatch" -> {
              if (reviewContext == null) {
                reviewContext = promptContext(request);
              }
              yield review(reviewContext);
            }
            default -> throw new AssertionError(
                "unexpected semantic-pivot schema: " + request.schemaName());
          };
      return new LLMResponse(
          response,
          "semantic-pivot-scripted-model",
          "mock",
          20,
          20,
          1.0d,
          "semantic-pivot-provider-" + schemas.size(),
          "stop",
          false,
          JsonNodeFactory.instance.objectNode());
    }

    synchronized List<String> schemas() {
      return List.copyOf(schemas);
    }

    private static String proposal(JsonNode context) {
      JsonNode root = context.path("immutable_root_goal");
      JsonNode signature = context.path("source_structural_signature");
      JsonNode obstruction = context.path("trusted_obstruction_refs").get(0);
      String routeId = context.path("route_id").asText();
      JsonNode sourceStrategy = context.path("source_strategy");
      String sourceId = text(sourceStrategy, "strategyId", "strategy_id");
      String obstructionId = obstruction.path("obstructionId").asText();
      String oldObject = signature.path("activeObjectIds").get(0).asText();
      String oldDirection = signature.path("directionSignature").asText();
      ObjectNode strategy = JsonNodeFactory.instance.objectNode();
      strategy.put("strategy_id", "provider-semantic-pivot-strategy");
      strategy.put("title", "Global support representation pivot");
      strategy.put("bottleneck", "Reduce a large prime in the global support family.");
      strategy.put(
          "core_idea", "Study inclusion-minimal hitting sets for the global support family.");
      strategy.put(
          "falsification_test", "Search for a global support family violating the reduction.");
      strategy.put(
          "independence_basis", "The typed object and target changes are independently reviewable.");
      strategy.put("estimated_cost", 0.3d);
      strategy.put("estimated_success", 0.8d);
      strategy.set("calculation_checks", JsonNodeFactory.instance.arrayNode());
      strategy.set("calculation_evidence_refs", JsonNodeFactory.instance.arrayNode());
      strategy.set("computation_hints", JsonNodeFactory.instance.arrayNode());
      strategy.set("critical_claims", JsonNodeFactory.instance.arrayNode());
      strategy.set("parent_strategy_ids", strings(sourceId));
      strategy.set(
          "expected_lemmas", strings("Prove the global large-prime support reduction."));
      strategy.set("prerequisites", JsonNodeFactory.instance.arrayNode());
      strategy.set("tags", strings("semantic_pivot", "global_support"));

      ObjectNode result = JsonNodeFactory.instance.objectNode();
      result.put("proposal_id", "provider-semantic-pivot-proposal");
      result.put("proposer_agent_id", "model-claimed-proposer");
      result.put("problem_hash", root.path("problem_hash").asText());
      result.put("root_goal_hash", root.path("source_statement_hash").asText());
      result.put("route_id", routeId);
      result.put("source_strategy_id", sourceId);
      result.set(
          "transformation_types",
          strings("OBJECT_REPLACEMENT", "TARGET_REFORMULATION", "REPRESENTATION_CHANGE"));
      result.set("obstruction_ids", strings(obstructionId));
      ObjectNode objectChange = JsonNodeFactory.instance.objectNode();
      objectChange.put("old_object_id", oldObject);
      objectChange.put("old_description", "current local recurrence object");
      objectChange.put("disposition", "REPLACE");
      objectChange.put("new_object_id", "provider-global-support-object");
      objectChange.put("new_description", "global inclusion-minimal support family");
      objectChange.put(
          "bridge_statement", "The global family retains the support property of the local object.");
      objectChange.set("evidence_refs", strings(obstructionId));
      result.set("object_changes", array(objectChange));
      ObjectNode directionChange = JsonNodeFactory.instance.objectNode();
      directionChange.put("old_direction_signature", oldDirection);
      directionChange.put("new_direction_signature", "provider-global-large-prime-reduction");
      directionChange.put(
          "mathematical_reason", "The obstruction requires a global support reduction.");
      directionChange.set("evidence_refs", strings(obstructionId));
      result.set("direction_changes", array(directionChange));
      result.set("assumption_changes", JsonNodeFactory.instance.arrayNode());
      result.set("claim_use_changes", JsonNodeFactory.instance.arrayNode());
      ObjectNode obligation = JsonNodeFactory.instance.objectNode();
      obligation.put("obligation_id", "provider-semantic-pivot-obligation");
      obligation.put("action", "ADD_NEW_OBLIGATION");
      obligation.put(
          "proposed_statement", "Prove the global large-prime support reduction.");
      obligation.put("proposed_kind", "subgoal");
      obligation.set("assumptions", strings("the prime exceeds the initial term"));
      obligation.set("dependency_ids", JsonNodeFactory.instance.arrayNode());
      obligation.put("reason", "This target is load-bearing for the new global object.");
      result.set("obligation_changes", array(obligation));
      result.set("proposed_strategy", strategy);
      result.put(
          "rationale", "Apply explicit object, target, direction, and obligation changes.");
      String json = ContractObjectMapper.write(result);
      ContractObjectMapper.read(json, SemanticPivotProposal.class);
      return json;
    }

    private static String review(JsonNode context) {
      String pivotId = context.path("compiled_pivot_delta").path("pivotId").asText();
      ObjectNode decision = JsonNodeFactory.instance.objectNode();
      decision.put("pivot_id", pivotId);
      decision.put("verdict", "pass");
      decision.put("confidence", 0.99d);
      decision.put("obstruction_binding_valid", true);
      decision.put("root_goal_preserved", true);
      decision.put("object_change_coherent", true);
      decision.put("target_change_coherent", true);
      decision.put("retained_claims_compatible", true);
      decision.put("new_obligations_load_bearing", true);
      decision.put("no_authority_escalation", true);
      decision.set("issues", JsonNodeFactory.instance.arrayNode());
      decision.put(
          "concise_feedback", "The bounded typed delta is coherent and respects authority.");
      ObjectNode result = JsonNodeFactory.instance.objectNode();
      result.put("report_id", "provider-semantic-pivot-review");
      result.put("reviewer_agent_id", "model-claimed-reviewer");
      result.put("proposer_agent_id", context.path("proposer_agent_id").asText());
      result.set("decisions", array(decision));
      ObjectNode usage = JsonNodeFactory.instance.objectNode();
      usage.put("estimated_cost_usd", 0.0d);
      usage.put("input_tokens", 0);
      usage.put("latency_ms", 0.0d);
      usage.put("output_tokens", 0);
      usage.put("total_tokens", 0);
      result.set("usage", usage);
      String json = ContractObjectMapper.write(result);
      ContractObjectMapper.read(json, SemanticPivotReviewBatch.class);
      return json;
    }

    private static JsonNode promptContext(ProviderRequest request) {
      String startMarker = "SANITIZED CONTEXT:\n";
      String prompt =
          String.join(
              "\n", request.messages().stream().map(message -> message.content()).toList());
      int start = prompt.indexOf(startMarker);
      if (start < 0) {
        throw new AssertionError("semantic-pivot prompt context markers are missing");
      }
      start = prompt.indexOf('{', start + startMarker.length());
      boolean quoted = false;
      boolean escaped = false;
      int depth = 0;
      for (int index = start; index < prompt.length(); index++) {
        char current = prompt.charAt(index);
        if (quoted) {
          if (escaped) {
            escaped = false;
          } else if (current == '\\') {
            escaped = true;
          } else if (current == '"') {
            quoted = false;
          }
          continue;
        }
        if (current == '"') {
          quoted = true;
        } else if (current == '{') {
          depth++;
        } else if (current == '}' && --depth == 0) {
          return ContractObjectMapper.parseTree(prompt.substring(start, index + 1));
        }
      }
      throw new AssertionError(
          "semantic-pivot prompt contains incomplete context JSON; length="
              + prompt.length()
              + "; tail="
              + prompt.substring(Math.max(start, prompt.length() - 800))
                  .replace("\n", "\\n"));
    }

    private static ArrayNode strings(String... values) {
      ArrayNode array = JsonNodeFactory.instance.arrayNode();
      for (String value : values) {
        array.add(value);
      }
      return array;
    }

    private static ArrayNode array(JsonNode value) {
      return JsonNodeFactory.instance.arrayNode().add(value);
    }

    private static String text(JsonNode source, String primary, String fallback) {
      String value = source.path(primary).asText();
      return value.isBlank() ? source.path(fallback).asText() : value;
    }
  }
}
