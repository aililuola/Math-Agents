package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseClaim;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseManifest;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BrokerArtifactReceiptService {
  private final Map<String, BrokerArtifactReceipt> receipts = new LinkedHashMap<>();
  private long version;

  public synchronized List<BrokerArtifactReceipt> record(
      String providerRequestId,
      List<BrokerArtifactDelivery> deliveries,
      Map<String, BrokerArtifactEnvelope> artifacts,
      BrokerArtifactUseManifest manifest,
      Set<String> actualProofStepIds,
      BrokerArtifactUseLedger useLedger) {
    return record(
        providerRequestId,
        deliveries,
        artifacts,
        manifest,
        actualProofStepIds,
        useLedger,
        () -> {},
        () -> {});
  }

  synchronized List<BrokerArtifactReceipt> record(
      String providerRequestId,
      List<BrokerArtifactDelivery> deliveries,
      Map<String, BrokerArtifactEnvelope> artifacts,
      BrokerArtifactUseManifest manifest,
      Set<String> actualProofStepIds,
      BrokerArtifactUseLedger useLedger,
      Runnable afterReceiptWrite,
      Runnable afterLineageWrite) {
    java.util.Objects.requireNonNull(providerRequestId, "providerRequestId");
    java.util.Objects.requireNonNull(deliveries, "deliveries");
    java.util.Objects.requireNonNull(artifacts, "artifacts");
    java.util.Objects.requireNonNull(actualProofStepIds, "actualProofStepIds");
    java.util.Objects.requireNonNull(useLedger, "useLedger");
    java.util.Objects.requireNonNull(afterReceiptWrite, "afterReceiptWrite");
    java.util.Objects.requireNonNull(afterLineageWrite, "afterLineageWrite");
    Map<String, BrokerArtifactUseClaim> uses = new LinkedHashMap<>();
    if (manifest != null) {
      if (!providerRequestId.equals(manifest.providerRequestId())) {
        throw new IllegalArgumentException("ARTIFACT_USE_MANIFEST_PROVIDER_REQUEST_MISMATCH");
      }
      manifest.uses().forEach(use -> uses.put(use.artifactId(), use));
    }
    Set<String> deliveredIds = deliveries.stream().map(BrokerArtifactDelivery::artifactId)
        .collect(java.util.stream.Collectors.toSet());
    if (manifest != null
        && manifest.uses().stream().anyMatch(use -> !deliveredIds.contains(use.artifactId()))) {
      throw new IllegalArgumentException("ARTIFACT_NOT_DELIVERED_TO_REQUEST");
    }
    if (manifest != null) {
      useLedger.recordManifest(manifest);
    }
    List<BrokerArtifactReceipt> result = new ArrayList<>();
    for (BrokerArtifactDelivery delivery : deliveries) {
      BrokerArtifactReceipt existing = receipts.get(delivery.deliveryId());
      if (existing != null) { result.add(existing); continue; }
      BrokerArtifactUseClaim use = uses.get(delivery.artifactId());
      BrokerArtifactReceipt receipt;
      if (use == null) {
        receipt = create(delivery, null, BrokerArtifactReceiptStatus.NOT_USED, "NO_EXPLICIT_USE");
      } else {
        String invalid = validateUse(artifacts.get(delivery.artifactId()), use, deliveredIds,
            actualProofStepIds);
        if (invalid != null) {
          receipt = create(delivery, use, BrokerArtifactReceiptStatus.REJECTED_INVALID_USE, invalid);
        } else {
          receipt = create(delivery, use, BrokerArtifactReceiptStatus.USED_PENDING_EFFECT,
              "EXPLICIT_USE_VALIDATED");
          receipts.put(delivery.deliveryId(), receipt);
          version++;
          afterReceiptWrite.run();
          useLedger.recordLineage(delivery.deliveryId(), use, providerRequestId);
          afterLineageWrite.run();
        }
      }
      if (!receipts.containsKey(delivery.deliveryId())) {
        receipts.put(delivery.deliveryId(), receipt);
        version++;
      }
      result.add(receipt);
    }
    return List.copyOf(result);
  }

  public synchronized void transition(String deliveryId, BrokerArtifactReceiptStatus status,
      String code) {
    BrokerArtifactReceipt receipt = receipts.get(deliveryId);
    if (receipt != null && receipt.status() != status) {
      receipts.put(deliveryId, receipt.transition(status, code));
      version++;
    }
  }

  public synchronized List<BrokerArtifactReceipt> records() {
    return List.copyOf(receipts.values());
  }

  public synchronized BrokerArtifactReceiptSnapshot snapshot() {
    return new BrokerArtifactReceiptSnapshot(receipts, version);
  }

  public synchronized void restore(BrokerArtifactReceiptSnapshot snapshot) {
    BrokerArtifactReceiptSnapshot safe = snapshot == null ? BrokerArtifactReceiptSnapshot.empty() : snapshot;
    receipts.clear(); receipts.putAll(safe.receipts()); version = safe.version();
  }

  private static BrokerArtifactReceipt create(BrokerArtifactDelivery delivery,
      BrokerArtifactUseClaim use, BrokerArtifactReceiptStatus status, String code) {
    String id = "broker-receipt-" + CanonicalJson.stableHash(delivery.deliveryId()).substring(0, 24);
    return new BrokerArtifactReceipt(id, delivery.deliveryId(), delivery.artifactId(),
        delivery.targetRouteId(), delivery.providerRequestId(), status,
        use == null ? null : use.useKind(), use == null ? List.of() : use.referencedProofStepIds(),
        use == null ? List.of() : use.affectedClaimIds(),
        use == null ? List.of() : use.affectedObligationIds(), code, 0L);
  }

  private static String validateUse(BrokerArtifactEnvelope artifact, BrokerArtifactUseClaim use,
      Set<String> deliveredIds, Set<String> actualProofStepIds) {
    if (!deliveredIds.contains(use.artifactId())) return "ARTIFACT_NOT_DELIVERED_TO_REQUEST";
    if (artifact == null) return "UNKNOWN_ARTIFACT";
    if (!actualProofStepIds.containsAll(use.referencedProofStepIds())) return "UNKNOWN_REFERENCED_STEP";
    if (!BrokerArtifactPromptProjectionService.allowedUses(artifact.artifactType()).contains(use.useKind())) {
      return switch (artifact.artifactType()) {
        case REVIEWED_OBSTRUCTION -> "REVIEWED_OBSTRUCTION_AS_PROVED_PREMISE";
        case BOUNDED_OBSERVATION, EXACT_EXAMPLE -> "BOUNDED_EVIDENCE_SCOPE_ESCALATION";
        case VERIFIED_COUNTEREXAMPLE -> "COUNTEREXAMPLE_WITHOUT_EXACT_TARGET";
        default -> "INCOMPATIBLE_ARTIFACT_USE";
      };
    }
    return null;
  }
}
