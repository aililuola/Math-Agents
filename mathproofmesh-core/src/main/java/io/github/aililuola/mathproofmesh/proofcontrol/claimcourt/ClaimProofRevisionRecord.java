package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import java.util.List;

public record ClaimProofRevisionRecord(
    String revisionId,
    String claimId,
    String claimSemanticHash,
    String baseRevisionId,
    List<ProofStep> proofSteps,
    List<String> dependencyClaimIds,
    List<EvidenceRef> evidenceRefs,
    String proofHash,
    String authorAgentId,
    String repairerAgentId,
    String repairPatchId,
    ClaimProofRevisionStatus status,
    long version,
    List<String> history) {
  public ClaimProofRevisionRecord {
    revisionId = ClaimCourtValues.required(revisionId, "revisionId");
    claimId = ClaimCourtValues.required(claimId, "claimId");
    claimSemanticHash = ClaimCourtValues.required(claimSemanticHash, "claimSemanticHash");
    baseRevisionId = ClaimCourtValues.nullable(baseRevisionId);
    proofSteps = ClaimCourtValues.copy(proofSteps);
    dependencyClaimIds = ClaimCourtValues.copy(dependencyClaimIds);
    evidenceRefs = ClaimCourtValues.copy(evidenceRefs);
    proofHash = ClaimCourtValues.required(proofHash, "proofHash");
    authorAgentId = ClaimCourtValues.required(authorAgentId, "authorAgentId");
    repairerAgentId = ClaimCourtValues.nullable(repairerAgentId);
    repairPatchId = ClaimCourtValues.nullable(repairPatchId);
    status = java.util.Objects.requireNonNull(status, "status");
    if (version < 0L) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
    history = ClaimCourtValues.copy(history);
    boolean repaired = repairPatchId != null || repairerAgentId != null || baseRevisionId != null;
    if (repaired && (baseRevisionId == null || repairerAgentId == null || repairPatchId == null)) {
      throw new IllegalArgumentException("patched proof revision requires base and repair provenance");
    }
    if (!repaired
        && status != ClaimProofRevisionStatus.ORIGINAL
        && status != ClaimProofRevisionStatus.BLIND_VERIFIED
        && status != ClaimProofRevisionStatus.BLIND_REJECTED) {
      throw new IllegalArgumentException("original proof has invalid revision status");
    }
  }

  @Override
  public List<ProofStep> proofSteps() {
    return List.copyOf(proofSteps);
  }

  @Override
  public List<String> dependencyClaimIds() {
    return List.copyOf(dependencyClaimIds);
  }

  @Override
  public List<EvidenceRef> evidenceRefs() {
    return List.copyOf(evidenceRefs);
  }

  @Override
  public List<String> history() {
    return List.copyOf(history);
  }
}
