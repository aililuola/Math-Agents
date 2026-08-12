package io.github.aililuola.mathproofmesh.verification;

import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Monotonic claim authority with explicit counterexample and dependency invalidation. */
public final class ClaimVerificationLedger {
  private final Map<String, ClaimVerificationLedgerEntry> entries =
      new LinkedHashMap<>();

  public static ClaimVerificationLedger restore(
      Map<String, ClaimVerificationLedgerEntry> snapshot) {
    ClaimVerificationLedger ledger = new ClaimVerificationLedger();
    if (snapshot != null) {
      snapshot.forEach(
          (claimId, entry) -> {
            if (!claimId.equals(entry.claimId())) {
              throw new IllegalArgumentException("claim snapshot key does not match entry");
            }
            ledger.entries.put(claimId, entry);
          });
    }
    return ledger;
  }

  public ClaimVerificationLedgerEntry register(
      String claimId, String sourceAttemptId, Collection<String> dependencies) {
    ClaimVerificationLedgerEntry entry =
        new ClaimVerificationLedgerEntry(
            claimId,
            sourceAttemptId,
            dependencies == null ? List.of() : List.copyOf(dependencies),
            ClaimVerificationState.PROPOSED,
            false,
            "",
            List.of());
    ClaimVerificationLedgerEntry existing = entries.putIfAbsent(claimId, entry);
    return existing == null ? entry : existing;
  }

  public ClaimVerificationLedgerEntry applyClaimVerdict(
      String claimId,
      VerificationVerdict verdict,
      String independentReviewId,
      String counterexample) {
    ClaimVerificationLedgerEntry current = require(claimId);
    ClaimVerificationState state =
        switch (verdict) {
          case PASS -> advance(current.state(), ClaimVerificationState.INDEPENDENTLY_VERIFIED);
          case FAIL ->
              counterexample == null || counterexample.isBlank()
                  ? ClaimVerificationState.REJECTED
                  : ClaimVerificationState.REJECTED;
          case UNCERTAIN, SKIPPED -> current.state();
        };
    String reason =
        verdict == VerificationVerdict.FAIL ? "claim_level_fail" : current.invalidationReason();
    List<String> evidence =
        independentReviewId == null || independentReviewId.isBlank()
            ? current.evidenceIds()
            : append(current.evidenceIds(), independentReviewId);
    return put(
        current,
        state,
        current.sourceAttemptIncomplete(),
        reason,
        evidence);
  }

  public void applyAttemptVerdict(String attemptId, VerificationVerdict verdict) {
    if (verdict == VerificationVerdict.PASS) {
      return;
    }
    entries.replaceAll(
        (ignored, current) ->
            current.sourceAttemptId().equals(attemptId)
                ? copy(current, current.state(), true, current.invalidationReason(), current.evidenceIds())
                : current);
  }

  public ClaimVerificationLedgerEntry promoteFactCandidate(
      String claimId, String refereeReviewId) {
    ClaimVerificationLedgerEntry current = require(claimId);
    if (current.state() != ClaimVerificationState.INDEPENDENTLY_VERIFIED
        && current.state() != ClaimVerificationState.FACT_CANDIDATE
        && current.state() != ClaimVerificationState.FACT) {
      throw new IllegalStateException("only independently verified claims can enter the Fact gate");
    }
    return put(
        current,
        advance(current.state(), ClaimVerificationState.FACT_CANDIDATE),
        current.sourceAttemptIncomplete(),
        current.invalidationReason(),
        append(current.evidenceIds(), refereeReviewId));
  }

  public ClaimVerificationLedgerEntry markFact(
      String claimId, Collection<String> evidenceIds) {
    ClaimVerificationLedgerEntry current = require(claimId);
    if (current.state() != ClaimVerificationState.FACT_CANDIDATE
        && current.state() != ClaimVerificationState.FACT) {
      throw new IllegalStateException("Fact requires the independent promotion gate");
    }
    List<String> evidence = new ArrayList<>(current.evidenceIds());
    if (evidenceIds != null) {
      evidence.addAll(evidenceIds);
    }
    return put(
        current,
        ClaimVerificationState.FACT,
        current.sourceAttemptIncomplete(),
        current.invalidationReason(),
        evidence.stream().distinct().toList());
  }

  public ClaimVerificationLedgerEntry invalidate(
      String claimId, String reason, Collection<String> evidenceIds) {
    ClaimVerificationLedgerEntry current = require(claimId);
    List<String> evidence = new ArrayList<>(current.evidenceIds());
    if (evidenceIds != null) {
      evidence.addAll(evidenceIds);
    }
    return put(
        current,
        ClaimVerificationState.INVALIDATED,
        current.sourceAttemptIncomplete(),
        reason,
        evidence.stream().distinct().toList());
  }

  public List<String> invalidateDependents(
      String claimId, Collection<String> evidenceIds) {
    List<String> invalidated = new ArrayList<>();
    boolean changed;
    do {
      changed = false;
      for (ClaimVerificationLedgerEntry entry : List.copyOf(entries.values())) {
        if (entry.state() == ClaimVerificationState.INVALIDATED
            || entry.state() == ClaimVerificationState.REJECTED) {
          continue;
        }
        boolean dependsOnInvalid =
            entry.dependencyIds().stream()
                .map(entries::get)
                .filter(java.util.Objects::nonNull)
                .anyMatch(item -> item.state() == ClaimVerificationState.INVALIDATED);
        if (dependsOnInvalid) {
          invalidate(entry.claimId(), "dependency_invalidated:" + claimId, evidenceIds);
          invalidated.add(entry.claimId());
          changed = true;
        }
      }
    } while (changed);
    return List.copyOf(invalidated);
  }

  public ClaimVerificationLedgerEntry get(String claimId) {
    return require(claimId);
  }

  public Map<String, ClaimVerificationLedgerEntry> snapshot() {
    return Map.copyOf(entries);
  }

  private ClaimVerificationLedgerEntry put(
      ClaimVerificationLedgerEntry current,
      ClaimVerificationState state,
      boolean sourceAttemptIncomplete,
      String invalidationReason,
      List<String> evidenceIds) {
    ClaimVerificationLedgerEntry next =
        copy(current, state, sourceAttemptIncomplete, invalidationReason, evidenceIds);
    entries.put(current.claimId(), next);
    return next;
  }

  private static ClaimVerificationLedgerEntry copy(
      ClaimVerificationLedgerEntry current,
      ClaimVerificationState state,
      boolean sourceAttemptIncomplete,
      String invalidationReason,
      List<String> evidenceIds) {
    return new ClaimVerificationLedgerEntry(
        current.claimId(),
        current.sourceAttemptId(),
        current.dependencyIds(),
        state,
        sourceAttemptIncomplete,
        invalidationReason,
        evidenceIds);
  }

  private ClaimVerificationLedgerEntry require(String claimId) {
    ClaimVerificationLedgerEntry entry = entries.get(claimId);
    if (entry == null) {
      throw new IllegalArgumentException("unknown claim: " + claimId);
    }
    return entry;
  }

  private static ClaimVerificationState advance(
      ClaimVerificationState current, ClaimVerificationState requested) {
    if (current == ClaimVerificationState.REJECTED
        || current == ClaimVerificationState.INVALIDATED) {
      return current;
    }
    return current.ordinal() >= requested.ordinal() ? current : requested;
  }

  private static List<String> append(List<String> values, String value) {
    if (value == null || value.isBlank()) {
      return values;
    }
    List<String> result = new ArrayList<>(values);
    result.add(value);
    return result.stream().distinct().toList();
  }
}
