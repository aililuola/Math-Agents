package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ComputationDecision;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Coordinates contract normalization, policy, execution, evidence binding, cache, and quota. */
public final class ComputationBroker {
  private final String runId;
  private final ComputationLimits limits;
  private final ComputationHandlerRegistry registry;
  private final ComputationCache cache;
  private final ComputationLedger ledger;
  private final ComputationPolicy policy;
  private final ConcurrentMap<String, ExperimentSpec> preparedByExperiment =
      new ConcurrentHashMap<>();

  public ComputationBroker(
      String runId,
      ComputationLimits limits,
      ComputationHandlerRegistry registry,
      ComputationCache cache) {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("runId is required");
    }
    this.runId = runId;
    this.limits = java.util.Objects.requireNonNull(limits, "limits");
    this.registry = java.util.Objects.requireNonNull(registry, "registry");
    this.cache = java.util.Objects.requireNonNull(cache, "cache");
    this.ledger = new ComputationLedger();
    this.policy = new ComputationPolicy(limits);
  }

  public PreparedDecision decide(ExperimentSpec requested, ComputationContext context) {
    ExperimentSpec spec = prepare(requested);
    preparedByExperiment.put(spec.experimentId(), spec);
    String identity = registry.toolIdentity(spec.method());
    Optional<ExperimentResult> cached =
        limits.cacheResults()
            ? cache.find(runId, spec.executionHash(), identity)
            : Optional.empty();
    ComputationDecision decision =
        policy.evaluate(
            spec,
            context,
            ledger.usage(context.pathId()),
            registry.supports(spec.method()),
            cached);
    return new PreparedDecision(spec, decision);
  }

  public ExperimentResult runExperiment(
      ExperimentSpec requested,
      ComputationDecision decision) {
    return runExperiment(requested, decision, null);
  }

  public ExperimentResult runExperiment(
      ExperimentSpec requested,
      ComputationDecision decision,
      ExperimentProgram program) {
    ExperimentSpec spec =
        preparedByExperiment.getOrDefault(requested.experimentId(), prepare(requested));
    if (decision.decision() != ComputationDecisionStatus.ALLOW) {
      throw new IllegalStateException(
          "only an allowed computation decision may be executed");
    }
    if (!decision.requestHash().equals(spec.requestHash())) {
      throw new IllegalArgumentException(
          "computation decision does not match the prepared request");
    }
    String identity = registry.toolIdentity(spec.method());
    if (limits.cacheResults()) {
      Optional<ExperimentResult> cached =
          cache.find(runId, spec.executionHash(), identity);
      if (cached.isPresent()) {
        return cachedForRequest(cached.get(), spec);
      }
    }

    long started = System.nanoTime();
    HandlerEvidence evidence;
    String error = null;
    try {
      evidence = registry.execute(spec, program);
      if (evidenceSize(evidence) > limits.maxOutputChars()) {
        throw new IllegalArgumentException(
            "handler output exceeds max_output_chars=" + limits.maxOutputChars());
      }
    } catch (RuntimeException exception) {
      error = boundedMessage(exception);
      evidence =
          new HandlerEvidence(
              ExperimentOutcome.INCONCLUSIVE,
              EvidenceStrength.HEURISTIC,
              ComputationJson.object().put("method", spec.method().value()),
              null,
              null,
              false,
              0,
              false,
              List.of(error),
              null);
    }
    double runtimeSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
    ExperimentResult result =
        toResult(spec, program, identity, evidence, runtimeSeconds, error, false);
    ledger.record(pathId(spec), runtimeSeconds);
    if (limits.cacheResults()) {
      cache.put(runId, spec.executionHash(), identity, result);
    }
    return result;
  }

  /** Replays a recorded computation through the pinned handler and compares canonical evidence. */
  public ComputationAudit auditExperiment(
      ExperimentSpec requested,
      ComputationDecision decision,
      ExperimentProgram program,
      ExperimentResult recorded) {
    Objects.requireNonNull(requested, "requested");
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(recorded, "recorded");
    ExperimentSpec spec = prepare(requested);
    String identity = registry.toolIdentity(spec.method());
    if (decision.decision() != ComputationDecisionStatus.ALLOW) {
      return ComputationAudit.failed(
          spec.experimentId(), spec.requestHash(), recorded.resultHash(), identity,
          "the original computation was not admitted");
    }
    if (!decision.requestHash().equals(spec.requestHash())
        || !recorded.requestHash().equals(spec.requestHash())) {
      return ComputationAudit.failed(
          spec.experimentId(), spec.requestHash(), recorded.resultHash(), identity,
          "request hash changed before replay");
    }
    if (recorded.method() != spec.method()
        || !recorded.toolVersion().equals(identity)
        || !recorded.experimentId().equals(spec.experimentId())) {
      return ComputationAudit.failed(
          spec.experimentId(), spec.requestHash(), recorded.resultHash(), identity,
          "recorded tool identity or experiment binding changed");
    }
    String expectedProgramHash = program == null ? null : program.codeHash();
    if (!Objects.equals(recorded.programHash(), expectedProgramHash)) {
      return ComputationAudit.failed(
          spec.experimentId(), spec.requestHash(), recorded.resultHash(), identity,
          "recorded program hash changed before replay");
    }

    long started = System.nanoTime();
    try {
      HandlerEvidence evidence = registry.execute(spec, program);
      if (evidenceSize(evidence) > limits.maxOutputChars()) {
        return ComputationAudit.failed(
            spec.experimentId(), spec.requestHash(), recorded.resultHash(), identity,
            "replayed handler output exceeded the configured bound");
      }
      ExperimentResult replayed =
          toResult(
              spec,
              program,
              identity,
              evidence,
              (System.nanoTime() - started) / 1_000_000_000.0d,
              null,
              false);
      boolean valid =
          recorded.error() == null && recorded.resultHash().equals(replayed.resultHash());
      return new ComputationAudit(
          spec.experimentId(),
          spec.requestHash(),
          recorded.resultHash(),
          replayed.resultHash(),
          identity,
          true,
          valid,
          valid ? "canonical evidence matched an independent replay" : "replayed evidence changed");
    } catch (RuntimeException exception) {
      return new ComputationAudit(
          spec.experimentId(),
          spec.requestHash(),
          recorded.resultHash(),
          "",
          identity,
          true,
          false,
          "replay failed: " + exception.getClass().getSimpleName());
    }
  }

  public ComputationLedger ledger() {
    return ledger;
  }

  public String runId() {
    return runId;
  }

  public ExperimentSpec prepare(ExperimentSpec requested) {
    ContractsFunctions.Normalization normalization =
        ContractsFunctions.normalizeExploratoryContract(requested);
    ExperimentSpec normalized = normalization.spec();
    ObjectNode fingerprint = ComputationJson.object();
    fingerprint.put("tool_name", normalized.method().value());
    fingerprint.put("tool_version", registry.toolIdentity(normalized.method()));
    fingerprint.put("runtime", "java-25");
    return normalized.bindRuntimeFingerprint(fingerprint);
  }

  private static ExperimentResult toResult(
      ExperimentSpec spec,
      ExperimentProgram program,
      String identity,
      HandlerEvidence evidence,
      double runtimeSeconds,
      String error,
      boolean cached) {
    return new ExperimentResult(
        List.<EvidenceRef>of(),
        cached,
        evidence.casesChecked(),
        evidence.certificate(),
        evidence.counterexample(),
        null,
        error,
        evidence.evidenceStrength(),
        evidence.exactArithmetic(),
        spec.experimentId(),
        evidence.independentlyVerified(),
        spec.method(),
        evidence.outcome(),
        spec.parentCheckpointId(),
        spec.pathId(),
        program == null ? null : program.codeHash(),
        spec.requestHash(),
        null,
        runtimeSeconds,
        evidence.scope(),
        spec.targetClaim(),
        spec.targetClaimId(),
        spec.method().value(),
        identity,
        evidence.verificationNotes(),
        spec.claimEvidenceSemanticBinding());
  }

  private static ExperimentResult cachedForRequest(
      ExperimentResult canonical, ExperimentSpec requested) {
    boolean claimBound = requested.claimEvidenceSemanticBinding() != null;
    return new ExperimentResult(
        canonical.artifactRefs(),
        true,
        canonical.casesChecked(),
        canonical.certificate(),
        canonical.counterexample(),
        canonical.createdAt(),
        canonical.error(),
        canonical.evidenceStrength(),
        canonical.exactArithmetic(),
        requested.experimentId(),
        canonical.independentlyVerified(),
        canonical.method(),
        canonical.outcome(),
        requested.parentCheckpointId(),
        requested.pathId(),
        canonical.programHash(),
        claimBound ? requested.requestHash() : canonical.requestHash(),
        claimBound ? null : canonical.resultHash(),
        canonical.runtimeSeconds(),
        canonical.scope(),
        canonical.targetClaim(),
        claimBound ? requested.targetClaimId() : canonical.targetClaimId(),
        canonical.toolName(),
        canonical.toolVersion(),
        canonical.verificationNotes(),
        claimBound ? requested.claimEvidenceSemanticBinding() : null);
  }

  private static int evidenceSize(HandlerEvidence evidence) {
    int size = evidence.scope().toString().length();
    if (evidence.counterexample() != null) {
      size += evidence.counterexample().toString().length();
    }
    if (evidence.certificate() != null) {
      size += evidence.certificate().toString().length();
    }
    if (evidence.rawOutput() != null) {
      size += evidence.rawOutput().toString().length();
    }
    size += evidence.verificationNotes().stream().mapToInt(String::length).sum();
    return size;
  }

  private static String boundedMessage(RuntimeException exception) {
    String message =
        exception.getMessage() == null
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
    return message.length() <= 1_000 ? message : message.substring(0, 1_000);
  }

  private static String pathId(ExperimentSpec spec) {
    return spec.pathId() == null || spec.pathId().isBlank() ? "unassigned" : spec.pathId();
  }

  public record PreparedDecision(ExperimentSpec spec, ComputationDecision decision) {}

  public record ComputationAudit(
      String experimentId,
      String requestHash,
      String recordedResultHash,
      String replayedResultHash,
      String toolVersion,
      boolean executed,
      boolean valid,
      String diagnostic) {
    public ComputationAudit {
      experimentId = Objects.requireNonNull(experimentId, "experimentId");
      requestHash = Objects.requireNonNull(requestHash, "requestHash");
      recordedResultHash = recordedResultHash == null ? "" : recordedResultHash;
      replayedResultHash = replayedResultHash == null ? "" : replayedResultHash;
      toolVersion = Objects.requireNonNull(toolVersion, "toolVersion");
      diagnostic = diagnostic == null ? "" : diagnostic.strip();
      if (!executed && valid) {
        throw new IllegalArgumentException("an unexecuted computation audit cannot pass");
      }
    }

    private static ComputationAudit failed(
        String experimentId,
        String requestHash,
        String recordedResultHash,
        String toolVersion,
        String diagnostic) {
      return new ComputationAudit(
          experimentId,
          requestHash,
          recordedResultHash,
          "",
          toolVersion,
          false,
          false,
          diagnostic);
    }
  }
}
