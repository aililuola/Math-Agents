package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtAuditEvent;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtRecord;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtSnapshot;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtStageExecutionRecord;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtStageExecutionSnapshot;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimProofRevisionAuditEvent;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimProofRevisionRecord;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimProofRevisionSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates and combines isolated Claim Court worker frontiers before authority mutation. */
final class ClaimCourtWorkerFrontierMerger {
  private ClaimCourtWorkerFrontierMerger() {}

  static MergedFrontier merge(
      ClaimCourtSnapshot currentCourt,
      ClaimProofRevisionSnapshot currentRevisions,
      ClaimCourtStageExecutionSnapshot currentExecutions,
      ClaimCourtSnapshot candidateCourt,
      ClaimProofRevisionSnapshot candidateRevisions,
      ClaimCourtStageExecutionSnapshot candidateExecutions) {
    return new MergedFrontier(
        mergeCourt(currentCourt, candidateCourt),
        mergeRevisions(currentRevisions, candidateRevisions),
        mergeExecutions(currentExecutions, candidateExecutions));
  }

  private static ClaimCourtSnapshot mergeCourt(
      ClaimCourtSnapshot current, ClaimCourtSnapshot candidate) {
    Map<String, ClaimCourtRecord> records = new LinkedHashMap<>(current.records());
    Set<String> changed = new LinkedHashSet<>();
    candidate.records().forEach(
        (id, next) -> {
          ClaimCourtRecord prior = records.get(id);
          if (prior != null && !sameCourtIdentity(prior, next)) {
            throw new IllegalStateException("claim court worker produced a conflicting case");
          }
          if (prior == null || prior.version() < next.version()) {
            records.put(id, next);
            changed.add(id);
          } else if (prior.version() == next.version() && !prior.equals(next)) {
            throw new IllegalStateException("claim court worker produced a conflicting case");
          }
        });
    List<ClaimCourtAuditEvent> audit = new ArrayList<>(current.audit());
    Set<String> auditHashes =
        audit.stream().map(CanonicalJson::stableHash).collect(Collectors.toSet());
    candidate.audit().stream()
        .filter(event -> changed.contains(event.courtCaseId()))
        .filter(event -> !auditHashes.contains(CanonicalJson.stableHash(event)))
        .forEach(
            event ->
                audit.add(
                    new ClaimCourtAuditEvent(
                        audit.size(),
                        event.courtCaseId(),
                        event.fromStatus(),
                        event.toStatus(),
                        event.detail(),
                        event.version())));
    return new ClaimCourtSnapshot(ClaimCourtSnapshot.CURRENT_SCHEMA_VERSION, records, audit);
  }

  private static ClaimProofRevisionSnapshot mergeRevisions(
      ClaimProofRevisionSnapshot current, ClaimProofRevisionSnapshot candidate) {
    Map<String, ClaimProofRevisionRecord> records = new LinkedHashMap<>(current.records());
    Set<String> changed = new LinkedHashSet<>();
    candidate.records().forEach(
        (id, next) -> {
          ClaimProofRevisionRecord prior = records.get(id);
          if (prior != null && !sameRevisionIdentity(prior, next)) {
            throw new IllegalStateException("Claim Court worker produced a conflicting revision");
          }
          if (prior == null || prior.version() < next.version()) {
            records.put(id, next);
            changed.add(id);
          } else if (prior.version() == next.version() && !prior.equals(next)) {
            throw new IllegalStateException("Claim Court worker produced a conflicting revision");
          }
        });
    List<ClaimProofRevisionAuditEvent> audit = new ArrayList<>(current.audit());
    Set<String> auditHashes =
        audit.stream().map(CanonicalJson::stableHash).collect(Collectors.toSet());
    candidate.audit().stream()
        .filter(event -> changed.contains(event.revisionId()))
        .filter(event -> !auditHashes.contains(CanonicalJson.stableHash(event)))
        .forEach(
            event ->
                audit.add(
                    new ClaimProofRevisionAuditEvent(
                        audit.size(),
                        event.revisionId(),
                        event.fromStatus(),
                        event.toStatus(),
                        event.detail(),
                        event.version())));
    return new ClaimProofRevisionSnapshot(
        ClaimProofRevisionSnapshot.CURRENT_SCHEMA_VERSION, records, audit);
  }

  static ClaimCourtStageExecutionSnapshot mergeExecutions(
      ClaimCourtStageExecutionSnapshot current, ClaimCourtStageExecutionSnapshot candidate) {
    Map<String, ClaimCourtStageExecutionRecord> records =
        new LinkedHashMap<>(current.records());
    candidate.records().forEach(
        (id, next) -> {
          ClaimCourtStageExecutionRecord prior = records.get(id);
          if (prior != null && !sameExecutionIdentity(prior, next)) {
            throw new IllegalStateException("Claim Court worker produced a conflicting execution");
          }
          if (prior == null || prior.version() < next.version()) {
            records.put(id, next);
          } else if (prior.version() == next.version() && !prior.equals(next)) {
            throw new IllegalStateException("Claim Court worker produced a conflicting execution");
          }
        });
    return new ClaimCourtStageExecutionSnapshot(
        ClaimCourtStageExecutionSnapshot.CURRENT_SCHEMA_VERSION, records);
  }

  private static boolean sameCourtIdentity(ClaimCourtRecord left, ClaimCourtRecord right) {
    return left.courtCaseId().equals(right.courtCaseId())
        && left.frozenClaim().equals(right.frozenClaim())
        && Objects.equals(left.roleAssignment(), right.roleAssignment());
  }

  private static boolean sameRevisionIdentity(
      ClaimProofRevisionRecord left, ClaimProofRevisionRecord right) {
    return left.revisionId().equals(right.revisionId())
        && left.claimId().equals(right.claimId())
        && left.claimSemanticHash().equals(right.claimSemanticHash())
        && Objects.equals(left.baseRevisionId(), right.baseRevisionId())
        && left.proofSteps().equals(right.proofSteps())
        && left.dependencyClaimIds().equals(right.dependencyClaimIds())
        && left.evidenceRefs().equals(right.evidenceRefs())
        && left.proofHash().equals(right.proofHash())
        && left.authorAgentId().equals(right.authorAgentId())
        && Objects.equals(left.repairerAgentId(), right.repairerAgentId())
        && Objects.equals(left.repairPatchId(), right.repairPatchId());
  }

  private static boolean sameExecutionIdentity(
      ClaimCourtStageExecutionRecord left, ClaimCourtStageExecutionRecord right) {
    return left.executionId().equals(right.executionId())
        && left.courtCaseId().equals(right.courtCaseId())
        && left.stage() == right.stage()
        && left.claimIds().equals(right.claimIds())
        && left.inputHash().equals(right.inputHash())
        && left.assignedAgentId().equals(right.assignedAgentId());
  }

  record MergedFrontier(
      ClaimCourtSnapshot court,
      ClaimProofRevisionSnapshot revisions,
      ClaimCourtStageExecutionSnapshot executions) {
    MergedFrontier {
      Objects.requireNonNull(court, "court");
      Objects.requireNonNull(revisions, "revisions");
      Objects.requireNonNull(executions, "executions");
    }
  }
}
