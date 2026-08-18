package io.github.aililuola.mathproofmesh.runstate;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.List;

public record RunMathematicalProgressSnapshot(
    int verifiedLocalClaims,
    int refutedClaims,
    int openObligations,
    boolean frozenProblemPresent,
    boolean strategyPresent,
    boolean routePresent,
    boolean proofGraphPresent,
    boolean researchFindingPresent,
    boolean computationEvidencePresent,
    boolean finalProofPresent,
    boolean finalValidationPassed,
    boolean finalReviewPassed,
    boolean problemIntegrityOk,
    List<String> verifiedClaimIds,
    List<String> refutedClaimIds,
    String verifiedClaimIdSetHash,
    String refutedClaimIdSetHash,
    String finalProofHash,
    String finalReviewHash) {

  public RunMathematicalProgressSnapshot(
      int verifiedLocalClaims,
      int refutedClaims,
      int openObligations,
      boolean frozenProblemPresent,
      boolean strategyPresent,
      boolean routePresent,
      boolean proofGraphPresent,
      boolean researchFindingPresent,
      boolean computationEvidencePresent,
      boolean finalProofPresent,
      boolean finalValidationPassed,
      boolean finalReviewPassed,
      boolean problemIntegrityOk) {
    this(
        verifiedLocalClaims,
        refutedClaims,
        openObligations,
        frozenProblemPresent,
        strategyPresent,
        routePresent,
        proofGraphPresent,
        researchFindingPresent,
        computationEvidencePresent,
        finalProofPresent,
        finalValidationPassed,
        finalReviewPassed,
        problemIntegrityOk,
        List.of(),
        List.of(),
        "",
        "",
        "",
        "");
  }

  public RunMathematicalProgressSnapshot {
    if (verifiedLocalClaims < 0 || refutedClaims < 0 || openObligations < 0) {
      throw new IllegalArgumentException("mathematical progress counters must not be negative");
    }
    verifiedClaimIds = normalizeIds(verifiedClaimIds);
    refutedClaimIds = normalizeIds(refutedClaimIds);
    verifiedLocalClaims = Math.max(verifiedLocalClaims, verifiedClaimIds.size());
    refutedClaims = Math.max(refutedClaims, refutedClaimIds.size());
    verifiedClaimIdSetHash = setHash(verifiedClaimIdSetHash, verifiedClaimIds);
    refutedClaimIdSetHash = setHash(refutedClaimIdSetHash, refutedClaimIds);
    finalProofHash = optional(finalProofHash);
    finalReviewHash = optional(finalReviewHash);
  }

  public boolean anyProgress() {
    return frozenProblemPresent
        || strategyPresent
        || routePresent
        || verifiedLocalClaims > 0
        || refutedClaims > 0
        || openObligations > 0
        || proofGraphPresent
        || researchFindingPresent
        || computationEvidencePresent
        || finalProofPresent;
  }

  @Override
  public List<String> verifiedClaimIds() {
    return List.copyOf(verifiedClaimIds);
  }

  @Override
  public List<String> refutedClaimIds() {
    return List.copyOf(refutedClaimIds);
  }

  public static RunMathematicalProgressSnapshot empty() {
    return new RunMathematicalProgressSnapshot(
        0, 0, 0, false, false, false, false, false, false, false, false, false, true);
  }

  private static List<String> normalizeIds(List<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .map(value -> java.util.Objects.requireNonNull(value, "claim id").strip())
        .filter(value -> !value.isEmpty())
        .distinct()
        .sorted()
        .toList();
  }

  private static String setHash(String supplied, List<String> ids) {
    String value = optional(supplied);
    if (ids.isEmpty()) {
      return value;
    }
    String generated = CanonicalJson.stableHash(ids);
    if (!value.isEmpty() && !RunStateHashes.equalHash(value, generated)) {
      throw new IllegalArgumentException("claim id set hash mismatch");
    }
    return generated;
  }

  private static String optional(String value) {
    return value == null ? "" : value.strip();
  }
}
