package io.github.aililuola.mathproofmesh.runstate;

import java.util.ArrayList;
import java.util.List;

/** Monotonically reconciles concrete mathematical progress, not only its summary status. */
public final class RunMathematicalProgressReconciler {
  public RunMathematicalProgressReconciliationResult reconcile(
      RunMathematicalProgressSnapshot current, RunMathematicalProgressSnapshot previous) {
    RunMathematicalProgressSnapshot next =
        current == null ? RunMathematicalProgressSnapshot.empty() : current;
    RunMathematicalProgressSnapshot prior =
        previous == null ? RunMathematicalProgressSnapshot.empty() : previous;
    List<RunStateConflict> conflicts = new ArrayList<>();
    String finalProofHash =
        mergeAuthorityHash("FINAL_PROOF_HASH_CONFLICT", prior.finalProofHash(), next.finalProofHash(), conflicts);
    String finalReviewHash =
        mergeAuthorityHash("FINAL_REVIEW_HASH_CONFLICT", prior.finalReviewHash(), next.finalReviewHash(), conflicts);
    List<String> verifiedClaimIds = union(prior.verifiedClaimIds(), next.verifiedClaimIds());
    List<String> refutedClaimIds = union(prior.refutedClaimIds(), next.refutedClaimIds());
    RunMathematicalProgressSnapshot merged =
        new RunMathematicalProgressSnapshot(
            Math.max(prior.verifiedLocalClaims(), next.verifiedLocalClaims()),
            Math.max(prior.refutedClaims(), next.refutedClaims()),
            next.openObligations(),
            prior.frozenProblemPresent() || next.frozenProblemPresent(),
            prior.strategyPresent() || next.strategyPresent(),
            prior.routePresent() || next.routePresent(),
            prior.proofGraphPresent() || next.proofGraphPresent(),
            prior.researchFindingPresent() || next.researchFindingPresent(),
            prior.computationEvidencePresent() || next.computationEvidencePresent(),
            prior.finalProofPresent() || next.finalProofPresent(),
            prior.finalValidationPassed() || next.finalValidationPassed(),
            prior.finalReviewPassed() || next.finalReviewPassed(),
            prior.problemIntegrityOk() && next.problemIntegrityOk(),
            verifiedClaimIds,
            refutedClaimIds,
            verifiedClaimIds.isEmpty()
                ? mergeSetHash(
                    prior.verifiedLocalClaims(),
                    next.verifiedLocalClaims(),
                    prior.verifiedClaimIdSetHash(),
                    next.verifiedClaimIdSetHash(),
                    "VERIFIED_CLAIM_SET_CONFLICT",
                    conflicts)
                : "",
            refutedClaimIds.isEmpty()
                ? mergeSetHash(
                    prior.refutedClaims(),
                    next.refutedClaims(),
                    prior.refutedClaimIdSetHash(),
                    next.refutedClaimIdSetHash(),
                    "REFUTED_CLAIM_SET_CONFLICT",
                    conflicts)
                : "",
            finalProofHash,
            finalReviewHash);
    return new RunMathematicalProgressReconciliationResult(merged, conflicts);
  }

  private static List<String> union(List<String> left, List<String> right) {
    return java.util.stream.Stream.concat(left.stream(), right.stream()).distinct().sorted().toList();
  }

  private static String mergeSetHash(
      int priorCount,
      int nextCount,
      String prior,
      String next,
      String code,
      List<RunStateConflict> conflicts) {
    if (prior.isEmpty()) {
      return next;
    }
    if (next.isEmpty()) {
      return prior;
    }
    if (prior.equals(next)) {
      return prior;
    }
    if (nextCount > priorCount) {
      return next;
    }
    if (priorCount > nextCount) {
      return prior;
    }
    conflicts.add(new RunStateConflict(code, "mathematical_progress", List.of(prior, next)));
    return prior;
  }

  private static String mergeAuthorityHash(
      String code, String prior, String next, List<RunStateConflict> conflicts) {
    if (prior.isEmpty()) {
      return next;
    }
    if (next.isEmpty() || prior.equals(next)) {
      return prior;
    }
    conflicts.add(new RunStateConflict(code, "mathematical_progress", List.of(prior, next)));
    return prior;
  }
}
