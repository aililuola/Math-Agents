package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimBlindAdjudicationVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimProofStatus;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementAssessment;
import io.github.aililuola.mathproofmesh.contract.ClaimStatementStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Monotonic, checkpointable authority record for Claim Court cases. */
public final class ClaimCourtLedger {
  private final Map<String, ClaimCourtRecord> records = new LinkedHashMap<>();
  private final List<ClaimCourtAuditEvent> audit = new ArrayList<>();

  public synchronized ClaimCourtRecord open(
      FrozenClaimSnapshot frozen, ClaimCourtRolePolicy.Assignment assignment) {
    java.util.Objects.requireNonNull(frozen, "frozen");
    new ClaimCourtRolePolicy().requireIndependent(assignment);
    ClaimCourtRecord candidate =
        new ClaimCourtRecord(
            frozen.courtCaseId(),
            frozen,
            ClaimCourtCaseStatus.FROZEN,
            ClaimStatementStatus.OPEN,
            ClaimProofStatus.UNREVIEWED,
            null,
            List.of(),
            null,
            frozen.initialProofRevisionId(),
            0,
            assignment,
            null,
            0L,
            List.of("claim identity frozen"));
    ClaimCourtRecord existing = records.putIfAbsent(candidate.courtCaseId(), candidate);
    if (existing != null && !sameIdentity(existing, candidate)) {
      throw new IllegalStateException("claim court case identity collision");
    }
    if (existing == null) {
      audit.add(
          new ClaimCourtAuditEvent(
              audit.size(),
              candidate.courtCaseId(),
              null,
              candidate.status(),
              "claim identity frozen",
              candidate.version()));
    }
    return existing == null ? candidate : existing;
  }

  public synchronized ClaimCourtRecord deferIndependence(FrozenClaimSnapshot frozen) {
    ClaimCourtRecord candidate =
        new ClaimCourtRecord(
            frozen.courtCaseId(),
            frozen,
            ClaimCourtCaseStatus.DEFERRED,
            ClaimStatementStatus.OPEN,
            ClaimProofStatus.UNREVIEWED,
            ClaimCourtOutcome.DEFERRED_INDEPENDENCE_UNAVAILABLE,
            List.of(),
            null,
            frozen.initialProofRevisionId(),
            0,
            null,
            null,
            0L,
            List.of("independent role assignment unavailable"));
    ClaimCourtRecord existing = records.putIfAbsent(candidate.courtCaseId(), candidate);
    if (existing != null && !sameIdentity(existing, candidate)) {
      throw new IllegalStateException("claim court case identity collision");
    }
    return existing == null ? candidate : existing;
  }

  /** Defers an already-open case when a durable stage cannot be safely replayed. */
  public synchronized ClaimCourtRecord defer(String courtCaseId, String reason) {
    ClaimCourtRecord current = required(courtCaseId);
    if (current.status() == ClaimCourtCaseStatus.DEFERRED) {
      return current;
    }
    return transition(
        current,
        ClaimCourtCaseStatus.DEFERRED,
        current.statementStatus(),
        current.proofStatus(),
        ClaimCourtOutcome.DEFERRED_INDEPENDENCE_UNAVAILABLE,
        current.refutationEvidenceIds(),
        current.proofAuditId(),
        null,
        0,
        ClaimCourtValues.required(reason, "reason"));
  }

  public synchronized ClaimCourtRecord beginStatementScreening(String courtCaseId) {
    return transition(
        required(courtCaseId),
        ClaimCourtCaseStatus.STATEMENT_SCREENING,
        ClaimStatementStatus.OPEN,
        ClaimProofStatus.UNREVIEWED,
        null,
        List.of(),
        null,
        null,
        0,
        "statement falsification started");
  }

  public synchronized ClaimCourtRecord recordStatementAssessment(
      String courtCaseId, ClaimStatementAuthorityService.Result result) {
    ClaimCourtRecord current = required(courtCaseId);
    java.util.Objects.requireNonNull(result, "result");
    return switch (result.assessment()) {
      case REFUTED_BY_VERIFIED_EVIDENCE ->
          transition(
              current,
              ClaimCourtCaseStatus.STATEMENT_REFUTED,
              ClaimStatementStatus.REFUTED,
              ClaimProofStatus.UNREVIEWED,
              ClaimCourtOutcome.REFUTED,
              result.evidenceIds(),
              null,
              null,
              0,
              result.detail());
      case OPEN_NO_VERIFIED_REFUTATION ->
          transition(
              current,
              ClaimCourtCaseStatus.PROOF_AUDIT_PENDING,
              ClaimStatementStatus.OPEN,
              ClaimProofStatus.UNREVIEWED,
              null,
              List.of(),
              null,
              null,
              0,
              result.detail());
      case INCONCLUSIVE ->
          transition(
              current,
              ClaimCourtCaseStatus.INCONCLUSIVE,
              ClaimStatementStatus.OPEN,
              ClaimProofStatus.UNREVIEWED,
              ClaimCourtOutcome.INCONCLUSIVE,
              List.of(),
              null,
              null,
              0,
              result.detail());
    };
  }

  public synchronized ClaimCourtRecord recordProofAudit(
      String courtCaseId, String proofAuditId, ClaimProofAuditDecision decision) {
    ClaimCourtRecord current = required(courtCaseId);
    java.util.Objects.requireNonNull(decision, "decision");
    if (!current.frozenClaim().claimId().equals(decision.claimId())) {
      throw new IllegalArgumentException("proof audit targets another claim");
    }
    return switch (decision.verdict()) {
      case VALID ->
          transition(
              current,
              ClaimCourtCaseStatus.PROOF_VALID,
              current.statementStatus(),
              ClaimProofStatus.VALID,
              null,
              current.refutationEvidenceIds(),
              proofAuditId,
              null,
              0,
              "proof audit valid; blind adjudication still required");
      case INVALID_REPAIRABLE ->
          transition(
              current,
              ClaimCourtCaseStatus.PROOF_INVALID_REPAIRABLE,
              current.statementStatus(),
              ClaimProofStatus.INVALID_REPAIRABLE,
              null,
              current.refutationEvidenceIds(),
              proofAuditId,
              null,
              0,
              "proof audit found a bounded local repair");
      case INVALID_UNREPAIRABLE ->
          transition(
              current,
              ClaimCourtCaseStatus.PROOF_INVALID_OPEN,
              current.statementStatus(),
              ClaimProofStatus.INVALID_UNREPAIRABLE,
              ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN,
              current.refutationEvidenceIds(),
              proofAuditId,
              null,
              0,
              "proof invalid but statement remains open");
      case INCONCLUSIVE ->
          transition(
              current,
              ClaimCourtCaseStatus.INCONCLUSIVE,
              current.statementStatus(),
              ClaimProofStatus.UNREVIEWED,
              ClaimCourtOutcome.INCONCLUSIVE,
              current.refutationEvidenceIds(),
              proofAuditId,
              null,
              0,
              "proof audit inconclusive");
    };
  }

  public synchronized ClaimCourtRecord beginRepair(
      String courtCaseId, ClaimCourtConfig config) {
    ClaimCourtRecord current = required(courtCaseId);
    if (current.repairAttempts() >= config.maxRepairAttempts()) {
      return transition(
          current,
          ClaimCourtCaseStatus.REPAIR_EXHAUSTED,
          current.statementStatus(),
          current.proofStatus(),
          ClaimCourtOutcome.REPAIR_EXHAUSTED,
          current.refutationEvidenceIds(),
          current.proofAuditId(),
          null,
          0,
          "bounded repair attempts exhausted");
    }
    return transition(
        current,
        ClaimCourtCaseStatus.REPAIR_PENDING,
        current.statementStatus(),
        current.proofStatus(),
        null,
        current.refutationEvidenceIds(),
        current.proofAuditId(),
        null,
        1,
        "minimal repair started");
  }

  public synchronized ClaimCourtRecord recordRepairedRevision(
      String courtCaseId, String revisionId) {
    ClaimCourtRecord current = required(courtCaseId);
    return transition(
        current,
        ClaimCourtCaseStatus.REPAIRED_PENDING_ADJUDICATION,
        current.statementStatus(),
        ClaimProofStatus.REPAIRED_PENDING_ADJUDICATION,
        null,
        current.refutationEvidenceIds(),
        current.proofAuditId(),
        revisionId,
        0,
        "validated repaired revision awaits blind adjudication");
  }

  public synchronized ClaimCourtRecord recordRepairFailure(
      String courtCaseId, boolean attemptsExhausted, String reason) {
    ClaimCourtRecord current = required(courtCaseId);
    return transition(
        current,
        attemptsExhausted
            ? ClaimCourtCaseStatus.REPAIR_EXHAUSTED
            : ClaimCourtCaseStatus.PROOF_INVALID_OPEN,
        current.statementStatus(),
        ClaimProofStatus.INVALID_UNREPAIRABLE,
        attemptsExhausted
            ? ClaimCourtOutcome.REPAIR_EXHAUSTED
            : ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN,
        current.refutationEvidenceIds(),
        current.proofAuditId(),
        null,
        0,
        ClaimCourtValues.required(reason, "reason"));
  }

  public synchronized ClaimCourtRecord beginBlindAdjudication(String courtCaseId) {
    ClaimCourtRecord current = required(courtCaseId);
    return transition(
        current,
        ClaimCourtCaseStatus.BLIND_ADJUDICATION_PENDING,
        current.statementStatus(),
        current.proofStatus(),
        null,
        current.refutationEvidenceIds(),
        current.proofAuditId(),
        null,
        0,
        "blind adjudication started");
  }

  public synchronized ClaimCourtRecord recordBlindAdjudication(
      String courtCaseId, ClaimBlindAdjudicationVerdict verdict) {
    ClaimCourtRecord current = required(courtCaseId);
    ClaimCourtOutcome outcome =
        new ClaimCourtDecisionService()
            .afterBlindAdjudication(verdict, current.repairAttempts() > 0);
    ClaimCourtCaseStatus status =
        switch (outcome) {
          case VERIFIED -> ClaimCourtCaseStatus.VERIFIED;
          case REPAIR_EXHAUSTED -> ClaimCourtCaseStatus.REPAIR_EXHAUSTED;
          case PROOF_INVALID_BUT_CLAIM_OPEN -> ClaimCourtCaseStatus.PROOF_INVALID_OPEN;
          case INCONCLUSIVE -> ClaimCourtCaseStatus.INCONCLUSIVE;
          case REFUTED -> ClaimCourtCaseStatus.STATEMENT_REFUTED;
          case DEFERRED_INDEPENDENCE_UNAVAILABLE -> ClaimCourtCaseStatus.DEFERRED;
        };
    ClaimProofStatus proofStatus =
        outcome == ClaimCourtOutcome.VERIFIED ? ClaimProofStatus.VALID : current.proofStatus();
    return transition(
        current,
        status,
        current.statementStatus(),
        proofStatus,
        outcome,
        current.refutationEvidenceIds(),
        current.proofAuditId(),
        null,
        0,
        "blind adjudication " + verdict.name().toLowerCase(java.util.Locale.ROOT));
  }

  /** Applies only independently verified evidence that exactly targets the frozen statement. */
  public synchronized ClaimCourtRecord recordVerifiedRefutation(
      String courtCaseId, List<ClaimRefutationEvidence> evidence, String reason) {
    ClaimCourtRecord current = required(courtCaseId);
    List<ClaimRefutationEvidence> verified = ClaimCourtValues.copy(evidence);
    if (verified.isEmpty() || verified.stream().anyMatch(item -> !item.exactFor(current.frozenClaim()))) {
      throw new IllegalArgumentException("verified refutation requires exact trusted evidence");
    }
    return transition(
        current,
        ClaimCourtCaseStatus.STATEMENT_REFUTED,
        ClaimStatementStatus.REFUTED,
        current.proofStatus(),
        ClaimCourtOutcome.REFUTED,
        verified.stream().map(ClaimRefutationEvidence::evidenceId).toList(),
        current.proofAuditId(),
        null,
        0,
        ClaimCourtValues.required(reason, "reason"));
  }

  public synchronized ClaimCourtRecord get(String courtCaseId) {
    return required(courtCaseId);
  }

  /** Resolves both current proof-bound IDs and legacy case IDs by exact proof identity. */
  public synchronized Optional<ClaimCourtRecord> findProofCase(FrozenClaimSnapshot frozen) {
    java.util.Objects.requireNonNull(frozen, "frozen");
    ClaimCourtRecord direct = records.get(frozen.courtCaseId());
    if (direct != null) {
      if (!sameIdentity(direct.frozenClaim(), frozen)) {
        throw new IllegalStateException("claim court case identity collision");
      }
      return Optional.of(direct);
    }
    List<ClaimCourtRecord> matches =
        records.values().stream()
            .filter(record -> sameIdentity(record.frozenClaim(), frozen))
            .toList();
    if (matches.size() > 1) {
      throw new IllegalStateException("duplicate claim court proof identity");
    }
    return matches.stream().findFirst();
  }

  public synchronized List<ClaimCourtRecord> records() {
    return records.values().stream()
        .sorted(Comparator.comparing(ClaimCourtRecord::courtCaseId))
        .toList();
  }

  public synchronized ClaimCourtSnapshot snapshot() {
    return new ClaimCourtSnapshot(ClaimCourtSnapshot.CURRENT_SCHEMA_VERSION, records, audit);
  }

  public synchronized String stableHash() {
    return CanonicalJson.stableHash(snapshot());
  }

  public synchronized void restore(ClaimCourtSnapshot snapshot) {
    ClaimCourtSnapshot source = snapshot == null ? ClaimCourtSnapshot.empty() : snapshot;
    source.records().forEach(
        (id, record) -> {
          if (!id.equals(record.courtCaseId())) {
            throw new IllegalArgumentException("claim court snapshot key mismatch");
          }
        });
    records.clear();
    records.putAll(source.records());
    audit.clear();
    audit.addAll(source.audit());
  }

  private ClaimCourtRecord transition(
      ClaimCourtRecord current,
      ClaimCourtCaseStatus next,
      ClaimStatementStatus statementStatus,
      ClaimProofStatus proofStatus,
      ClaimCourtOutcome outcome,
      List<String> evidenceIds,
      String proofAuditId,
      String revisionId,
      int repairAttemptIncrement,
      String detail) {
    if (current.status() == next && current.status().terminal()) {
      return current;
    }
    if (!allowed(current.status(), next)) {
      throw new IllegalStateException(
          "claim court status cannot move from " + current.status() + " to " + next);
    }
    List<String> history = new ArrayList<>(current.history());
    history.add(detail);
    ClaimCourtRecord updated =
        new ClaimCourtRecord(
            current.courtCaseId(),
            current.frozenClaim(),
            next,
            statementStatus,
            proofStatus,
            outcome,
            evidenceIds,
            proofAuditId == null ? current.proofAuditId() : proofAuditId,
            revisionId == null ? current.currentProofRevisionId() : revisionId,
            current.repairAttempts() + repairAttemptIncrement,
            current.roleAssignment(),
            current.legacyClassification(),
            current.version() + 1L,
            history);
    records.put(updated.courtCaseId(), updated);
    audit.add(
        new ClaimCourtAuditEvent(
            audit.size(),
            updated.courtCaseId(),
            current.status(),
            next,
            detail,
            updated.version()));
    return updated;
  }

  private static boolean allowed(ClaimCourtCaseStatus from, ClaimCourtCaseStatus to) {
    if (from.terminal()) {
      return false;
    }
    if (to == ClaimCourtCaseStatus.DEFERRED) {
      return true;
    }
    if (to == ClaimCourtCaseStatus.STATEMENT_REFUTED) {
      return true;
    }
    return switch (from) {
      case FROZEN -> to == ClaimCourtCaseStatus.STATEMENT_SCREENING;
      case STATEMENT_SCREENING ->
          to == ClaimCourtCaseStatus.STATEMENT_REFUTED
              || to == ClaimCourtCaseStatus.PROOF_AUDIT_PENDING
              || to == ClaimCourtCaseStatus.INCONCLUSIVE;
      case PROOF_AUDIT_PENDING ->
          to == ClaimCourtCaseStatus.PROOF_VALID
              || to == ClaimCourtCaseStatus.PROOF_INVALID_REPAIRABLE
              || to == ClaimCourtCaseStatus.PROOF_INVALID_OPEN
              || to == ClaimCourtCaseStatus.INCONCLUSIVE;
      case PROOF_VALID, REPAIRED_PENDING_ADJUDICATION ->
          to == ClaimCourtCaseStatus.BLIND_ADJUDICATION_PENDING;
      case PROOF_INVALID_REPAIRABLE ->
          to == ClaimCourtCaseStatus.REPAIR_PENDING
              || to == ClaimCourtCaseStatus.REPAIR_EXHAUSTED;
      case REPAIR_PENDING ->
          to == ClaimCourtCaseStatus.REPAIRED_PENDING_ADJUDICATION
              || to == ClaimCourtCaseStatus.REPAIR_EXHAUSTED
              || to == ClaimCourtCaseStatus.PROOF_INVALID_OPEN;
      case BLIND_ADJUDICATION_PENDING ->
          to == ClaimCourtCaseStatus.VERIFIED
              || to == ClaimCourtCaseStatus.PROOF_INVALID_OPEN
              || to == ClaimCourtCaseStatus.REPAIR_EXHAUSTED
              || to == ClaimCourtCaseStatus.INCONCLUSIVE;
      default -> false;
    };
  }

  private ClaimCourtRecord required(String courtCaseId) {
    ClaimCourtRecord record = records.get(ClaimCourtValues.required(courtCaseId, "courtCaseId"));
    if (record == null) {
      throw new IllegalArgumentException("unknown claim court case: " + courtCaseId);
    }
    return record;
  }

  private static boolean sameIdentity(ClaimCourtRecord left, ClaimCourtRecord right) {
    return sameIdentity(left.frozenClaim(), right.frozenClaim());
  }

  private static boolean sameIdentity(
      FrozenClaimSnapshot left, FrozenClaimSnapshot right) {
    return left.claimSemanticHash().equals(right.claimSemanticHash())
        && left.rootGoalHash().equals(right.rootGoalHash())
        && left.problemHash().equals(right.problemHash())
        && left.statementCaseId().equals(right.statementCaseId())
        && ClaimProofRevisionIdentity.compatible(
            left.initialProofRevisionId(), right.initialProofRevisionId());
  }
}
