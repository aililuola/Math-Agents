package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.ComputationDecision;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionStatus;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
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
  private final ComputationExecutionService executionService;
  private final ConcurrentMap<String, ExperimentSpec> preparedByExperiment =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, ComputationExecutionContext> contextByExperiment =
      new ConcurrentHashMap<>();

  public ComputationBroker(
      String runId,
      ComputationLimits limits,
      ComputationHandlerRegistry registry,
      ComputationCache cache) {
    this(runId, limits, registry, cache, new InMemoryComputationArtifactStore());
  }

  public ComputationBroker(
      String runId,
      ComputationLimits limits,
      ComputationHandlerRegistry registry,
      ComputationCache cache,
      ComputationArtifactStore artifactStore) {
    this(
        runId,
        limits,
        registry,
        cache,
        new ComputationExecutionService(
            runId,
            registry.capabilityRegistry(),
            cache,
            artifactStore,
            new ComputationExecutionLedger(),
            new ComputationVerificationLedger(),
            new ComputationOutcomeReceiptLedger()));
  }

  public ComputationBroker(
      String runId,
      ComputationLimits limits,
      ComputationHandlerRegistry registry,
      ComputationCache cache,
      ComputationExecutionService executionService) {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("runId is required");
    }
    this.runId = runId;
    this.limits = java.util.Objects.requireNonNull(limits, "limits");
    this.registry = java.util.Objects.requireNonNull(registry, "registry");
    this.cache = java.util.Objects.requireNonNull(cache, "cache");
    this.executionService =
        java.util.Objects.requireNonNull(executionService, "executionService");
    this.ledger = new ComputationLedger(executionService.executions());
    this.policy = new ComputationPolicy(limits);
  }

  public PreparedDecision decide(ExperimentSpec requested, ComputationContext context) {
    ExperimentSpec spec = prepare(requested);
    preparedByExperiment.put(spec.experimentId(), spec);
    contextByExperiment.put(
        spec.experimentId(), ComputationExecutionContext.legacy(context.pathId()));
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
    ComputationExecutionContext context =
        contextByExperiment.getOrDefault(
            requested.experimentId(), ComputationExecutionContext.legacy(pathId(requested)));
    return runExperiment(requested, decision, program, context);
  }

  public ExperimentResult runExperiment(
      ExperimentSpec requested,
      ComputationDecision decision,
      ExperimentProgram program,
      ComputationExecutionContext context) {
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
    ComputationExecutionContext bound =
        context == null ? ComputationExecutionContext.legacy(pathId(spec)) : context;
    contextByExperiment.put(spec.experimentId(), bound);
    return executionService.execute(spec, decision, program, bound).result();
  }

  /** Audits the independently verified durable receipt without rerunning the producer. */
  public ComputationAudit auditExperiment(
      ExperimentSpec requested,
      ComputationDecision decision,
      ExperimentProgram program,
      ExperimentResult recorded) {
    Objects.requireNonNull(requested, "requested");
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(recorded, "recorded");
    ExperimentSpec spec = prepare(requested);
    ComputationCapabilityDescriptor descriptor =
        registry.capabilityRegistry().capability(spec.method()).descriptor();
    String identity = descriptor.verifierId() + "/" + descriptor.verifierVersion();
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

    try {
      ComputationExecutionContext context =
          contextByExperiment.getOrDefault(
              spec.experimentId(), ComputationExecutionContext.legacy(pathId(spec)));
      ComputationExecutionOutcome verified =
          executionService.execute(spec, decision, program, context);
      boolean valid =
          verified.verificationReceipt().valid()
              && recorded.resultHash().equals(verified.result().resultHash());
      return new ComputationAudit(
          spec.experimentId(),
          spec.requestHash(),
          recorded.resultHash(),
          verified.result().resultHash(),
          identity,
          true,
          valid,
          valid
              ? "certificate accepted by an independent verifier"
              : "independent certificate verification failed");
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

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The broker intentionally exposes its single state-owning execution facade.")
  public ComputationExecutionService executionService() {
    return executionService;
  }

  public ComputationExecutionState snapshot() {
    return executionService.snapshot();
  }

  public void restore(ComputationExecutionState state) {
    executionService.restore(state);
  }

  public void setExecutionHook(ComputationExecutionHook hook) {
    executionService.setHook(hook);
  }

  public void setStatePersister(ComputationStatePersister persister) {
    executionService.setPersister(persister);
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
