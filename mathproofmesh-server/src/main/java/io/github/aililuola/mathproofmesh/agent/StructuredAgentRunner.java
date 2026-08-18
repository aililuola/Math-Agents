package io.github.aililuola.mathproofmesh.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceBinding;
import io.github.aililuola.mathproofmesh.api.ReasoningTraceStore;
import io.github.aililuola.mathproofmesh.contract.CheckpointedResearchEnvelope;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingUpdateBatch;
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
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointFrameParser;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointTraceSpan;
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
        parseRetries,
        null);
  }

  public <T> CheckpointedStructuredCallResult<T> callCheckpointed(
      String runId,
      String idempotencyKey,
      String role,
      CheckpointedPromptBundle<T> bundle,
      AgentRuntime fixedAgent,
      String budgetBucket,
      Boolean thinkingEnabled,
      String reasoningEffort,
      ResearchCheckpointSink sink) {
    return callCheckpointed(
        runId,
        idempotencyKey,
        role,
        bundle,
        fixedAgent,
        budgetBucket,
        thinkingEnabled,
        reasoningEffort,
        sink,
        null);
  }

  public <T> CheckpointedStructuredCallResult<T> callCheckpointed(
      String runId,
      String idempotencyKey,
      String role,
      CheckpointedPromptBundle<T> bundle,
      AgentRuntime fixedAgent,
      String budgetBucket,
      Boolean thinkingEnabled,
      String reasoningEffort,
      ResearchCheckpointSink sink,
      ResearchCheckpointFallbackEvidence fallbackEvidence) {
    Objects.requireNonNull(bundle, "bundle");
    AgentRuntime agent =
        fixedAgent == null
            ? pool.select(role, Set.of(), List.of(), null, false)
            : fixedAgent;
    CheckpointContext context =
        new CheckpointContext(
            bundle.resultType(),
            Objects.requireNonNullElseGet(sink, ResearchCheckpointSink::noOp),
            fallbackEvidence);
    StructuredCallResult<CheckpointedResearchEnvelope> envelopeResult =
        callSingle(
            runId,
            idempotencyKey,
            bundle.promptBundle(),
            agent,
            budgetBucket,
            List.of(agent.id()),
            thinkingEnabled,
            reasoningEffort,
            parseRetries,
            context);
    CheckpointedResearchEnvelope envelope = context.validatedEnvelope(envelopeResult.value());
    StructuredCallResult<T> mapped;
    try {
      T value = parseCheckpointResult(envelope.result(), bundle.resultType());
      mapped = mapCheckpointed(envelopeResult, value, envelopeResult.repaired());
    } catch (StructuredOutputError failure) {
      StructuredCallResult<T> repairedResult =
          callSingle(
              runId,
              idempotencyKey + ":result-json-repair:1",
              resultRepairBundle(bundle, envelope.result(), failure),
              agent,
              budgetBucket,
              envelopeResult.attemptedAgents(),
              false,
              null,
              0,
              null);
      mapped =
          new StructuredCallResult<>(
              repairedResult.value(),
              repairedResult.runId(),
              repairedResult.callId(),
              repairedResult.agentId(),
              repairedResult.provider(),
              repairedResult.model(),
              repairedResult.promptArtifactRef(),
              repairedResult.responseArtifactRef(),
              sumUsage(envelopeResult.usage(), repairedResult.usage()),
              true,
              repairedResult.attemptedAgents());
    }
    return new CheckpointedStructuredCallResult<>(
        mapped,
        context.primaryProviderCallId(),
        context.traceFrames(),
        envelope.publicCheckpoint(),
        envelope.findingUpdates());
  }

  private static <T> StructuredCallResult<T> mapCheckpointed(
      StructuredCallResult<CheckpointedResearchEnvelope> envelope,
      T value,
      boolean repaired) {
    return new StructuredCallResult<>(
        value,
        envelope.runId(),
        envelope.callId(),
        envelope.agentId(),
        envelope.provider(),
        envelope.model(),
        envelope.promptArtifactRef(),
        envelope.responseArtifactRef(),
        envelope.usage(),
        repaired,
        envelope.attemptedAgents());
  }

  private static <T> T parseCheckpointResult(JsonNode result, Class<T> resultType) {
    try {
      JsonNode copy = result.deepCopy();
      if (copy instanceof ObjectNode object) {
        StructuredPayloadNormalizer.stripServerOwnedHashes(object);
        StructuredPayloadNormalizer.normalize(object, resultType);
      }
      return ContractObjectMapper.read(ContractObjectMapper.write(copy), resultType);
    } catch (RuntimeException exception) {
      throw new StructuredOutputError(
          "checkpoint envelope result failed its original strict contract", exception);
    }
  }

  private <T> PromptBundle<T> resultRepairBundle(
      CheckpointedPromptBundle<T> source, JsonNode malformed, StructuredOutputError failure) {
    PromptBundle<T> original = source.originalBundle();
    String user =
        ("[STAGE:"
                + original.stage()
                + "_json_repair]\nRepair only the malformed nested result below. Return one JSON "
                + "object matching the original result schema. The already committed public research "
                + "checkpoints are immutable and must not be repeated or removed.\n\nJSON SCHEMA:\n"
                + ContractObjectMapper.write(source.resultSchema())
                + "\n\nMALFORMED RESULT:\n"
                + ContractObjectMapper.write(malformed)
                + "\n\nVALIDATION ERROR:\n"
                + rootMessage(failure))
            .strip();
    return new PromptBundle<>(
        original.stage() + "_json_repair",
        "You repair malformed structured output without inventing mathematical content.",
        user,
        original.responseType(),
        0.0d,
        Math.min(original.maxOutputTokens(), jsonRepairMaxOutputTokens),
        false,
        source.resultSchema());
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
            parseRetries,
            null);
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

  public java.util.Optional<ReasoningTraceStore.CallArchive> reasoningTrace(
      String providerCallId) {
    if (reasoningTraces == null) {
      return java.util.Optional.empty();
    }
    return reasoningTraces.findByProviderCallId(providerCallId);
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
      int remainingParseRetries,
      CheckpointContext checkpointContext) {
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
      if (checkpointContext != null) {
        checkpointContext.bindPrimaryProviderCallId(planned.callId());
      }
      return replayExisting(
          planned,
          idempotencyKey,
          bundle,
          agent,
          budgetBucket,
          promptRef,
          attemptedAgents,
          remainingParseRetries,
          checkpointContext);
    }

    if (checkpointContext != null) {
      checkpointContext.bindPrimaryProviderCallId(generatedCallId);
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
              checkpointContext == null
                  ? bundle.responseType().getSimpleName()
                  : checkpointContext.providerSchemaName(),
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
                bundle.stage(),
                generatedCallId);
        ReasoningTraceBinding.Scope scope = binding.bind();
        try {
          response = agent.call(request);
        } finally {
          scope.close();
        }
      }
      String safeResponseText = redactor.redact(response.text());
      BigDecimal cost =
          CallLedger.tokenCost(
              response.inputTokens(),
              response.outputTokens(),
              agent.config().pricing().inputPerMillion(),
              agent.config().pricing().outputPerMillion());
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
                              "latency_ms", response.latencyMs(),
                              "cost_usd", cost),
                      "metadata", response.metadata())),
              "application/json",
              "provider-response:" + bundle.stage() + ":" + agent.id(),
              "short-term",
              "provider_response");
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
          remainingParseRetries,
          checkpointContext);
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
      int remainingParseRetries,
      CheckpointContext checkpointContext) {
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
        remainingParseRetries,
        checkpointContext);
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
      int remainingParseRetries,
      CheckpointContext checkpointContext) {
    if (checkpointContext != null) {
      checkpointContext.capture(
          callId, responseRef, safeResponseText, reasoningTraces, redactor, bundle.responseType());
    }
    if (reasoningBudgetExhausted(response, bundle.maxOutputTokens())) {
      throw reasoningBudgetExhaustedError(
          agent.id(),
          response,
          bundle.maxOutputTokens(),
          cost,
          callId,
          responseRef,
          reasoningTraces);
    }
    UsageRecord currentUsage = usage(response, cost);
    try {
      Parsed<T> parsed =
          parseCheckpointedOrLegacy(
              safeResponseText, bundle.responseType(), checkpointContext);
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
              remainingParseRetries - 1,
              checkpointContext);
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

  @SuppressWarnings("unchecked")
  private <T> Parsed<T> parseCheckpointedOrLegacy(
      String raw, Class<T> responseType, CheckpointContext checkpointContext) {
    if (checkpointContext == null || responseType != CheckpointedResearchEnvelope.class) {
      return parse(raw, responseType);
    }
    try {
      return parse(raw, responseType);
    } catch (StructuredOutputError envelopeFailure) {
      Parsed<?> legacy = parse(raw, checkpointContext.resultType());
      CheckpointedResearchEnvelope envelope =
          new CheckpointedResearchEnvelope(
              null,
              ResearchFindingUpdateBatch.empty(),
              ContractObjectMapper.toTree(legacy.value()));
      return new Parsed<>((T) envelope, legacy.repaired());
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
      BigDecimal cost,
      String providerCallId,
      String responseArtifactRef,
      ReasoningTraceStore reasoningTraces) {
    Map<String, Object> progress = new LinkedHashMap<>();
    progress.put("output_tokens", response.outputTokens());
    progress.put("max_output_tokens", requestedOutputTokens);
    progress.put("finish_reason", Objects.toString(response.finishReason(), ""));
    progress.put(
        "reasoning_characters",
        response.metadata().path("reasoning").path("characters").asLong(0L));
    progress.put("provider_call_id", providerCallId);
    progress.put("response_artifact_ref", responseArtifactRef);
    if (reasoningTraces != null) {
      reasoningTraces
          .findByProviderCallId(providerCallId)
          .ifPresent(
              trace -> {
                progress.put("reasoning_trace_call_id", trace.reasoningTraceCallId());
                progress.put("reasoning_trace_task_id", trace.taskId());
                progress.put("reasoning_trace_sha256", trace.sha256());
                progress.put("reasoning_trace_characters", trace.characters());
              });
    }
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

  private static final class CheckpointContext {
    private final Class<?> resultType;
    private final ResearchCheckpointSink sink;
    private final ResearchCheckpointFrameParser parser = new ResearchCheckpointFrameParser();
    private final List<ResearchCheckpointTraceSpan> traceFrames = new ArrayList<>();
    private final Set<String> committedKeys = new java.util.LinkedHashSet<>();
    private final ResearchCheckpointFallbackEvidence fallbackEvidence;
    private String primaryProviderCallId;

    private CheckpointContext(
        Class<?> resultType,
        ResearchCheckpointSink sink,
        ResearchCheckpointFallbackEvidence fallbackEvidence) {
      this.resultType = Objects.requireNonNull(resultType, "resultType");
      this.sink = Objects.requireNonNull(sink, "sink");
      this.fallbackEvidence = fallbackEvidence;
    }

    private String providerSchemaName() {
      return resultType.getSimpleName();
    }

    private Class<?> resultType() {
      return resultType;
    }

    private void capture(
        String providerCallId,
        String responseArtifactRef,
        String safeResponseText,
        ReasoningTraceStore reasoningTraces,
        PromptRedactor redactor,
        Class<?> responseType) {
      ReasoningTraceStore.CallArchive trace =
          reasoningTraces == null
              ? null
              : reasoningTraces.findByProviderCallId(providerCallId).orElse(null);
      List<ResearchCheckpointTraceSpan> spans =
          trace == null ? List.of() : parser.parse(trace.text(), trace.sha256());
      if (!spans.isEmpty()) {
        traceFrames.addAll(spans);
      }
      CheckpointedResearchEnvelope partial = parseCompleteEnvelope(safeResponseText);
      if (partial != null
          && partial.publicCheckpoint() != null
          && fallbackEvidence != null
          && !validFallbackFrame(partial.publicCheckpoint(), fallbackEvidence)) {
        partial = null;
      }
      ResearchCheckpointCapture capture =
          new ResearchCheckpointCapture(
              providerCallId,
              responseArtifactRef,
              trace == null ? null : trace.reasoningTraceCallId(),
              trace == null ? null : trace.taskId(),
              trace == null ? null : trace.sha256(),
              trace == null ? 0L : trace.characters(),
              spans,
              partial == null ? null : partial.publicCheckpoint(),
              partial == null
                  ? ResearchFindingUpdateBatch.empty()
                  : partial.findingUpdates());
      if (!spans.isEmpty()
          || capture.envelopeFrame() != null
          || !capture.findingUpdates().dispositions().isEmpty()) {
        commit(capture);
      }
    }

    private void commit(ResearchCheckpointCapture capture) {
      String key =
          CanonicalCheckpointKey.value(capture, resultType);
      if (committedKeys.add(key)) {
        sink.commit(capture);
      }
    }

    private String primaryProviderCallId() {
      if (primaryProviderCallId == null) {
        throw new IllegalStateException("checkpointed provider call was not captured");
      }
      return primaryProviderCallId;
    }

    private void bindPrimaryProviderCallId(String providerCallId) {
      if (primaryProviderCallId == null) {
        primaryProviderCallId = Objects.requireNonNull(providerCallId, "providerCallId");
      }
    }

    private List<ResearchCheckpointTraceSpan> traceFrames() {
      return List.copyOf(traceFrames);
    }

    private CheckpointedResearchEnvelope validatedEnvelope(
        CheckpointedResearchEnvelope envelope) {
      if (fallbackEvidence == null
          || envelope.publicCheckpoint() == null
          || validFallbackFrame(envelope.publicCheckpoint(), fallbackEvidence)) {
        return envelope;
      }
      return new CheckpointedResearchEnvelope(
          null, ResearchFindingUpdateBatch.empty(), envelope.result());
    }

    private static CheckpointedResearchEnvelope parseCompleteEnvelope(String text) {
      try {
        String object = JsonObjectExtractor.firstBalancedObject(text);
        return ContractObjectMapper.read(object, CheckpointedResearchEnvelope.class);
      } catch (RuntimeException ignored) {
        return null;
      }
    }

    private static boolean validFallbackFrame(
        io.github.aililuola.mathproofmesh.contract.ResearchCheckpointFrame frame,
        ResearchCheckpointFallbackEvidence evidence) {
      return frame.findings().stream()
          .allMatch(
              finding ->
                  finding.sourceQuote() != null
                      && ResearchCheckpointTraceSpan.validatesExactQuote(
                          evidence.trace(),
                          finding.quoteStart(),
                          finding.quoteEnd(),
                          finding.sourceQuote(),
                          finding.quoteSha256()));
    }
  }

  private static final class CanonicalCheckpointKey {
    private CanonicalCheckpointKey() {}

    private static String value(ResearchCheckpointCapture capture, Class<?> resultType) {
      List<String> frames =
          capture.traceFrames().stream()
              .map(ResearchCheckpointTraceSpan::markerSha256)
              .sorted()
              .toList();
      String envelope =
          capture.envelopeFrame() == null
              ? ""
              : io.github.aililuola.mathproofmesh.contract.CanonicalJson.stableHash(
                  capture.envelopeFrame());
      String updates =
          io.github.aililuola.mathproofmesh.contract.CanonicalJson.stableHash(
              capture.findingUpdates());
      return io.github.aililuola.mathproofmesh.contract.CanonicalJson.stableHash(
          List.of(
              capture.providerCallId(),
              capture.responseArtifactRef(),
              resultType.getName(),
              frames,
              envelope,
              updates));
    }
  }

  private record Parsed<T>(T value, boolean repaired) {}
}
