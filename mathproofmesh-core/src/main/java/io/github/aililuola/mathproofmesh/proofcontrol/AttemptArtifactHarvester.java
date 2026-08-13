package io.github.aililuola.mathproofmesh.proofcontrol;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ProofAttempt;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Deterministically classifies bounded artifacts without granting mathematical authority. */
@SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification =
        "Artifact tags use Locale.ROOT case mapping followed by final NFKC canonicalization.")
public final class AttemptArtifactHarvester {
  private static final String COUNTEREXAMPLE_TAG = "artifact:counterexample";
  private static final String COUNTEREXAMPLE_TARGET_PREFIX = "counterexample-target:";

  public List<AttemptArtifactRecord> harvest(
      String problemHash,
      String routeId,
      String sourceDeltaId,
      String sourceRouteStatus,
      ProofAttempt attempt,
      Set<String> knownObligationIds) {
    Set<String> obligations =
        knownObligationIds == null ? Set.of() : Set.copyOf(knownObligationIds);
    List<AttemptArtifactRecord> records = new ArrayList<>();
    for (ClaimCard claim : attempt.proposedLemmas()) {
      List<String> normalizedTags = claim.tags().stream().map(AttemptArtifactHarvester::normalizeTag).toList();
      boolean counterexample = normalizedTags.contains(COUNTEREXAMPLE_TAG);
      List<String> targets =
          normalizedTags.stream()
              .filter(tag -> tag.startsWith(COUNTEREXAMPLE_TARGET_PREFIX))
              .map(tag -> tag.substring(COUNTEREXAMPLE_TARGET_PREFIX.length()).strip())
              .filter(target -> !target.isBlank())
              .distinct()
              .toList();
      AttemptArtifactKind kind =
          counterexample ? AttemptArtifactKind.COUNTEREXAMPLE : AttemptArtifactKind.LOCAL_LEMMA;
      String target = targets.size() == 1 ? targets.getFirst() : null;
      boolean targetValid =
          !counterexample || (targets.size() == 1 && obligations.contains(target));
      AttemptArtifactStatus status =
          targetValid ? AttemptArtifactStatus.HARVESTED : AttemptArtifactStatus.UNCERTAIN;
      List<String> history = new ArrayList<>();
      history.add("harvested:" + kind.name().toLowerCase(Locale.ROOT));
      if (normalizedTags.contains("route_theorem")) {
        history.add("ignored_untrusted_route_theorem_tag");
      }
      if (!targetValid) {
        history.add(
            targets.size() == 1
                ? "classification_uncertain:unknown_counterexample_target"
                : "classification_uncertain:counterexample_requires_one_exact_target");
      }
      records.add(
          record(
              problemHash,
              routeId,
              sourceDeltaId,
              sourceRouteStatus,
              attempt,
              claim,
              kind,
              target,
              status,
              history));
    }
    return List.copyOf(records);
  }

  public Optional<AttemptArtifactRecord> harvestRouteTheorem(
      String problemHash,
      String routeId,
      String sourceDeltaId,
      String sourceRouteStatus,
      ProofAttempt attempt,
      ClaimCard internalRouteTheorem,
      boolean validationPassed,
      boolean factPromotionAllowed) {
    if (!"verified".equals(sourceRouteStatus)
        || attempt.status() != io.github.aililuola.mathproofmesh.contract.AttemptStatus.COMPLETE
        || !validationPassed
        || !factPromotionAllowed) {
      return Optional.empty();
    }
    return Optional.of(
        record(
            problemHash,
            routeId,
            sourceDeltaId,
            sourceRouteStatus,
            attempt,
            internalRouteTheorem,
            AttemptArtifactKind.ROUTE_THEOREM,
            null,
            AttemptArtifactStatus.HARVESTED,
            List.of("harvested:route_theorem", "created_by_internal_route_authority")));
  }

  private static AttemptArtifactRecord record(
      String problemHash,
      String routeId,
      String sourceDeltaId,
      String sourceRouteStatus,
      ProofAttempt attempt,
      ClaimCard claim,
      AttemptArtifactKind kind,
      String targetObligationId,
      AttemptArtifactStatus status,
      List<String> history) {
    String artifactId =
        "attempt-artifact-"
            + CanonicalJson.stableHash(
                    Map.of(
                        "problem_hash", problemHash,
                        "route_id", routeId,
                        "attempt_id", attempt.attemptId(),
                        "claim_id", claim.claimId(),
                        "content_hash", claim.contentHash(),
                        "kind", kind.name()))
                .substring(0, 24);
    List<String> evidence =
        claim.evidenceRefs().stream()
            .map(ref -> ref.artifactRef())
            .collect(
                java.util.stream.Collectors.collectingAndThen(
                    java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
    return new AttemptArtifactRecord(
        artifactId,
        problemHash,
        routeId,
        attempt.attemptId(),
        attempt.status(),
        sourceDeltaId,
        sourceRouteStatus,
        kind,
        claim.claimId(),
        claim.contentHash(),
        claim.statement(),
        attempt.agentId(),
        attempt.status() != io.github.aililuola.mathproofmesh.contract.AttemptStatus.COMPLETE,
        targetObligationId,
        status,
        List.of(),
        evidence,
        null,
        0L,
        history);
  }

  private static String normalizeTag(String value) {
    String caseMapped = (value == null ? "" : value).toLowerCase(Locale.ROOT);
    return Normalizer.normalize(caseMapped, Normalizer.Form.NFKC).strip();
  }
}
