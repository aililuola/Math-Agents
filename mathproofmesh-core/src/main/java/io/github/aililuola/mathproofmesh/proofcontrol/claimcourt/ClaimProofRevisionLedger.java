package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatch;
import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ClaimProofRevisionLedger {
  private final Map<String, ClaimProofRevisionRecord> records = new LinkedHashMap<>();
  private final List<ClaimProofRevisionAuditEvent> audit = new ArrayList<>();

  public synchronized ClaimProofRevisionRecord createOriginal(
      FrozenClaimSnapshot frozen,
      List<ProofStep> proofSteps,
      List<EvidenceRef> evidenceRefs) {
    java.util.Objects.requireNonNull(frozen, "frozen");
    String proofHash = CanonicalJson.stableHash(ClaimCourtValues.copy(proofSteps));
    ClaimProofRevisionRecord candidate =
        new ClaimProofRevisionRecord(
            frozen.initialProofRevisionId(),
            frozen.claimId(),
            frozen.claimSemanticHash(),
            null,
            proofSteps,
            frozen.dependencyClaimIds(),
            evidenceRefs,
            proofHash,
            frozen.authorAgentId(),
            null,
            null,
            ClaimProofRevisionStatus.ORIGINAL,
            0L,
            List.of("original proof frozen"));
    ClaimProofRevisionRecord existing = records.putIfAbsent(candidate.revisionId(), candidate);
    if (existing != null && !sameIdentity(existing, candidate)) {
      throw new IllegalStateException("proof revision identity collision");
    }
    if (existing == null) {
      audit.add(
          new ClaimProofRevisionAuditEvent(
              audit.size(),
              candidate.revisionId(),
              null,
              candidate.status(),
              "original proof frozen",
              candidate.version()));
    }
    return existing == null ? candidate : existing;
  }

  public synchronized ClaimProofRevisionRecord createRepaired(
      FrozenClaimSnapshot frozen,
      ClaimProofRevisionRecord base,
      ClaimProofPatch patch,
      List<ProofStep> proofSteps,
      String repairerAgentId) {
    return createRepaired(
        frozen, base, patch, proofSteps, base.evidenceRefs(), repairerAgentId);
  }

  public synchronized ClaimProofRevisionRecord createRepaired(
      FrozenClaimSnapshot frozen,
      ClaimProofRevisionRecord base,
      ClaimProofPatch patch,
      List<ProofStep> proofSteps,
      List<EvidenceRef> evidenceRefs,
      String repairerAgentId) {
    requireFrozenIdentity(frozen, base, patch);
    String repairer = ClaimCourtValues.required(repairerAgentId, "repairerAgentId");
    if (repairer.equals(frozen.authorAgentId())) {
      throw new IllegalArgumentException("repairer must differ from claim author");
    }
    String proofHash = CanonicalJson.stableHash(ClaimCourtValues.copy(proofSteps));
    String revisionId =
        "claim-proof-revision-"
            + CanonicalJson.stableHash(
                    List.of(
                        frozen.claimId(),
                        base.revisionId(),
                        patch.patchId(),
                        proofHash))
                .substring(0, 24);
    ClaimProofRevisionRecord candidate =
        new ClaimProofRevisionRecord(
            revisionId,
            frozen.claimId(),
            frozen.claimSemanticHash(),
            base.revisionId(),
            proofSteps,
            base.dependencyClaimIds(),
            evidenceRefs,
            proofHash,
            frozen.authorAgentId(),
            repairer,
            patch.patchId(),
            ClaimProofRevisionStatus.REPAIRED_PENDING_ADJUDICATION,
            0L,
            List.of("deterministic proof patch validated", "awaiting blind adjudication"));
    ClaimProofRevisionRecord existing = records.putIfAbsent(revisionId, candidate);
    if (existing != null && !sameIdentity(existing, candidate)) {
      throw new IllegalStateException("proof revision identity collision");
    }
    if (existing == null) {
      audit.add(
          new ClaimProofRevisionAuditEvent(
              audit.size(),
              revisionId,
              null,
              candidate.status(),
              "repaired proof revision created",
              candidate.version()));
    }
    return existing == null ? candidate : existing;
  }

  public synchronized ClaimProofRevisionRecord markBlindVerified(String revisionId) {
    return transition(
        required(revisionId), ClaimProofRevisionStatus.BLIND_VERIFIED, "blind adjudication passed");
  }

  public synchronized ClaimProofRevisionRecord markBlindRejected(String revisionId) {
    return transition(
        required(revisionId), ClaimProofRevisionStatus.BLIND_REJECTED, "blind proof review failed");
  }

  public synchronized ClaimProofRevisionRecord get(String revisionId) {
    return required(revisionId);
  }

  public synchronized List<ClaimProofRevisionRecord> records() {
    return records.values().stream()
        .sorted(Comparator.comparing(ClaimProofRevisionRecord::revisionId))
        .toList();
  }

  public synchronized List<ClaimProofRevisionRecord> recordsForClaim(String claimId) {
    return records.values().stream()
        .filter(record -> record.claimId().equals(claimId))
        .sorted(Comparator.comparing(ClaimProofRevisionRecord::revisionId))
        .toList();
  }

  public synchronized ClaimProofRevisionSnapshot snapshot() {
    return new ClaimProofRevisionSnapshot(
        ClaimProofRevisionSnapshot.CURRENT_SCHEMA_VERSION, records, audit);
  }

  public synchronized String stableHash() {
    return CanonicalJson.stableHash(snapshot());
  }

  public synchronized void restore(ClaimProofRevisionSnapshot snapshot) {
    ClaimProofRevisionSnapshot source =
        snapshot == null ? ClaimProofRevisionSnapshot.empty() : snapshot;
    source.records().forEach(
        (id, record) -> {
          if (!id.equals(record.revisionId())) {
            throw new IllegalArgumentException("proof revision snapshot key mismatch");
          }
        });
    records.clear();
    records.putAll(source.records());
    audit.clear();
    audit.addAll(source.audit());
  }

  private ClaimProofRevisionRecord transition(
      ClaimProofRevisionRecord current, ClaimProofRevisionStatus next, String detail) {
    if (current.status() == next) {
      return current;
    }
    if ((current.status() != ClaimProofRevisionStatus.REPAIRED_PENDING_ADJUDICATION
            && current.status() != ClaimProofRevisionStatus.ORIGINAL)
        || (next != ClaimProofRevisionStatus.BLIND_VERIFIED
            && next != ClaimProofRevisionStatus.BLIND_REJECTED)) {
      throw new IllegalStateException("invalid proof revision transition");
    }
    List<String> history = new ArrayList<>(current.history());
    history.add(detail);
    ClaimProofRevisionRecord updated =
        new ClaimProofRevisionRecord(
            current.revisionId(),
            current.claimId(),
            current.claimSemanticHash(),
            current.baseRevisionId(),
            current.proofSteps(),
            current.dependencyClaimIds(),
            current.evidenceRefs(),
            current.proofHash(),
            current.authorAgentId(),
            current.repairerAgentId(),
            current.repairPatchId(),
            next,
            current.version() + 1L,
            history);
    records.put(updated.revisionId(), updated);
    audit.add(
        new ClaimProofRevisionAuditEvent(
            audit.size(),
            updated.revisionId(),
            current.status(),
            next,
            detail,
            updated.version()));
    return updated;
  }

  private ClaimProofRevisionRecord required(String revisionId) {
    ClaimProofRevisionRecord record = records.get(ClaimCourtValues.required(revisionId, "revisionId"));
    if (record == null) {
      throw new IllegalArgumentException("unknown proof revision: " + revisionId);
    }
    return record;
  }

  private static void requireFrozenIdentity(
      FrozenClaimSnapshot frozen, ClaimProofRevisionRecord base, ClaimProofPatch patch) {
    if (!frozen.claimId().equals(base.claimId())
        || !frozen.claimId().equals(patch.claimId())
        || !frozen.claimSemanticHash().equals(base.claimSemanticHash())
        || !frozen.claimSemanticHash().equals(patch.claimSemanticHash())
        || !base.revisionId().equals(patch.baseProofRevisionId())
        || !base.proofHash().equals(patch.baseProofHash())) {
      throw new IllegalArgumentException("FROZEN_CLAIM_MUTATION");
    }
  }

  private static boolean sameIdentity(
      ClaimProofRevisionRecord left, ClaimProofRevisionRecord right) {
    return left.claimId().equals(right.claimId())
        && left.claimSemanticHash().equals(right.claimSemanticHash())
        && left.proofHash().equals(right.proofHash())
        && java.util.Objects.equals(left.baseRevisionId(), right.baseRevisionId())
        && java.util.Objects.equals(left.repairPatchId(), right.repairPatchId());
  }
}
