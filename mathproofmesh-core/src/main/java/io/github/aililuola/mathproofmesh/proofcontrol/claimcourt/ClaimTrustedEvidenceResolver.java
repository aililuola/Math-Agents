package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.List;

/** Resolves model-supplied evidence references against server-owned authority records. */
public final class ClaimTrustedEvidenceResolver {
  public static final String UNKNOWN_EVIDENCE_REF = "UNKNOWN_EVIDENCE_REF";
  public static final String EVIDENCE_CONTENT_HASH_MISMATCH =
      "EVIDENCE_CONTENT_HASH_MISMATCH";
  public static final String EVIDENCE_PROBLEM_SCOPE_MISMATCH =
      "EVIDENCE_PROBLEM_SCOPE_MISMATCH";
  public static final String EVIDENCE_CLAIM_SCOPE_MISMATCH =
      "EVIDENCE_CLAIM_SCOPE_MISMATCH";
  public static final String EVIDENCE_NOT_VERIFIED = "EVIDENCE_NOT_VERIFIED";
  public static final String EVIDENCE_REPLAY_NOT_VERIFIED =
      "EVIDENCE_REPLAY_NOT_VERIFIED";
  public static final String EVIDENCE_AUTHORITY_ESCALATION =
      "EVIDENCE_AUTHORITY_ESCALATION";

  public record Resolution(
      boolean accepted, String failureCode, TrustedClaimEvidence evidence) {
    public Resolution {
      if (accepted && evidence == null) {
        throw new IllegalArgumentException("accepted evidence resolution requires evidence");
      }
      failureCode = failureCode == null ? "" : failureCode.strip();
    }
  }

  public Resolution resolve(
      FrozenClaimSnapshot frozen,
      EvidenceRef supplied,
      Collection<TrustedClaimEvidence> availableEvidence) {
    java.util.Objects.requireNonNull(frozen, "frozen");
    java.util.Objects.requireNonNull(supplied, "supplied");
    List<TrustedClaimEvidence> byReference =
        safe(availableEvidence).stream()
            .filter(value -> value.evidenceRef().artifactRef().equals(supplied.artifactRef()))
            .toList();
    if (byReference.isEmpty()) {
      return rejected(UNKNOWN_EVIDENCE_REF);
    }
    if (supplied.contentHash() == null || supplied.contentHash().isBlank()) {
      return rejected(EVIDENCE_CONTENT_HASH_MISMATCH);
    }
    List<TrustedClaimEvidence> byHash =
        byReference.stream()
            .filter(
                value ->
                    sameHash(
                        supplied.contentHash(),
                        value.evidenceRef().contentHash()))
            .toList();
    if (byHash.isEmpty()) {
      return rejected(EVIDENCE_CONTENT_HASH_MISMATCH);
    }
    List<TrustedClaimEvidence> byProblem =
        byHash.stream()
            .filter(value -> value.problemHash().equals(frozen.problemHash()))
            .toList();
    if (byProblem.isEmpty()) {
      return rejected(EVIDENCE_PROBLEM_SCOPE_MISMATCH);
    }
    List<TrustedClaimEvidence> byClaim =
        byProblem.stream()
            .filter(
                value ->
                    value.claimSemanticHash().equals(frozen.claimSemanticHash()))
            .toList();
    if (byClaim.isEmpty()) {
      return rejected(EVIDENCE_CLAIM_SCOPE_MISMATCH);
    }
    List<TrustedClaimEvidence> verified =
        byClaim.stream().filter(TrustedClaimEvidence::verified).toList();
    if (verified.isEmpty()) {
      return rejected(EVIDENCE_NOT_VERIFIED);
    }
    List<TrustedClaimEvidence> replayValid =
        verified.stream()
            .filter(value -> !value.authority().replayRequired() || value.replayVerified())
            .toList();
    if (replayValid.isEmpty()) {
      return rejected(EVIDENCE_REPLAY_NOT_VERIFIED);
    }
    TrustedClaimEvidence resolved =
        replayValid.stream()
            .filter(TrustedClaimEvidence::active)
            .filter(TrustedClaimEvidence::proofStepUseAllowed)
            .sorted(java.util.Comparator.comparing(TrustedClaimEvidence::evidenceId))
            .findFirst()
            .orElse(null);
    if (resolved == null) {
      return rejected(EVIDENCE_AUTHORITY_ESCALATION);
    }
    return new Resolution(true, "", resolved);
  }

  private static Resolution rejected(String code) {
    return new Resolution(false, code, null);
  }

  private static List<TrustedClaimEvidence> safe(
      Collection<TrustedClaimEvidence> availableEvidence) {
    return availableEvidence == null ? List.of() : List.copyOf(availableEvidence);
  }

  private static boolean sameHash(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }
}
