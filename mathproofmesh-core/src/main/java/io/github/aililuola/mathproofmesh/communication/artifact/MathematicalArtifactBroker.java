package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.communication.MessageStoreSnapshot;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseManifest;
import io.github.aililuola.mathproofmesh.contract.BrokerClaimSemanticContext;
import io.github.aililuola.mathproofmesh.contract.BrokerPromptArtifact;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.VerifiedClaimPayload;
import io.github.aililuola.mathproofmesh.contract.VerifiedCounterexamplePayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Modern broker for server-compiled mathematical artifacts. */
public final class MathematicalArtifactBroker {
  private final BrokerArtifactRegistry registry = new BrokerArtifactRegistry();
  private final BrokerArtifactPublicationLedger publications = new BrokerArtifactPublicationLedger();
  private final BrokerArtifactTargetingService targeting = new BrokerArtifactTargetingService();
  private final BrokerArtifactPublicationService publicationService =
      new BrokerArtifactPublicationService(registry, publications, targeting);
  private final BrokerArtifactPromptProjectionService projection =
      new BrokerArtifactPromptProjectionService();
  private final BrokerArtifactUseLedger uses = new BrokerArtifactUseLedger();
  private final BrokerArtifactReceiptService receipts = new BrokerArtifactReceiptService();
  private final BrokerArtifactEffectVerifier effectVerifier = new BrokerArtifactEffectVerifier();
  private final BrokerArtifactUtilityLedger utilities = new BrokerArtifactUtilityLedger();
  private final BrokerArtifactInvalidationService invalidations =
      new BrokerArtifactInvalidationService();
  private final Map<String, BrokerArtifactDelivery> deliveries = new LinkedHashMap<>();
  private final Map<String, BrokerDeliveryBaseline> baselines = new LinkedHashMap<>();
  private final Map<String, List<String>> providerRequests = new LinkedHashMap<>();
  private long deliveryVersion;

  public synchronized BrokerArtifactPublishResult publish(
      BrokerArtifactEnvelope artifact,
      List<RouteMathematicalNeedProfile> profiles,
      int currentRound,
      int targetLimit) {
    BrokerArtifactRegistrySnapshot registryBefore = registry.snapshot();
    BrokerArtifactPublicationSnapshot publicationsBefore = publications.snapshot();
    Map<String, BrokerArtifactDelivery> deliveriesBefore = new LinkedHashMap<>(deliveries);
    long versionBefore = deliveryVersion;
    try {
      BrokerArtifactPublicationService.Publication result = publicationService.publish(
          artifact, profiles, currentRound, targetLimit);
      List<BrokerArtifactDelivery> created = new ArrayList<>();
      for (String routeId : result.publication().targetRouteIds()) {
        String deliveryId = "broker-delivery-" +
            io.github.aililuola.mathproofmesh.contract.CanonicalJson.stableHash(
                List.of(result.publication().publicationId(), routeId)).substring(0, 24);
        BrokerArtifactDelivery delivery = new BrokerArtifactDelivery(
            deliveryId, result.artifact().artifactId(), result.publication().publicationId(),
            routeId, currentRound, BrokerArtifactDeliveryState.QUEUED, null);
        BrokerArtifactDelivery existing = deliveries.putIfAbsent(deliveryId, delivery);
        created.add(existing == null ? delivery : existing);
        if (existing == null) deliveryVersion++;
      }
      return new BrokerArtifactPublishResult(result.artifact(), result.publication(), created,
          result.relevanceDecisions());
    } catch (RuntimeException exception) {
      registry.restore(registryBefore);
      publications.restore(publicationsBefore);
      deliveries.clear(); deliveries.putAll(deliveriesBefore); deliveryVersion = versionBefore;
      throw exception;
    }
  }

  public synchronized BrokerArtifactPromptBatch consumeForPrompt(
      String routeId,
      String providerRequestId,
      int currentRound,
      int limit,
      double proofDebtBefore,
      Set<String> openCanonicalTargets,
      Set<String> verifiedClaimIds,
      Set<String> refutedClaimIds,
      String strategyEpochId,
      String focusCanonicalTargetId) {
    if (providerRequests.containsKey(providerRequestId)) {
      return new BrokerArtifactPromptBatch(providerRequestId, routeId, List.of(), List.of(), true,
          projection.instruction());
    }
    List<BrokerArtifactDelivery> selected = deliveries.values().stream()
        .filter(delivery -> delivery.targetRouteId().equals(routeId))
        .filter(delivery -> delivery.state() == BrokerArtifactDeliveryState.QUEUED)
        .filter(delivery -> registry.active(delivery.artifactId()))
        .filter(delivery -> registry.find(delivery.artifactId())
            .map(artifact -> currentRound - delivery.deliveredRound() <= artifact.ttlRounds())
            .orElse(false))
        .sorted(Comparator.comparing(BrokerArtifactDelivery::deliveryId))
        .limit(Math.min(BrokerArtifactPromptProjectionService.MAX_ARTIFACTS, Math.max(0, limit)))
        .toList();
    List<BrokerArtifactDelivery> consumed = new ArrayList<>();
    List<BrokerArtifactEnvelope> artifacts = new ArrayList<>();
    for (BrokerArtifactDelivery delivery : selected) {
      BrokerArtifactDelivery updated = delivery.consume(providerRequestId);
      deliveries.put(delivery.deliveryId(), updated);
      baselines.putIfAbsent(delivery.deliveryId(), new BrokerDeliveryBaseline(
          delivery.deliveryId(), routeId, providerRequestId, currentRound, proofDebtBefore,
          openCanonicalTargets, verifiedClaimIds, refutedClaimIds, strategyEpochId,
          focusCanonicalTargetId));
      consumed.add(updated);
      artifacts.add(registry.find(delivery.artifactId()).orElseThrow());
      deliveryVersion++;
    }
    providerRequests.put(providerRequestId,
        consumed.stream().map(BrokerArtifactDelivery::deliveryId).toList());
    deliveryVersion++;
    List<BrokerPromptArtifact> prompt = projection.project(artifacts);
    return new BrokerArtifactPromptBatch(providerRequestId, routeId, prompt, consumed, false,
        projection.instruction());
  }

  public synchronized List<BrokerArtifactReceipt> acknowledge(
      String providerRequestId,
      BrokerArtifactUseManifest manifest,
      Set<String> actualProofStepIds) {
    BrokerArtifactReceiptSnapshot receiptBefore = receipts.snapshot();
    BrokerArtifactUseSnapshot useBefore = uses.snapshot();
    Map<String, BrokerArtifactDelivery> deliveriesBefore = new LinkedHashMap<>(deliveries);
    long deliveryVersionBefore = deliveryVersion;
    List<BrokerArtifactDelivery> requestDeliveries = providerRequests
        .getOrDefault(providerRequestId, List.of()).stream().map(deliveries::get).toList();
    Map<String, BrokerArtifactEnvelope> artifacts = registry.snapshot().artifacts();
    try {
      List<BrokerArtifactReceipt> recorded = receipts.record(providerRequestId, requestDeliveries,
          artifacts, manifest, actualProofStepIds, uses);
      recorded.forEach(receipt -> deliveries.computeIfPresent(receipt.deliveryId(),
          (key, delivery) -> delivery.transition(BrokerArtifactDeliveryState.RECEIPTED)));
      deliveryVersion += recorded.size();
      return recorded;
    } catch (RuntimeException exception) {
      receipts.restore(receiptBefore);
      uses.restore(useBefore);
      deliveries.clear();
      deliveries.putAll(deliveriesBefore);
      deliveryVersion = deliveryVersionBefore;
      throw exception;
    }
  }

  public synchronized void stageUseManifest(BrokerArtifactUseManifest manifest) {
    uses.recordManifest(java.util.Objects.requireNonNull(manifest, "manifest"));
  }

  public synchronized List<BrokerArtifactReceipt> acknowledge(
      String providerRequestId, Set<String> actualProofStepIds) {
    return acknowledge(providerRequestId, uses.manifest(providerRequestId).orElse(null),
        actualProofStepIds);
  }

  public synchronized List<String> pendingProviderRequestsForRoute(String routeId) {
    return providerRequests.entrySet().stream()
        .filter(entry -> entry.getValue().stream()
            .map(deliveries::get)
            .filter(java.util.Objects::nonNull)
            .anyMatch(delivery -> delivery.targetRouteId().equals(routeId)
                && delivery.state() == BrokerArtifactDeliveryState.PROMPT_CONSUMED))
        .map(Map.Entry::getKey)
        .toList();
  }

  public synchronized Optional<BrokerArtifactUtilityRecord> verifyEffect(
      String deliveryId, BrokerArtifactEffectObservation observation) {
    BrokerArtifactLineageRecord lineage = uses.forDelivery(deliveryId).orElse(null);
    BrokerDeliveryBaseline baseline = baselines.get(deliveryId);
    if (lineage == null || baseline == null) return Optional.empty();
    BrokerArtifactEffectVerifier.Verification verified =
        effectVerifier.verify(lineage, baseline, observation);
    if (!verified.verified()) return Optional.empty();
    uses.markVerified(lineage.lineageId());
    receipts.transition(deliveryId, BrokerArtifactReceiptStatus.USED_EFFECT_VERIFIED,
        "DOWNSTREAM_EFFECT_VERIFIED");
    return Optional.of(utilities.record(lineage, baseline, observation, verified));
  }

  public synchronized BrokerArtifactInvalidationRecord invalidate(
      String artifactId, String sourceAuthorityId, String reason, int round) {
    registry.invalidate(artifactId);
    List<String> affectedDeliveries = deliveries.values().stream()
        .filter(delivery -> delivery.artifactId().equals(artifactId))
        .map(BrokerArtifactDelivery::deliveryId).toList();
    List<String> lineageIds = uses.records().stream()
        .filter(lineage -> lineage.artifactId().equals(artifactId))
        .map(BrokerArtifactLineageRecord::lineageId).toList();
    affectedDeliveries.forEach(id -> {
      deliveries.computeIfPresent(id, (key, delivery) ->
          delivery.transition(BrokerArtifactDeliveryState.INVALIDATED));
      receipts.transition(id, BrokerArtifactReceiptStatus.INVALIDATED, "SOURCE_AUTHORITY_INVALIDATED");
    });
    utilities.invalidateArtifact(artifactId);
    return invalidations.invalidate(artifactId, sourceAuthorityId, reason, round,
        affectedDeliveries, lineageIds);
  }

  public synchronized int expire(int currentRound) {
    int expired = 0;
    for (BrokerArtifactDelivery delivery : List.copyOf(deliveries.values())) {
      BrokerArtifactEnvelope artifact = registry.find(delivery.artifactId()).orElse(null);
      if (artifact != null && delivery.state() == BrokerArtifactDeliveryState.QUEUED
          && currentRound - delivery.deliveredRound() > artifact.ttlRounds()) {
        deliveries.put(delivery.deliveryId(), delivery.transition(BrokerArtifactDeliveryState.EXPIRED));
        receipts.transition(delivery.deliveryId(), BrokerArtifactReceiptStatus.EXPIRED,
            "DELIVERY_EXPIRED");
        expired++;
      }
    }
    deliveryVersion += expired;
    return expired;
  }

  public synchronized int migrateLegacy(
      MessageStoreSnapshot legacy,
      String problemHash,
      String rootGoalHash,
      List<RouteMathematicalNeedProfile> profiles,
      int currentRound) {
    if (legacy == null) return 0;
    BrokerArtifactCompiler compiler = new BrokerArtifactCompiler();
    int migrated = 0;
    for (MessageEnvelope message : legacy.messages().values()) {
      BrokerArtifactCompilationRequest request = legacyRequest(message, problemHash, rootGoalHash);
      if (request == null) continue;
      BrokerArtifactCompilationResult result = compiler.compile(request);
      if (result.accepted()) {
        publish(result.artifact(), profiles, currentRound, 8);
        migrated++;
      }
    }
    return migrated;
  }

  private static BrokerArtifactCompilationRequest legacyRequest(
      MessageEnvelope message, String problemHash, String rootGoalHash) {
    if (!problemHash.equals(message.problemHash())
        || message.claimSemanticHash() == null
        || message.claimStatementHash() == null
        || message.polarity() == null) return null;
    BrokerClaimSemanticContext context = new BrokerClaimSemanticContext(
        message.statement(), message.conclusion(), message.assumptions(), message.quantifiers(),
        message.variableBindings(), message.scopeLimitations(), message.polarity(),
        message.claimStatementHash(), message.claimSemanticHash(), message.dependencies());
    if (message.messageType() == MessageType.VERIFIED_LEMMA
        && message.memoryTier() == MemoryTier.FACT
        && message.verificationStatus() == ClaimStatus.VERIFIED) {
      return request(message, problemHash, rootGoalHash,
          io.github.aililuola.mathproofmesh.contract.BrokerArtifactType.VERIFIED_CLAIM,
          new VerifiedClaimPayload(context), BrokerArtifactSourceKind.CLAIM_COURT_VERIFIED);
    }
    if (message.evidenceType() == EvidenceType.COUNTEREXAMPLE
        && message.verificationStatus() == ClaimStatus.REJECTED
        && message.rawSourceRef() != null
        && message.rawSourceRef().startsWith("experiment://")) {
      return request(message, problemHash, rootGoalHash,
          io.github.aililuola.mathproofmesh.contract.BrokerArtifactType.VERIFIED_COUNTEREXAMPLE,
          new VerifiedCounterexamplePayload(context, message.messageId(),
              context.claimSemanticHash(), message.statement(), message.artifactRefs(), List.of()),
          BrokerArtifactSourceKind.VERIFIED_COUNTEREXAMPLE);
    }
    return null;
  }

  private static BrokerArtifactCompilationRequest request(
      MessageEnvelope message,
      String problemHash,
      String rootGoalHash,
      io.github.aililuola.mathproofmesh.contract.BrokerArtifactType type,
      io.github.aililuola.mathproofmesh.contract.BrokerArtifactPayload payload,
      BrokerArtifactSourceKind sourceKind) {
    return new BrokerArtifactCompilationRequest(problemHash, rootGoalHash, type, payload, sourceKind,
        message.sourceRouteId(), null, message.messageId(), message.rawSourceRef(), List.of(),
        List.of(), message.artifactRefs(), List.of(), List.of(), List.of(), null,
        message.roundCreated(), message.ttlRounds(), true, true);
  }

  public synchronized BrokerArtifactRegistrySnapshot registrySnapshot() { return registry.snapshot(); }
  public synchronized BrokerArtifactPublicationSnapshot publicationSnapshot() { return publications.snapshot(); }
  public synchronized BrokerArtifactDeliverySnapshot deliverySnapshot() {
    return new BrokerArtifactDeliverySnapshot(deliveries, baselines, providerRequests, deliveryVersion);
  }
  public synchronized BrokerArtifactReceiptSnapshot receiptSnapshot() { return receipts.snapshot(); }
  public synchronized BrokerArtifactUseSnapshot useSnapshot() { return uses.snapshot(); }
  public synchronized BrokerArtifactUtilitySnapshot utilitySnapshot() { return utilities.snapshot(); }
  public synchronized BrokerArtifactInvalidationSnapshot invalidationSnapshot() { return invalidations.snapshot(); }

  public synchronized void restore(
      BrokerArtifactRegistrySnapshot registrySnapshot,
      BrokerArtifactPublicationSnapshot publicationSnapshot,
      BrokerArtifactDeliverySnapshot deliverySnapshot,
      BrokerArtifactReceiptSnapshot receiptSnapshot,
      BrokerArtifactUseSnapshot useSnapshot,
      BrokerArtifactUtilitySnapshot utilitySnapshot,
      BrokerArtifactInvalidationSnapshot invalidationSnapshot) {
    registry.restore(registrySnapshot);
    publications.restore(publicationSnapshot);
    BrokerArtifactDeliverySnapshot deliverySafe = deliverySnapshot == null
        ? BrokerArtifactDeliverySnapshot.empty() : deliverySnapshot;
    deliveries.clear(); deliveries.putAll(deliverySafe.deliveries());
    baselines.clear(); baselines.putAll(deliverySafe.baselines());
    providerRequests.clear(); providerRequests.putAll(deliverySafe.providerRequests());
    deliveryVersion = deliverySafe.version();
    receipts.restore(receiptSnapshot);
    uses.restore(useSnapshot);
    utilities.restore(utilitySnapshot);
    invalidations.restore(invalidationSnapshot);
  }

  public synchronized List<BrokerArtifactEnvelope> artifacts() {
    return List.copyOf(registry.snapshot().artifacts().values());
  }
  public synchronized List<BrokerArtifactDelivery> deliveries() { return List.copyOf(deliveries.values()); }
  public synchronized List<BrokerArtifactReceipt> receipts() { return receipts.records(); }
  public synchronized List<BrokerArtifactLineageRecord> lineage() { return uses.records(); }
  public synchronized List<BrokerArtifactUtilityRecord> utilities() { return utilities.records(); }
  public synchronized List<BrokerArtifactInvalidationRecord> invalidations() { return invalidations.records(); }
}
