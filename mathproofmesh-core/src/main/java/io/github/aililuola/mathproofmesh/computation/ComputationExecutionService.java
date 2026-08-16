package io.github.aililuola.mathproofmesh.computation;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ComputationCertificateEnvelope;
import io.github.aililuola.mathproofmesh.contract.ComputationDecision;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionPlan;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionStatus;
import io.github.aililuola.mathproofmesh.contract.ComputationOutcomeApplicationReceipt;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationReceipt;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationStatus;
import io.github.aililuola.mathproofmesh.contract.ComputationVerifiedAuthority;
import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Unified crash-recoverable execution, verification, and typed outcome pipeline. */
public final class ComputationExecutionService {
  private final String runId;
  private final ComputationCapabilityRegistry capabilities;
  private final ComputationRequestCompiler compiler;
  private final ComputationCache cache;
  private final ComputationArtifactStore artifacts;
  private final ComputationExecutionLedger executions;
  private final ComputationVerificationLedger verifications;
  private final ComputationOutcomeReceiptLedger outcomeReceipts;
  private final ComputationOutcomeProjector projector = new ComputationOutcomeProjector();
  private final ConcurrentMap<String, ComputationExecutionOutcome> outcomesByExperiment =
      new ConcurrentHashMap<>();
  private volatile ComputationExecutionHook hook = ComputationExecutionHook.noOp();
  private volatile ComputationStatePersister persister = ComputationStatePersister.noOp();

  public ComputationExecutionService(
      String runId,
      ComputationCapabilityRegistry capabilities,
      ComputationCache cache,
      ComputationArtifactStore artifacts,
      ComputationExecutionLedger executions,
      ComputationVerificationLedger verifications,
      ComputationOutcomeReceiptLedger outcomeReceipts) {
    this.runId = required(runId, "runId");
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.compiler = new ComputationRequestCompiler(runId, capabilities);
    this.cache = Objects.requireNonNull(cache, "cache");
    this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    this.executions = Objects.requireNonNull(executions, "executions");
    this.verifications = Objects.requireNonNull(verifications, "verifications");
    this.outcomeReceipts = Objects.requireNonNull(outcomeReceipts, "outcomeReceipts");
  }

  public static ComputationExecutionService inMemory(
      String runId,
      ComputationCapabilityRegistry capabilities,
      ComputationCache cache) {
    return new ComputationExecutionService(
        runId,
        capabilities,
        cache,
        new InMemoryComputationArtifactStore(),
        new ComputationExecutionLedger(),
        new ComputationVerificationLedger(),
        new ComputationOutcomeReceiptLedger());
  }

  public ComputationExecutionOutcome execute(
      ExperimentSpec spec,
      ComputationDecision decision,
      ExperimentProgram program,
      ComputationExecutionContext context) {
    Objects.requireNonNull(decision, "decision");
    if (decision.decision() != ComputationDecisionStatus.ALLOW) {
      throw new IllegalStateException("only an allowed computation decision may be executed");
    }
    if (!decision.requestHash().equals(spec.requestHash())) {
      throw new IllegalArgumentException("computation decision does not match request");
    }
    ValidatedComputationRequest request = compiler.compile(spec, program, context);
    hook.onPoint(ComputationExecutionFailurePoint.AFTER_CAPABILITY_RESOLVE, request.executionId());
    RegisteredComputationCapability capability = capabilities.capability(spec.method());
    ComputationArtifactRecord requestArtifact =
        artifacts
            .find(request.executionId(), ComputationArtifactKind.REQUEST)
            .orElseGet(
                () ->
                    artifacts.write(
                        request.executionId(), ComputationArtifactKind.REQUEST, spec));
    ComputationExecutionRecord record =
        executions.reserve(
            runId,
            context.routeId(),
            spec,
            request.capability(),
            context.claimId(),
            context.obligationId(),
            requestArtifact.reference(),
            context.round());
    persist("computation_admitted");
    hook.onPoint(ComputationExecutionFailurePoint.AFTER_EXECUTION_ADMITTED, request.executionId());

    Optional<ComputationExecutionOutcome> completed = completedOutcome(spec, request, record);
    if (completed.isPresent()) {
      ComputationExecutionOutcome outcome = completed.orElseThrow();
      outcomesByExperiment.put(spec.experimentId(), outcome);
      return outcome;
    }

    if (record.status() == ComputationExecutionStatus.RUNNING
        && request.capability().backendKind() == ComputationBackendKind.SANDBOXED_PYTHON
        && artifacts.find(request.executionId(), ComputationArtifactKind.RESULT).isEmpty()) {
      executions.markTerminal(
          request.executionId(),
          ComputationExecutionStatus.QUARANTINED,
          "AMBIGUOUS_SANDBOX_EXECUTION",
          context.round());
      persist("computation_quarantined");
      throw new IllegalStateException("ambiguous sandbox execution was quarantined");
    }

    ResultAndCertificate durable = recoverDurableProducerArtifacts(request, record).orElse(null);
    boolean cacheHit = false;
    if (durable != null && record.status() == ComputationExecutionStatus.RUNNING) {
      executions.markResultDurable(
          request.executionId(),
          durable.resultRecord().reference(),
          durable.certificateRecord().reference(),
          durable.result().runtimeSeconds(),
          true,
          false,
          context.round());
      persist("computation_result_recovered");
    }
    if (durable == null) {
      ComputationCacheKey cacheKey = cacheKey(request);
      Optional<CanonicalComputationCacheEntry> cached = cache.find(cacheKey);
      if (cached.isPresent()) {
        cacheHit = true;
        ComputationResultArtifact rebound = rebind(cached.orElseThrow().result(), request);
        ComputationCertificateEnvelope reboundCertificate =
            ComputationCertificateFactory.create(request, rebound);
        durable = persistProducerArtifacts(request, rebound, reboundCertificate);
        executions.markResultDurable(
            request.executionId(),
            durable.resultRecord().reference(),
            durable.certificateRecord().reference(),
            0.0d,
            false,
            true,
            context.round());
      } else {
        executions.markRunning(request.executionId(), context.round());
        persist("computation_running");
        long started = System.nanoTime();
        ProducedComputation produced;
        String error = "";
        try {
          produced = capability.producer().execute(request);
        } catch (RuntimeException exception) {
          error = boundedMessage(exception);
          produced =
              new ProducedComputation(
                  HandlerEvidence.inconclusive(
                      error, ComputationJson.object().put("method", spec.method().value())),
                  request.capability().producerId(),
                  request.capability().producerVersion());
        }
        double cpuSeconds = (System.nanoTime() - started) / 1_000_000_000.0d;
        ComputationResultArtifact raw = rawResult(request, produced, error, cpuSeconds);
        ComputationCertificateEnvelope certificate =
            ComputationCertificateFactory.create(request, raw);
        durable = persistProducerArtifacts(request, raw, certificate);
        hook.onPoint(
            ComputationExecutionFailurePoint.AFTER_PRODUCER_BEFORE_RESULT_DURABLE,
            request.executionId());
        executions.markResultDurable(
            request.executionId(),
            durable.resultRecord().reference(),
            durable.certificateRecord().reference(),
            cpuSeconds,
            true,
            false,
            context.round());
        cache.put(cacheKey, new CanonicalComputationCacheEntry(raw, certificate));
      }
      persist("computation_result_durable");
      hook.onPoint(ComputationExecutionFailurePoint.AFTER_RESULT_DURABLE, request.executionId());
    }

    ComputationVerificationReceipt receipt =
        recoverVerification(request, durable, context.round());
    hook.onPoint(ComputationExecutionFailurePoint.AFTER_VERIFICATION_DURABLE, request.executionId());
    List<ComputationArtifactRecord> evidenceArtifacts =
        List.of(
            requestArtifact,
            durable.resultRecord(),
            durable.certificateRecord(),
            artifacts
                .find(request.executionId(), ComputationArtifactKind.VERIFICATION_RECEIPT)
                .orElseThrow());
    ExperimentResult preliminary =
        publicResult(spec, program, durable.result(), receipt, evidenceArtifacts, cacheHit);
    ComputationEvidenceGate.EvidenceAuthority authority =
        ComputationEvidenceGate.authority(preliminary, receipt, request.capability());
    ComputationDecisionPlan plan = projector.plan(spec, context, receipt);
    ComputationOutcomeApplicationReceipt application =
        projector.project(request.executionId(), preliminary.outcome(), plan, receipt);
    ComputationArtifactRecord applicationRecord =
        artifacts.write(
            request.executionId(),
            ComputationArtifactKind.OUTCOME_APPLICATION_RECEIPT,
            application);
    outcomeReceipts.record(application);
    executions.markAuthorityApplied(request.executionId(), applicationRecord.reference(), context.round());
    hook.onPoint(
        ComputationExecutionFailurePoint.AFTER_AUTHORITY_APPLIED_BEFORE_CHECKPOINT,
        request.executionId());
    persist("computation_authority_applied");
    hook.onPoint(ComputationExecutionFailurePoint.AFTER_ATOMIC_CHECKPOINT_MOVE, request.executionId());

    List<ComputationArtifactRecord> allArtifacts = new ArrayList<>(evidenceArtifacts);
    allArtifacts.add(applicationRecord);
    ExperimentResult result =
        publicResult(spec, program, durable.result(), receipt, allArtifacts, cacheHit);
    ComputationArtifactBundle bundle =
        new ComputationArtifactBundle(
            requestArtifact,
            durable.resultRecord(),
            durable.certificateRecord(),
            evidenceArtifacts.get(3),
            applicationRecord);
    ComputationExecutionOutcome outcome =
        new ComputationExecutionOutcome(
            request.executionId(),
            result,
            durable.certificate(),
            receipt,
            application,
            bundle,
            authority,
            cacheHit);
    outcomesByExperiment.put(spec.experimentId(), outcome);
    return outcome;
  }

  public Optional<ComputationExecutionOutcome> lastOutcome(String experimentId) {
    return Optional.ofNullable(outcomesByExperiment.get(experimentId));
  }

  public ComputationExecutionState snapshot() {
    return new ComputationExecutionState(
        capabilities.snapshot(),
        executions.snapshot(),
        artifacts.snapshot(),
        verifications.snapshot(),
        outcomeReceipts.snapshot());
  }

  public void restore(ComputationExecutionState state) {
    ComputationExecutionState safe =
        state == null ? ComputationExecutionState.empty() : state;
    executions.restore(safe.executions());
    artifacts.restore(safe.artifacts());
    verifications.restore(safe.verifications());
    outcomeReceipts.restore(safe.outcomeReceipts());
    rebuildCanonicalCache();
    outcomesByExperiment.clear();
  }

  private void rebuildCanonicalCache() {
    for (ComputationExecutionRecord record : executions.records()) {
      if (record.resultArtifactRef().isEmpty()
          || record.certificateArtifactRef().isEmpty()
          || record.backend() == ComputationBackendKind.SANDBOXED_PYTHON) {
        continue;
      }
      Optional<ExperimentSpec> spec =
          artifacts.read(record.requestArtifactRef(), ExperimentSpec.class);
      Optional<ComputationResultArtifact> result =
          artifacts.read(record.resultArtifactRef(), ComputationResultArtifact.class);
      Optional<ComputationCertificateEnvelope> certificate =
          artifacts.read(record.certificateArtifactRef(), ComputationCertificateEnvelope.class);
      if (spec.isEmpty() || result.isEmpty() || certificate.isEmpty()) {
        continue;
      }
      ComputationCapabilityDescriptor descriptor =
          capabilities.capability(spec.orElseThrow().method()).descriptor();
      ValidatedComputationRequest request =
          new ValidatedComputationRequest(
              spec.orElseThrow(), descriptor, null, record.executionId());
      cache.put(
          cacheKey(request),
          new CanonicalComputationCacheEntry(
              result.orElseThrow(), certificate.orElseThrow()));
    }
  }

  /** Deterministically imports a trusted v17 trace without invoking a producer or verifier. */
  public void importLegacy(
      String routeId,
      ExperimentSpec spec,
      ExperimentProgram program,
      ExperimentResult result,
      ComputationEvidenceGate.EvidenceAuthority legacyAuthority,
      boolean replayValid,
      int round) {
    Objects.requireNonNull(spec, "spec");
    Objects.requireNonNull(result, "result");
    ComputationExecutionContext context = ComputationExecutionContext.legacy(routeId);
    ValidatedComputationRequest request = compiler.compile(spec, program, context);
    if (executions.find(request.executionId()).isPresent()) {
      return;
    }
    ComputationArtifactRecord requestRecord =
        artifacts
            .find(request.executionId(), ComputationArtifactKind.REQUEST)
            .orElseGet(
                () ->
                    artifacts.write(
                        request.executionId(), ComputationArtifactKind.REQUEST, spec));
    executions.reserve(
        runId,
        routeId,
        spec,
        request.capability(),
        result.targetClaimId(),
        "",
        requestRecord.reference(),
        round);
    executions.markRunning(request.executionId(), round);
    ComputationResultArtifact raw =
        new ComputationResultArtifact(
            spec.requestHash(),
            spec.executionHash(),
            result.outcome(),
            result.evidenceStrength(),
            result.scope(),
            result.counterexample(),
            result.certificate(),
            result.exactArithmetic(),
            result.casesChecked(),
            result.runtimeSeconds(),
            request.capability().producerId(),
            request.capability().producerVersion(),
            result.error(),
            null);
    ComputationCertificateEnvelope certificate =
        ComputationCertificateFactory.create(request, raw);
    ResultAndCertificate durable = persistProducerArtifacts(request, raw, certificate);
    executions.markResultDurable(
        request.executionId(),
        durable.resultRecord().reference(),
        durable.certificateRecord().reference(),
        result.runtimeSeconds(),
        true,
        result.cached(),
        round);
    ComputationVerifiedAuthority authority =
        replayValid ? migratedAuthority(legacyAuthority) : ComputationVerifiedAuthority.AUDIT_ONLY;
    ComputationVerificationReceipt receipt =
        new ComputationVerificationReceipt(
            "legacy-verification-" + request.executionId(),
            certificate.certificateHash(),
            request.capability().verifierId(),
            request.capability().verifierVersion(),
            replayValid
                ? ComputationVerificationStatus.VALID
                : ComputationVerificationStatus.INVALID,
            replayValid,
            authority,
            certificate.scopeHash(),
            List.of(
                replayValid
                    ? "LEGACY_VERIFICATION_ACCEPTED"
                    : "LEGACY_AUDIT_ONLY"),
            "1970-01-01T00:00:00Z",
            null);
    ComputationArtifactRecord verificationRecord =
        artifacts.write(
            request.executionId(), ComputationArtifactKind.VERIFICATION_RECEIPT, receipt);
    verifications.record(receipt);
    executions.markVerificationDurable(
        request.executionId(), verificationRecord.reference(), authority, round);
    ComputationDecisionPlan plan = projector.plan(spec, context, receipt);
    ComputationOutcomeApplicationReceipt application =
        projector.project(request.executionId(), result.outcome(), plan, receipt);
    ComputationArtifactRecord applicationRecord =
        artifacts.write(
            request.executionId(),
            ComputationArtifactKind.OUTCOME_APPLICATION_RECEIPT,
            application);
    outcomeReceipts.record(application);
    executions.markAuthorityApplied(request.executionId(), applicationRecord.reference(), round);
  }

  /** Imports an incomplete v17 trace as audit-only evidence without invoking any backend. */
  public void importLegacyAudit(
      String routeId,
      ExperimentSpec spec,
      ExperimentProgram program,
      int round) {
    Objects.requireNonNull(spec, "spec");
    ComputationExecutionContext context = ComputationExecutionContext.legacy(routeId);
    ValidatedComputationRequest request = compiler.compile(spec, program, context);
    if (executions.find(request.executionId()).isPresent()) {
      return;
    }
    ComputationArtifactRecord requestRecord =
        artifacts
            .find(request.executionId(), ComputationArtifactKind.REQUEST)
            .orElseGet(
                () ->
                    artifacts.write(
                        request.executionId(), ComputationArtifactKind.REQUEST, spec));
    executions.reserve(
        runId,
        routeId,
        spec,
        request.capability(),
        spec.targetClaimId(),
        "",
        requestRecord.reference(),
        round);
    executions.markRunning(request.executionId(), round);
    ComputationResultArtifact raw =
        new ComputationResultArtifact(
            spec.requestHash(),
            spec.executionHash(),
            ExperimentOutcome.INCONCLUSIVE,
            EvidenceStrength.HEURISTIC,
            ComputationJson.object().put("legacy_audit_only", true),
            null,
            null,
            spec.exactArithmetic(),
            0,
            0.0d,
            request.capability().producerId(),
            request.capability().producerVersion(),
            "LEGACY_AUDIT_ONLY",
            null);
    ComputationCertificateEnvelope certificate =
        ComputationCertificateFactory.create(request, raw);
    ResultAndCertificate durable = persistProducerArtifacts(request, raw, certificate);
    executions.markResultDurable(
        request.executionId(),
        durable.resultRecord().reference(),
        durable.certificateRecord().reference(),
        0.0d,
        false,
        false,
        round);
    ComputationVerificationReceipt receipt =
        new ComputationVerificationReceipt(
            "legacy-audit-" + request.executionId(),
            certificate.certificateHash(),
            request.capability().verifierId(),
            request.capability().verifierVersion(),
            ComputationVerificationStatus.INVALID,
            false,
            ComputationVerifiedAuthority.AUDIT_ONLY,
            certificate.scopeHash(),
            List.of("LEGACY_AUDIT_ONLY"),
            "1970-01-01T00:00:00Z",
            null);
    ComputationArtifactRecord verificationRecord =
        artifacts.write(
            request.executionId(), ComputationArtifactKind.VERIFICATION_RECEIPT, receipt);
    verifications.record(receipt);
    executions.markVerificationDurable(
        request.executionId(),
        verificationRecord.reference(),
        ComputationVerifiedAuthority.AUDIT_ONLY,
        round);
    ComputationDecisionPlan plan = projector.plan(spec, context, receipt);
    ComputationOutcomeApplicationReceipt application =
        projector.project(request.executionId(), ExperimentOutcome.INCONCLUSIVE, plan, receipt);
    ComputationArtifactRecord applicationRecord =
        artifacts.write(
            request.executionId(),
            ComputationArtifactKind.OUTCOME_APPLICATION_RECEIPT,
            application);
    outcomeReceipts.record(application);
    executions.markAuthorityApplied(request.executionId(), applicationRecord.reference(), round);
  }

  public ComputationExecutionLedger executions() {
    return executions;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Callers need the durable artifact store facade, not a copied state projection.")
  public ComputationArtifactStore artifacts() {
    return artifacts;
  }

  public ComputationVerificationLedger verifications() {
    return verifications;
  }

  public ComputationOutcomeReceiptLedger outcomeReceipts() {
    return outcomeReceipts;
  }

  public void setHook(ComputationExecutionHook hook) {
    this.hook = hook == null ? ComputationExecutionHook.noOp() : hook;
  }

  public void setPersister(ComputationStatePersister persister) {
    this.persister = persister == null ? ComputationStatePersister.noOp() : persister;
  }

  private Optional<ComputationExecutionOutcome> completedOutcome(
      ExperimentSpec spec,
      ValidatedComputationRequest request,
      ComputationExecutionRecord record) {
    if (record.status() != ComputationExecutionStatus.AUTHORITY_APPLIED) {
      return Optional.empty();
    }
    ResultAndCertificate durable = readDurable(record);
    ComputationVerificationReceipt receipt =
        artifacts
            .read(record.verificationReceiptRef(), ComputationVerificationReceipt.class)
            .orElseThrow(() -> new IllegalStateException("durable verification receipt is missing"));
    ComputationOutcomeApplicationReceipt application =
        artifacts
            .read(
                record.outcomeApplicationReceiptRef(),
                ComputationOutcomeApplicationReceipt.class)
            .orElseThrow(() -> new IllegalStateException("durable outcome receipt is missing"));
    List<ComputationArtifactRecord> artifactRecords =
        List.of(
            artifacts.find(request.executionId(), ComputationArtifactKind.REQUEST).orElseThrow(),
            durable.resultRecord(),
            durable.certificateRecord(),
            artifacts.find(request.executionId(), ComputationArtifactKind.VERIFICATION_RECEIPT).orElseThrow(),
            artifacts.find(request.executionId(), ComputationArtifactKind.OUTCOME_APPLICATION_RECEIPT).orElseThrow());
    ExperimentResult result =
        publicResult(spec, request.program(), durable.result(), receipt, artifactRecords, true);
    ComputationArtifactBundle bundle =
        new ComputationArtifactBundle(
            artifactRecords.get(0),
            artifactRecords.get(1),
            artifactRecords.get(2),
            artifactRecords.get(3),
            artifactRecords.get(4));
    return Optional.of(
        new ComputationExecutionOutcome(
            request.executionId(),
            result,
            durable.certificate(),
            receipt,
            application,
            bundle,
            ComputationEvidenceGate.authority(result, receipt, request.capability()),
            true));
  }

  private Optional<ResultAndCertificate> recoverDurableProducerArtifacts(
      ValidatedComputationRequest request, ComputationExecutionRecord record) {
    if (record.status() == ComputationExecutionStatus.RESULT_DURABLE
        || record.status() == ComputationExecutionStatus.VERIFICATION_DURABLE) {
      return Optional.of(readDurable(record));
    }
    Optional<ComputationArtifactRecord> resultRecord =
        artifacts.find(request.executionId(), ComputationArtifactKind.RESULT);
    Optional<ComputationArtifactRecord> certificateRecord =
        artifacts.find(request.executionId(), ComputationArtifactKind.CERTIFICATE);
    if (record.status() == ComputationExecutionStatus.RUNNING
        && resultRecord.isPresent()
        && certificateRecord.isPresent()) {
      ComputationResultArtifact result =
          artifacts
              .read(resultRecord.orElseThrow().reference(), ComputationResultArtifact.class)
              .orElseThrow();
      ComputationCertificateEnvelope certificate =
          artifacts
              .read(certificateRecord.orElseThrow().reference(), ComputationCertificateEnvelope.class)
              .orElseThrow();
      return Optional.of(
          new ResultAndCertificate(
              result, certificate, resultRecord.orElseThrow(), certificateRecord.orElseThrow()));
    }
    return Optional.empty();
  }

  private ResultAndCertificate persistProducerArtifacts(
      ValidatedComputationRequest request,
      ComputationResultArtifact result,
      ComputationCertificateEnvelope certificate) {
    ComputationArtifactRecord resultRecord =
        artifacts.write(request.executionId(), ComputationArtifactKind.RESULT, result);
    ComputationArtifactRecord certificateRecord =
        artifacts.write(request.executionId(), ComputationArtifactKind.CERTIFICATE, certificate);
    return new ResultAndCertificate(result, certificate, resultRecord, certificateRecord);
  }

  private ComputationVerificationReceipt recoverVerification(
      ValidatedComputationRequest request,
      ResultAndCertificate durable,
      int round) {
    Optional<ComputationArtifactRecord> existing =
        artifacts.find(request.executionId(), ComputationArtifactKind.VERIFICATION_RECEIPT);
    if (existing.isPresent()) {
      return artifacts
          .read(existing.orElseThrow().reference(), ComputationVerificationReceipt.class)
          .orElseThrow();
    }
    RegisteredComputationCapability capability = capabilities.capability(request.spec().method());
    ComputationVerificationReceipt receipt =
        capability.verifier().verify(request, durable.result(), durable.certificate());
    ComputationArtifactRecord receiptRecord =
        artifacts.write(
            request.executionId(), ComputationArtifactKind.VERIFICATION_RECEIPT, receipt);
    verifications.record(receipt);
    executions.markVerificationDurable(
        request.executionId(), receiptRecord.reference(), receipt.authority(), round);
    persist("computation_verification_durable");
    return receipt;
  }

  private ResultAndCertificate readDurable(ComputationExecutionRecord record) {
    ComputationResultArtifact result =
        artifacts
            .read(record.resultArtifactRef(), ComputationResultArtifact.class)
            .orElseThrow(() -> new IllegalStateException("durable result artifact is missing"));
    ComputationCertificateEnvelope certificate =
        artifacts
            .read(record.certificateArtifactRef(), ComputationCertificateEnvelope.class)
            .orElseThrow(() -> new IllegalStateException("durable certificate artifact is missing"));
    ComputationArtifactRecord resultRecord =
        artifacts.find(record.executionId(), ComputationArtifactKind.RESULT).orElseThrow();
    ComputationArtifactRecord certificateRecord =
        artifacts.find(record.executionId(), ComputationArtifactKind.CERTIFICATE).orElseThrow();
    return new ResultAndCertificate(result, certificate, resultRecord, certificateRecord);
  }

  private static ComputationResultArtifact rawResult(
      ValidatedComputationRequest request,
      ProducedComputation produced,
      String error,
      double runtimeSeconds) {
    HandlerEvidence evidence = produced.evidence();
    return new ComputationResultArtifact(
        request.spec().requestHash(),
        request.spec().executionHash(),
        evidence.outcome(),
        evidence.evidenceStrength(),
        evidence.scope(),
        evidence.counterexample(),
        evidence.certificate(),
        evidence.exactArithmetic(),
        evidence.casesChecked(),
        runtimeSeconds,
        produced.producerId(),
        produced.producerVersion(),
        error,
        null);
  }

  private static ComputationResultArtifact rebind(
      ComputationResultArtifact cached, ValidatedComputationRequest request) {
    return new ComputationResultArtifact(
        request.spec().requestHash(),
        request.spec().executionHash(),
        cached.outcome(),
        cached.evidenceStrength(),
        cached.scope(),
        cached.counterexample(),
        cached.certificate(),
        cached.exactArithmetic(),
        cached.casesChecked(),
        cached.runtimeSeconds(),
        request.capability().producerId(),
        request.capability().producerVersion(),
        cached.error(),
        null);
  }

  private static ExperimentResult publicResult(
      ExperimentSpec spec,
      ExperimentProgram program,
      ComputationResultArtifact raw,
      ComputationVerificationReceipt receipt,
      List<ComputationArtifactRecord> artifacts,
      boolean cacheHit) {
    ExperimentOutcome outcome = raw.outcome();
    EvidenceStrength strength = raw.evidenceStrength();
    com.fasterxml.jackson.databind.node.ObjectNode counterexample = raw.counterexample();
    com.fasterxml.jackson.databind.node.ObjectNode certificate = raw.certificate();
    String error = raw.error().isBlank() ? null : raw.error();
    if (outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND && !receipt.valid()) {
      outcome = ExperimentOutcome.INCONCLUSIVE;
      strength = EvidenceStrength.HEURISTIC;
      counterexample = null;
      certificate = null;
      error = "UNVERIFIED_COUNTEREXAMPLE";
    }
    List<String> notes = new ArrayList<>(receipt.diagnostics());
    notes.add("verification_receipt=" + receipt.receiptHash());
    List<EvidenceRef> references =
        artifacts.stream()
            .map(
                artifact ->
                    new EvidenceRef(
                        artifact.reference(),
                        artifact.contentHash(),
                        "computation-" + artifact.kind().name().toLowerCase(java.util.Locale.ROOT),
                        "Immutable "
                            + artifact.kind().name().toLowerCase(java.util.Locale.ROOT)
                            + " artifact"))
            .toList();
    return new ExperimentResult(
        references,
        cacheHit,
        raw.casesChecked(),
        certificate,
        counterexample,
        null,
        error,
        strength,
        raw.exactArithmetic(),
        spec.experimentId(),
        receipt.valid(),
        spec.method(),
        outcome,
        spec.parentCheckpointId(),
        spec.pathId(),
        program == null ? null : program.codeHash(),
        spec.requestHash(),
        null,
        raw.runtimeSeconds(),
        raw.scope(),
        spec.targetClaim(),
        spec.targetClaimId(),
        spec.method().value(),
        raw.producerVersion(),
        notes,
        spec.claimEvidenceSemanticBinding());
  }

  private ComputationCacheKey cacheKey(ValidatedComputationRequest request) {
    return new ComputationCacheKey(
        runId,
        request.spec().executionHash(),
        request.capability().capabilityId(),
        request.capability().capabilityVersion(),
        request.capability().producerVersion(),
        request.capability().verifierVersion(),
        request.capability().inputSchemaHash(),
        CanonicalJson.stableHash(request.spec().runtimeFingerprint()));
  }

  private static ComputationVerifiedAuthority migratedAuthority(
      ComputationEvidenceGate.EvidenceAuthority authority) {
    if (authority == null) {
      return ComputationVerifiedAuthority.AUDIT_ONLY;
    }
    return switch (authority) {
      case REFUTED -> ComputationVerifiedAuthority.EXACT_COUNTEREXAMPLE;
      case VERIFIED_BOUNDED -> ComputationVerifiedAuthority.FINITE_DOMAIN_CERTIFICATE;
      case VERIFIED -> ComputationVerifiedAuthority.FORMAL_CERTIFICATE;
      case NOT_REFUTED -> ComputationVerifiedAuthority.BOUNDED_OBSERVATION;
      case INCONCLUSIVE -> ComputationVerifiedAuthority.AUDIT_ONLY;
    };
  }

  private void persist(String reason) {
    persister.persist(reason, snapshot());
  }

  private static String boundedMessage(RuntimeException exception) {
    String message =
        exception.getMessage() == null
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
    return message.length() <= 1_000 ? message : message.substring(0, 1_000);
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }

  private record ResultAndCertificate(
      ComputationResultArtifact result,
      ComputationCertificateEnvelope certificate,
      ComputationArtifactRecord resultRecord,
      ComputationArtifactRecord certificateRecord) {}
}
