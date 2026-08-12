package io.github.aililuola.mathproofmesh.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceBinding;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceStore;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.StructuredPayloadNormalizer;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.provider.AgentCallFailure;
import io.github.aililuola.mathproofmesh.provider.AgentFailoverExhausted;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.AgentRuntime;
import io.github.aililuola.mathproofmesh.provider.ChatMessage;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.ProviderCallPlan;
import io.github.aililuola.mathproofmesh.provider.ProviderCallRecord;
import io.github.aililuola.mathproofmesh.provider.ProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.ProviderCallState;
import io.github.aililuola.mathproofmesh.provider.ProviderCallTransition;
import io.github.aililuola.mathproofmesh.provider.ProviderCircuitOpenError;
import io.github.aililuola.mathproofmesh.provider.ProviderErrorKind;
import io.github.aililuola.mathproofmesh.provider.ProviderException;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Audited structured-call pipeline. The external call and downstream result
 * application deliberately have separate idempotency records.
 */
public final class StructuredAgentRunner {
  private final AgentPool pool;
  private final ArtifactStore artifacts;
  private final ProviderCallRepository calls;
  private final CallLedger budget;
  private final PromptRedactor redactor;
  private final BoundedJsonRepairer repairer;
  private final ReasoningTraceStore reasoningTraces;
  private final int parseRetries;
  private final int jsonRepairMaxOutputTokens;

  public StructuredAgentRunner(
      AgentPool pool,
      ArtifactStore artifacts,
      ProviderCallRepository calls,
      CallLedger budget,
      PromptRedactor redactor,
      BoundedJsonRepairer repairer) {
    this(pool, artifacts, calls, budget, redactor, repairer, null, 1, 8_192);
  }

  public StructuredAgentRunner(
      AgentPool pool,
      ArtifactStore artifacts,
      ProviderCallRepository calls,
      CallLedger budget,
      PromptRedactor redactor,
      BoundedJsonRepairer repairer,
      ReasoningTraceStore reasoningTraces) {
    this(pool, artifacts, calls, budget, redactor, repairer, reasoningTraces, 1, 8_192);
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The run-scoped reasoning archive is an intentionally shared, synchronized service; "
              + "the runner never exposes the reference to callers.")
  public StructuredAgentRunner(
      AgentPool pool,
      ArtifactStore artifacts,
      ProviderCallRepository calls,
      CallLedger budget,
      PromptRedactor redactor,
      BoundedJsonRepairer repairer,
      ReasoningTraceStore reasoningTraces,
      int parseRetries,
      int jsonRepairMaxOutputTokens) {
    this.pool = Objects.requireNonNull(pool, "pool");
    this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    this.calls = Objects.requireNonNull(calls, "calls");
    this.budget = Objects.requireNonNull(budget, "budget");
    this.redactor = Objects.requireNonNull(redactor, "redactor");
    this.repairer = Objects.requireNonNull(repairer, "repairer");
    this.reasoningTraces = reasoningTraces;
    if (parseRetries < 0 || parseRetries > 5) {
      throw new IllegalArgumentException("parseRetries must be between 0 and 5");
    }
    if (jsonRepairMaxOutputTokens < 256) {
      throw new IllegalArgumentException("jsonRepairMaxOutputTokens must be at least 256");
    }
    this.parseRetries = parseRetries;
    this.jsonRepairMaxOutputTokens = jsonRepairMaxOutputTokens;
  }

  public <T> StructuredCallResult<T> call(
      String runId,
      String idempotencyKey,
      String role,
      PromptBundle<T> bundle,
      AgentRuntime fixedAgent,
      String budgetBucket) {
    return call(
        runId,
        idempotencyKey,
        role,
        bundle,
        fixedAgent,
        budgetBucket,
        null,
        null);
  }

  public <T> StructuredCallResult<T> call(
      String runId,
      String idempotencyKey,
      String role,
      PromptBundle<T> bundle,
      AgentRuntime fixedAgent,
      String budgetBucket,
      Boolean thinkingEnabled,
      String reasoningEffort) {
    AgentRuntime agent =
        fixedAgent == null
            ? pool.select(role, Set.of(), List.of(), null, false)
            : fixedAgent;
    return callSingle(
        runId,
        idempotencyKey,
        bundle,
        agent,
        budgetBucket,
        List.of(agent.id()),
        thinkingEnabled,
        reasoningEffort,
        parseRetries);
  }

  public <T> StructuredCallResult<T> callWithFailover(
      String runId,
      String idempotencyKey,
      String role,
      PromptBundle<T> bundle,
      AgentRuntime primary,
      String budgetBucket,
      Set<String> excludedAgents,
      List<String> specialtyHints,
      int maximumBackups) {
    Objects.requireNonNull(primary, "primary");
    Set<String> exclusions =
        excludedAgents == null ? Set.of() : Set.copyOf(excludedAgents);
    if (exclusions.contains(primary.id())) {
      throw new IllegalArgumentException("primary agent is excluded");
    }
    List<AgentRuntime> candidates = new ArrayList<>();
    candidates.add(primary);
    java.util.LinkedHashSet<String> backupExclusions =
        new java.util.LinkedHashSet<>(exclusions);
    backupExclusions.add(primary.id());
    candidates.addAll(
        pool.failoverCandidates(
            role,
            backupExclusions,
            specialtyHints,
            primary.provider(),
            maximumBackups));
    List<String> attempted = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (AgentRuntime candidate : candidates) {
      attempted.add(candidate.id());
      try {
        return callSingle(
            runId,
            idempotencyKey + ":" + candidate.id(),
            bundle,
            candidate,
            budgetBucket,
            List.copyOf(attempted),
            null,
            null,
            parseRetries);
      } catch (ProviderCircuitOpenError error) {
        throw error;
      } catch (AgentCallFailure error) {
        errors.add(
            candidate.id()
                + ":"
                + error.providerFailure().kind());
        Integer status = error.providerFailure().statusCode();
        if (!error.retryable()
            && status != null
            && status != 401
            && status != 403) {
          break;
        }
      } catch (StructuredOutputError error) {
        errors.add(candidate.id() + ":STRUCTURED_OUTPUT");
      }
    }
    throw new AgentFailoverExhausted(role, attempted, errors);
  }

  public boolean apply(StructuredCallResult<?> result, String applicationKey) {
    Objects.requireNonNull(result, "result");
    return calls.markApplied(
        result.runId(), result.callId(), applicationKey);
  }

  public boolean apply(
      String runId, StructuredCallResult<?> result, String applicationKey) {
    Objects.requireNonNull(result, "result");
    return calls.markApplied(runId, result.callId(), applicationKey);
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "After persisting the correct terminal ledger state, this private boundary "
              + "must preserve the original typed provider or parsing failure.")
  private <T> StructuredCallResult<T> callSingle(
      String runId,
      String idempotencyKey,
      PromptBundle<T> bundle,
      AgentRuntime agent,
      String budgetBucket,
      List<String> attemptedAgents,
      Boolean thinkingEnabled,
      String reasoningEffort,
      int remainingParseRetries) {
    String safeSystem = redactor.redact(bundle.system());
    String safeUser = redactor.redact(bundle.user());
    String promptRef =
        artifacts.savePrompt(bundle.stage(), agent.id(), safeSystem, safeUser);
    String requestArtifactHash = artifactHash(promptRef);
    String requestHash =
        sha256(
            ContractObjectMapper.write(
                Map.of(
                    "agent_id", agent.id(),
                    "provider", agent.provider(),
                    "model", agent.model(),
                    "stage", bundle.stage(),
                    "system", safeSystem,
                    "user", safeUser,
                    "max_output_tokens", bundle.maxOutputTokens(),
                    "streaming", bundle.streaming())));
    long estimatedInputTokens =
        Math.max(1L, (safeSystem.length() + safeUser.length() + 3L) / 4L);
    BigDecimal expectedCost =
        CallLedger.tokenCost(
            estimatedInputTokens,
            bundle.maxOutputTokens(),
            agent.config().pricing().inputPerMillion(),
            agent.config().pricing().outputPerMillion());
    CallLedger.Reservation reservation =
        budget.reserve(
            bundle.stage(),
            budgetBucket,
            Math.addExact(estimatedInputTokens, bundle.maxOutputTokens()),
            expectedCost);
    String generatedCallId = UUID.randomUUID().toString();
    ProviderCallRecord planned =
        calls.plan(
            new ProviderCallPlan(
                runId,
                generatedCallId,
                idempotencyKey,
                agent.id(),
                agent.provider(),
                agent.model(),
                bundle.stage(),
                requestHash,
                requestArtifactHash));
    if (!planned.callId().equals(generatedCallId)) {
      budget.release(reservation.id());
      return replayExisting(
          planned,
          idempotencyKey,
          bundle,
          agent,
          budgetBucket,
          promptRef,
          attemptedAgents,
          remainingParseRetries);
    }

    ProviderCallState activeState = ProviderCallState.DISPATCHED;
    calls.transition(
        ProviderCallTransition.state(
            runId,
            generatedCallId,
            ProviderCallState.PLANNED,
            ProviderCallState.DISPATCHED));
    if (bundle.streaming()) {
      calls.transition(
          ProviderCallTransition.state(
              runId,
              generatedCallId,
              ProviderCallState.DISPATCHED,
              ProviderCallState.STREAMING));
      activeState = ProviderCallState.STREAMING;
    }
    try {
      ProviderRequest request =
          new ProviderRequest(
              List.of(
                  new ChatMessage("system", safeSystem),
                  new ChatMessage("user", safeUser)),
              bundle.temperature(),
              bundle.maxOutputTokens(),
              true,
              bundle.responseType().getSimpleName(),
              bundle.responseSchema(),
              thinkingEnabled,
              reasoningEffort,
              bundle.streaming(),
              agent.id(),
              null);
      LLMResponse response;
      if (reasoningTraces == null) {
        response = agent.call(request);
      } else {
        ReasoningTraceBinding binding =
            new ReasoningTraceBinding(
                reasoningTraces,
                ReasoningTraceBinding.agentTaskId(bundle.stage(), agent.id()),
                agent.id(),
                bundle.stage());
        ReasoningTraceBinding.Scope scope = binding.bind();
        try {
          response = agent.call(request);
        } finally {
          scope.close();
        }
      }
      String safeResponseText = redactor.redact(response.text());
      String responseRef =
          artifacts.writeText(
              ContractObjectMapper.write(
                  Map.of(
                      "agent_id", agent.id(),
                      "call_id", generatedCallId,
                      "provider", response.provider(),
                      "model", response.model(),
                      "request_id",
                          response.requestId() == null ? "" : response.requestId(),
                      "stage", bundle.stage(),
                      "text", safeResponseText,
                      "usage",
                          Map.of(
                              "input_tokens", response.inputTokens(),
                              "output_tokens", response.outputTokens(),
                              "latency_ms", response.latencyMs()),
                      "metadata", response.metadata())),
              "application/json",
              "provider-response:" + bundle.stage() + ":" + agent.id(),
              "short-term",
              "provider_response");
      BigDecimal cost =
          CallLedger.tokenCost(
              response.inputTokens(),
              response.outputTokens(),
              agent.config().pricing().inputPerMillion(),
              agent.config().pricing().outputPerMillion());
      calls.transition(
          new ProviderCallTransition(
              runId,
              generatedCallId,
              activeState,
              ProviderCallState.SUCCEEDED,
              response.inputTokens(),
              response.outputTokens(),
              cost,
              response.latencyMs(),
              artifactHash(responseRef),
              response.requestId(),
              agent.lastCallRetries(),
              BigDecimal.ZERO,
              null));
      budget.commit(reservation.id(), response, agent.config().pricing());
      return parseOrRepair(
          runId,
          generatedCallId,
          idempotencyKey,
          bundle,
          agent,
          budgetBucket,
          promptRef,
          responseRef,
          response,
          safeResponseText,
          cost,
          attemptedAgents,
          remainingParseRetries);
    } catch (AgentCallFailure failure) {
      completeFailure(
          runId,
          generatedCallId,
          activeState,
          reservation,
          expectedCost,
          failure.providerFailure(),
          failure.retries());
      throw failure;
    } catch (ProviderException failure) {
      completeFailure(
          runId,
          generatedCallId,
          activeState,
          reservation,
          expectedCost,
          failure,
          agent.lastCallRetries());
      throw failure;
    } catch (ProviderCircuitOpenError failure) {
      calls.transition(
          new ProviderCallTransition(
              runId,
              generatedCallId,
              activeState,
              ProviderCallState.FAILED,
              0L,
              0L,
              BigDecimal.ZERO,
              0.0d,
              null,
              null,
              0,
              BigDecimal.ZERO,
              null));
      budget.release(reservation.id());
      throw failure;
    } catch (RuntimeException failure) {
      // A provider success may still fail strict structured parsing. The
      // succeeded provider_call remains immutable and billed.
      if (!(failure instanceof StructuredOutputError)
          && !(failure instanceof AgentProgressError)) {
        budget.release(reservation.id());
      }
      throw failure;
    }
  }

  private void completeFailure(
      String runId,
      String callId,
      ProviderCallState activeState,
      CallLedger.Reservation reservation,
      BigDecimal expectedCost,
      ProviderException failure,
      int retries) {
    boolean ambiguous = failure.remoteResultUnknown();
    ProviderCallState terminal;
    if (failure.kind() == ProviderErrorKind.CANCELLED) {
      terminal = ProviderCallState.CANCELLED;
    } else {
      terminal =
          ambiguous ? ProviderCallState.AMBIGUOUS : ProviderCallState.FAILED;
    }
    ObjectNode ambiguity = JsonNodeFactory.instance.objectNode();
    ambiguity.put("remote_result_unknown", ambiguous);
    ambiguity.put("error_kind", failure.kind().name());
    ambiguity.put("potential_duplicate_charge", ambiguous);
    calls.transition(
        new ProviderCallTransition(
            runId,
            callId,
            activeState,
            terminal,
            0L,
            0L,
            BigDecimal.ZERO,
            0.0d,
            null,
            null,
            retries,
            ambiguous ? expectedCost : BigDecimal.ZERO,
            ambiguity));
    if (ambiguous) {
      budget.commitAmbiguous(reservation.id(), expectedCost);
    } else {
      budget.release(reservation.id());
    }
  }

  private <T> StructuredCallResult<T> replayExisting(
      ProviderCallRecord existing,
      String idempotencyKey,
      PromptBundle<T> bundle,
      AgentRuntime agent,
      String budgetBucket,
      String promptRef,
      List<String> attemptedAgents,
      int remainingParseRetries) {
    if (existing.state() != ProviderCallState.SUCCEEDED
        || existing.responseArtifactHash() == null) {
      throw new IllegalStateException(
          "idempotent provider call is not safely replayable: " + existing.state());
    }
    String responseRef =
        "artifact://sha256/" + existing.responseArtifactHash();
    JsonNode stored =
        ContractObjectMapper.parseTree(
            new String(artifacts.read(responseRef), StandardCharsets.UTF_8));
    String text = stored.path("text").asText();
    LLMResponse response =
        new LLMResponse(
            text,
            existing.model(),
            existing.provider(),
            existing.inputTokens(),
            existing.outputTokens(),
            existing.latencyMs(),
            existing.requestId(),
            "replayed",
            false,
            stored.path("metadata"));
    return parseOrRepair(
        existing.runId(),
        existing.callId(),
        idempotencyKey,
        bundle,
        agent,
        budgetBucket,
        promptRef,
        responseRef,
        response,
        text,
        existing.costUsd(),
        attemptedAgents,
        remainingParseRetries);
  }

  private <T> StructuredCallResult<T> parseOrRepair(
      String runId,
      String callId,
      String idempotencyKey,
      PromptBundle<T> bundle,
      AgentRuntime agent,
      String budgetBucket,
      String promptRef,
      String responseRef,
      LLMResponse response,
      String safeResponseText,
      BigDecimal cost,
      List<String> attemptedAgents,
      int remainingParseRetries) {
    if (reasoningBudgetExhausted(response, bundle.maxOutputTokens())) {
      throw reasoningBudgetExhaustedError(
          agent.id(), response, bundle.maxOutputTokens(), cost);
    }
    UsageRecord currentUsage = usage(response, cost);
    try {
      Parsed<T> parsed = parse(safeResponseText, bundle.responseType());
      return new StructuredCallResult<>(
          parsed.value(),
          runId,
          callId,
          agent.id(),
          response.provider(),
          response.model(),
          promptRef,
          responseRef,
          currentUsage,
          parsed.repaired(),
          attemptedAgents);
    } catch (StructuredOutputError failure) {
      if (remainingParseRetries <= 0) {
        throw failure;
      }
      int repairAttempt = parseRetries - remainingParseRetries + 1;
      StructuredCallResult<T> repaired =
          callSingle(
              runId,
              idempotencyKey + ":json-repair:" + repairAttempt,
              repairBundle(bundle, safeResponseText, failure),
              agent,
              budgetBucket,
              attemptedAgents,
              false,
              null,
              remainingParseRetries - 1);
      return new StructuredCallResult<>(
          repaired.value(),
          repaired.runId(),
          repaired.callId(),
          repaired.agentId(),
          repaired.provider(),
          repaired.model(),
          repaired.promptArtifactRef(),
          repaired.responseArtifactRef(),
          sumUsage(currentUsage, repaired.usage()),
          true,
          repaired.attemptedAgents());
    }
  }

  private <T> PromptBundle<T> repairBundle(
      PromptBundle<T> original,
      String malformedOutput,
      StructuredOutputError failure) {
    String schema =
        original.responseSchema() == null
            ? "{}"
            : ContractObjectMapper.write(original.responseSchema());
    String system =
        ("You repair malformed structured output. Return only one JSON object matching the schema. "
                + "Do not change mathematical content except where needed to satisfy field types, "
                + "numeric bounds, allowed values, and required fields. Never invent a missing final "
                + "answer. If the output is truncated, preserve only the honest complete prefix rather "
                + "than fabricating missing mathematical content.")
            .strip();
    String user =
        ("[STAGE:"
                + original.stage()
                + "_json_repair]\nJSON SCHEMA:\n"
                + schema
                + "\n\nCONTRACT-SPECIFIC RULES:\n"
                + repairContractRules(original.responseType())
                + "\n\nMALFORMED OUTPUT:\n"
                + malformedOutput
                + "\n\nVALIDATION ERROR:\n"
                + rootMessage(failure)
                + "\n\nORIGINAL TASK CONTEXT (immutable excerpt for reference only; "
                + "do not answer it, only preserve its mathematical content):\n"
                + prefix(original.user(), 1_200))
            .strip();
    return new PromptBundle<>(
        original.stage() + "_json_repair",
        system,
        user,
        original.responseType(),
        0.0d,
        Math.min(original.maxOutputTokens(), jsonRepairMaxOutputTokens),
        original.streaming(),
        original.responseSchema());
  }

  private static String repairContractRules(Class<?> responseType) {
    String common =
        "Use only schema enum literals and properties. Leave server-owned cryptographic hash "
            + "fields as empty strings. Probabilities, confidences, estimated_success, and "
            + "estimated_cost are normalized numbers from 0.0 through 1.0. A "
            + "ClaimCard.proof_steps entry is a complete ProofStep object, not a string ID. "
            + "Every CandidateConjecture must have status=candidate plus non-empty "
            + "supporting_experiment_ids, scope_limitations, and proof_obligations; omit an "
            + "unsupported optional conjecture rather than inventing experimental evidence.";
    return switch (responseType.getSimpleName()) {
      case "StrategySet" ->
          common
              + " calculation_checks accepts only typed ToolRequest kinds from the schema. "
              + "sandboxed_python is a ComputationHint or later ExperimentSpec method, never a "
              + "ToolRequest.kind; omit an invalid optional calculation check rather than changing "
              + "its mathematical purpose.";
      case "InitialExplorationTurn" ->
          common
              + " The action is a strict tagged union. request_computation requires exactly one "
              + "experiment_spec and requires attempt and experiment_impact to be null or omitted. "
              + "submit_attempt requires exactly one attempt and requires experiment_spec to be "
              + "null or omitted. abandon requires both attempt and experiment_spec to be null or "
              + "omitted. A discover_pattern experiment must set broad_search=true.";
      case "ContinuationTurn" ->
          common
              + " The action is a strict tagged union. request_computation requires exactly one "
              + "experiment_spec and requires delta and experiment_impact to be null or omitted. "
              + "submit_delta or complete requires exactly one delta and requires experiment_spec "
              + "to be null or omitted. abandon requires both delta and experiment_spec to be null "
              + "or omitted. A discover_pattern experiment must set broad_search=true.";
      case "ProofDelta" ->
          common
              + " proof_complete=true requires a non-empty candidate_final_answer and no remaining "
              + "subgoals; otherwise set proof_complete=false and retain the honest partial proof.";
      case "ToolAuditReport" ->
          common
              + " verdict must be exactly pass, fail, or inconclusive. Use pass only when every "
              + "proof-relevant result was independently replayed and its mathematical mapping was "
              + "checked; use fail for a mismatched or invalid replay, and inconclusive when the "
              + "required evidence is absent or cannot be verified.";
      default -> common;
    };
  }

  private static UsageRecord sumUsage(UsageRecord left, UsageRecord right) {
    return new UsageRecord(
        left.estimatedCostUsd() + right.estimatedCostUsd(),
        Math.addExact(left.inputTokens(), right.inputTokens()),
        left.latencyMs() + right.latencyMs(),
        Math.addExact(left.outputTokens(), right.outputTokens()),
        Math.addExact(left.totalTokens(), right.totalTokens()));
  }

  private static String rootMessage(Throwable failure) {
    Throwable root = failure;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return prefix(
        root.getClass().getSimpleName()
            + ": "
            + Objects.toString(root.getMessage(), "structured output validation failed"),
        2_000);
  }

  private static String prefix(String value, int maximumCharacters) {
    if (value.length() <= maximumCharacters) {
      return value;
    }
    return value.substring(0, maximumCharacters);
  }

  private <T> Parsed<T> parse(String raw, Class<T> responseType) {
    try {
      String extracted = JsonObjectExtractor.firstBalancedObject(raw);
      return parseNormalized(extracted, responseType, false);
    } catch (RuntimeException first) {
      try {
        String repaired = repairer.repair(raw);
        return parseNormalized(repaired, responseType, true);
      } catch (RuntimeException second) {
        second.addSuppressed(first);
        throw new StructuredOutputError(
            "provider output failed strict contract parsing after bounded repair",
            second);
      }
    }
  }

  private static <T> Parsed<T> parseNormalized(
      String json, Class<T> responseType, boolean representationRepaired) {
    JsonNode parsed = ContractObjectMapper.parseTree(json);
    if (!(parsed instanceof ObjectNode payload)) {
      throw new StructuredOutputError("provider output is not a JSON object");
    }
    StructuredPayloadNormalizer.stripServerOwnedHashes(payload);
    List<String> normalizations = StructuredPayloadNormalizer.normalize(payload, responseType);
    T value = ContractObjectMapper.read(ContractObjectMapper.write(payload), responseType);
    return new Parsed<>(value, representationRepaired || !normalizations.isEmpty());
  }

  private static UsageRecord usage(LLMResponse response, BigDecimal cost) {
    return new UsageRecord(
        cost.doubleValue(),
        Math.toIntExact(response.inputTokens()),
        response.latencyMs(),
        Math.toIntExact(response.outputTokens()),
        Math.toIntExact(response.totalTokens()));
  }

  private static boolean reasoningBudgetExhausted(
      LLMResponse response, int requestedOutputTokens) {
    if (!response.text().isBlank()
        || !response.metadata().path("reasoning").path("present").asBoolean(false)) {
      return false;
    }
    return "length".equals(response.finishReason())
        || response.outputTokens() >= requestedOutputTokens;
  }

  private static ReasoningBudgetExhaustedError reasoningBudgetExhaustedError(
      String agentId,
      LLMResponse response,
      int requestedOutputTokens,
      BigDecimal cost) {
    Map<String, Object> progress = new LinkedHashMap<>();
    progress.put("output_tokens", response.outputTokens());
    progress.put("max_output_tokens", requestedOutputTokens);
    progress.put("finish_reason", Objects.toString(response.finishReason(), ""));
    progress.put(
        "reasoning_characters",
        response.metadata().path("reasoning").path("characters").asLong(0L));
    return new ReasoningBudgetExhaustedError(
        agentId + " exhausted the output budget in reasoning without returning a public artifact",
        usage(response, cost),
        progress);
  }

  private static String artifactHash(String reference) {
    String prefix = "artifact://sha256/";
    if (!reference.startsWith(prefix)) {
      throw new IllegalArgumentException("expected content-addressed artifact");
    }
    return reference.substring(prefix.length());
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the JDK", exception);
    }
  }

  private record Parsed<T>(T value, boolean repaired) {}
}
